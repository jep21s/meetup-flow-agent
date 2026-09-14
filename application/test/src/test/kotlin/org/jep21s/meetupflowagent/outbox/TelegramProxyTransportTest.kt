package org.jep21s.meetupflowagent.outbox

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.ok
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.serverError
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jep21s.meetupflowagent.db.DestinationRow
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Контракт телеграм-транспорта (WireMock): POST /api/notify + Bearer,
 * event=EVENT_PUBLISHED, userIds пустые (общий канал), текст анонса содержит
 * ключевые поля, deliveryId для дедупликации; 5xx/сеть → исключение (ретраит
 * поллер); пустой proxy.baseUrl → доставка считается выполненной.
 */
class TelegramProxyTransportTest {

  private val wireMock = WireMockServer(wireMockConfig().dynamicPort())

  private val destination = DestinationRow(
    id = UUID.randomUUID(),
    type = "telegram_proxy",
    name = "telegram_main",
    config = jacksonMapper.readTree("{}"),
    isActive = true,
  )

  private fun task(deliveryId: UUID = UUID.randomUUID()): OutboxDeliveryTask = OutboxDeliveryTask(
    deliveryId = deliveryId,
    messageId = UUID.randomUUID(),
    attempts = 0,
    destination = destination,
    payload = parsePublicationPayload(
      jacksonMapper.valueToTree(
        mapOf(
          "flowId" to "11111111-1111-1111-1111-111111111111",
          "deliveryDedupKey" to "22222222-2222-2222-2222-222222222222",
          "event" to mapOf(
            "eventId" to "33333333-3333-3333-3333-333333333333",
            "title" to "SPb Go Community #20",
            "startsAt" to "2026-10-15T16:00:00Z",
            "endsAt" to null,
            "venueName" to "Кластер Ленполиграф",
            "address" to "ул. Правды 24, СПб",
            "city" to "Санкт-Петербург",
            "registrationUrl" to "https://golang-timepad.ru",
            "description" to "Доклады о горутинах",
            "organizer" to "SPb Go",
            "tags" to listOf("OFFLINE"),
            "isFree" to true,
          ),
          "finishedAt" to "2026-09-13T10:00:00Z",
        ),
      ),
    ),
  )

  @AfterEach
  fun tearDown() {
    if (wireMock.isRunning) wireMock.stop()
    System.clearProperty("proxy.baseUrl")
    System.clearProperty("proxy.token")
  }

  @Test
  fun `publishes EVENT_PUBLISHED to proxy with announcement text and dedup key`() {
    wireMock.start()
    System.setProperty("proxy.baseUrl", wireMock.baseUrl())
    System.setProperty("proxy.token", "secret-token")
    val deliveryId = UUID.randomUUID()
    wireMock.stubFor(
      post(urlEqualTo("/api/notify"))
        .willReturn(ok()),
    )

    runBlocking { TelegramProxyTransport().deliver(task(deliveryId)) }

    // проверяем пойманный запрос напрямую — без хрупких regex-паттернов WireMock
    val requests = wireMock.findAll(postRequestedFor(urlEqualTo("/api/notify")))
    assertThat(requests).hasSize(1)
    val request = requests.single()
    assertThat(request.header("Authorization").firstValue()).isEqualTo("Bearer secret-token")
    assertThat(request.header("Content-Type").firstValue()).contains("application/json")
    val body = jacksonMapper.readTree(request.bodyAsString)
    assertThat(body.path("flowId").asText()).isEqualTo("11111111-1111-1111-1111-111111111111")
    assertThat(body.path("event").asText()).isEqualTo("EVENT_PUBLISHED")
    assertThat(body.path("userIds")).isEmpty()
    assertThat(body.path("deliveryId").asText()).isEqualTo(deliveryId.toString())
    // текст анонса: название, место, адрес, ссылка, московское время
    val text = body.path("text").asText()
    assertThat(text).contains("SPb Go Community #20")
    assertThat(text).contains("Кластер Ленполиграф")
    assertThat(text).contains("ул. Правды 24, СПб")
    assertThat(text).contains("https://golang-timepad.ru")
    assertThat(text).contains("МСК")
  }

  @Test
  fun `5xx response fails the delivery attempt`() {
    wireMock.start()
    System.setProperty("proxy.baseUrl", wireMock.baseUrl())
    wireMock.stubFor(post(urlEqualTo("/api/notify")).willReturn(serverError()))

    assertThatThrownBy { runBlocking { TelegramProxyTransport().deliver(task()) } }
      .isInstanceOf(OutboxDeliveryException::class.java)
      .hasMessageContaining("500")
  }

  @Test
  fun `network failure fails the delivery attempt`() {
    wireMock.start()
    val deadPort = wireMock.port()
    wireMock.stop() // порт уже не слушается
    System.setProperty("proxy.baseUrl", "http://127.0.0.1:$deadPort")

    assertThatThrownBy { runBlocking { TelegramProxyTransport().deliver(task()) } }
      .isInstanceOf(OutboxDeliveryException::class.java)
  }

  @Test
  fun `empty proxy baseUrl counts delivery as sent`() {
    System.setProperty("proxy.baseUrl", "")

    assertThatCode { runBlocking { TelegramProxyTransport().deliver(task()) } }
      .doesNotThrowAnyException()
  }

  @Test
  fun `announcement text is stable and self-sufficient`() {
    val text = TelegramProxyTransport.formatAnnouncement(task().payload)
    assertThat(text).contains("📣 SPb Go Community #20")
    assertThat(text).contains("📍 Кластер Ленполиграф, ул. Правды 24, СПб")
    assertThat(text).contains("#OFFLINE")
    assertThat(text).contains("бесплатный")
  }
}
