package org.jep21s.meetupflowagent.googlecalendar

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jep21s.meetupflowagent.db.DestinationRepository
import org.jep21s.meetupflowagent.db.EventRepository
import org.jep21s.meetupflowagent.db.EventRow
import org.jep21s.meetupflowagent.db.FlowRepository
import org.jep21s.meetupflowagent.db.OutboxRepository
import org.jep21s.meetupflowagent.observability.Metrics
import org.jep21s.meetupflowagent.outbox.OutboxDeliveryPoller
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.jep21s.meetupflowagent.testsupport.PostgresTestBase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Сквозной контур доставки Google Calendar (реальный Postgres + реальный поллер
 * + реальный транспорт; Google подменён WireMock): fan-out по активному
 * назначению → jwt-bearer-токен → events.insert; идемпотентный 409; ошибка →
 * PENDING с ретраем.
 */
class GoogleCalendarDeliveryIT : PostgresTestBase() {

  private val wireMock = WireMockServer(wireMockConfig().dynamicPort())
  private val metrics = Metrics.inMemory()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val eventRepository = EventRepository(testConnectivity())
  private val flowRepository = FlowRepository(testConnectivity())
  private val outboxRepository = OutboxRepository(
    testConnectivity(),
    DestinationRepository(testConnectivity()),
    eventRepository,
  )

  @BeforeEach
  fun startWireMock() {
    wireMock.start()
  }

  @AfterEach
  fun stopWireMock() {
    wireMock.stop()
    scope.cancel()
    System.clearProperty("google.calendar.credentials-json")
    System.clearProperty("outbox.schedule")
  }

  @Test
  fun `delivery reaches SENT and posts formatted event to calendar`(): Unit = runBlocking {
    activateGoogleDestination()
    stubToken()
    stubEvents(200, """{"id":"gcal-evt-1","status":"confirmed"}""")

    val flowId = enqueuePublication()
    poller().pollOnce()

    awaitDelivery(flowId, "SENT")

    wireMock.verify(1, postRequestedFor(urlEqualTo("/token")))
    val inserts = wireMock.findAll(postRequestedFor(urlPathMatching("/calendar/v3/calendars/.+/events")))
    assertThat(inserts).hasSize(1)
    val insert = inserts.single()
    assertThat(insert.url).contains("/calendar/v3/calendars/test-cal@group.calendar.google.com/events")
    assertThat(insert.getHeader("Authorization")).isEqualTo("Bearer it-token")
    val body = jacksonMapper.readTree(insert.bodyAsString)
    assertThat(body.path("summary").asText()).isEqualTo("SPb Go #20")
    assertThat(body.path("id").asText()).startsWith("mfa")
    assertThat(body.path("start").path("timeZone").asText()).isEqualTo("Europe/Moscow")
    assertThat(body.path("extendedProperties").path("private").path("flowId").asText()).isEqualTo(flowId.toString())
  }

  @Test
  fun `409 duplicate still counts as sent - idempotent redelivery`(): Unit = runBlocking {
    activateGoogleDestination()
    stubToken()
    stubEvents(409, """{"error":{"code":409,"message":"Duplicate detected"}}""")

    val flowId = enqueuePublication()
    poller().pollOnce()

    awaitDelivery(flowId, "SENT")
    assertThat(wireMock.findAll(postRequestedFor(urlPathMatching("/calendar/v3/calendars/.+/events")))).hasSize(1)
  }

  @Test
  fun `insert failure returns delivery to PENDING with error and retry plan`(): Unit = runBlocking {
    System.setProperty("outbox.schedule", "1h")
    activateGoogleDestination()
    stubToken()
    stubEvents(500, """{"error":{"code":500,"message":"Backend error"}}""")

    val flowId = enqueuePublication()
    poller().pollOnce()

    awaitDelivery(flowId, "PENDING")
    val delivery = outboxRepository.deliveriesByFlow(flowId).single()
    assertThat(delivery.attempts).isEqualTo(1)
    assertThat(delivery.lastError).contains("HTTP 500")
    assertThat(delivery.nextRetryAt).isAfter(Instant.now())

    System.clearProperty("outbox.schedule")
  }

  @Test
  fun `destination without calendarId fails loudly instead of silently succeeding`(): Unit = runBlocking {
    System.setProperty("outbox.schedule", "1h")
    executeSql("UPDATE destinations SET type = 'google_calendar', config = '{}'::jsonb WHERE name = 'telegram_main'")
    stubToken()

    val flowId = enqueuePublication()
    poller().pollOnce()

    awaitDelivery(flowId, "PENDING")
    val delivery = outboxRepository.deliveriesByFlow(flowId).single()
    assertThat(delivery.lastError).contains("calendarId")
    // в Google не уходило ничего
    assertThat(wireMock.findAll(postRequestedFor(urlPathMatching("/calendar/v3/")))).isEmpty()

    System.clearProperty("outbox.schedule")
  }

  private fun poller(): OutboxDeliveryPoller =
    OutboxDeliveryPoller(outboxRepository, listOf(googleTransport()), metrics, scope)

  /** Реальный транспорт с реальным HTTP-клиентом на WireMock (Google подменён). */
  private fun googleTransport(): GoogleCalendarOutboxTransport {
    System.setProperty("google.calendar.credentials-json", TestServiceAccounts.json())
    return GoogleCalendarOutboxTransport(
      GoogleCalendarClient(
        httpClient = GoogleCalendarClient.defaultHttpClient(),
        tokenUrl = "${wireMock.baseUrl()}/token",
        apiBase = "${wireMock.baseUrl()}/calendar/v3",
      ),
    )
  }

  private suspend fun enqueuePublication(): UUID {
    val flowId = flowRepository.create("PROCESSING")
    outboxRepository.insertEventAndEnqueue(
      EventRow(
        id = UUID.randomUUID(),
        flowId = flowId,
        title = "SPb Go #20",
        description = "Доклады про Go",
        organizer = "SPb Go",
        city = "Санкт-Петербург",
        venueName = "Мраморный зал ПОМИ РАН",
        startsAt = Instant.parse("2026-10-15T16:00:00Z"),
        registrationUrl = "https://example.com/reg",
      ),
    )
    return flowId
  }

  private fun activateGoogleDestination() {
    executeSql(
      "UPDATE destinations SET type = 'google_calendar', " +
        "config = '{\"calendarId\":\"test-cal@group.calendar.google.com\"}'::jsonb " +
        "WHERE name = 'telegram_main'",
    )
  }

  private fun stubToken() {
    wireMock.stubFor(
      post(urlEqualTo("/token"))
        .willReturn(okJson("""{"access_token":"it-token","expires_in":3600,"token_type":"Bearer"}""")),
    )
  }

  private fun stubEvents(status: Int, body: String) {
    wireMock.stubFor(
      post(urlPathMatching("/calendar/v3/calendars/.+/events"))
        .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)),
    )
  }

  private fun awaitDelivery(flowId: UUID, expectedStatus: String) {
    val deadline = System.currentTimeMillis() + 10_000
    while (System.currentTimeMillis() < deadline) {
      val delivery = runBlocking { outboxRepository.deliveriesByFlow(flowId).singleOrNull() }
      if (delivery != null && delivery.status == expectedStatus) return
      Thread.sleep(100)
    }
    error("delivery did not reach $expectedStatus in 10s: " + runBlocking { outboxRepository.deliveriesByFlow(flowId) })
  }

  private fun executeSql(sql: String) {
    transaction(database) { exec(sql) }
  }
}
