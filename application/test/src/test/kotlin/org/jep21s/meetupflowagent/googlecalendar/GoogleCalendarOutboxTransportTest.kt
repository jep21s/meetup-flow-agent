package org.jep21s.meetupflowagent.googlecalendar

import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.jep21s.meetupflowagent.db.DestinationRow
import org.jep21s.meetupflowagent.outbox.OutboxDeliveryException
import org.jep21s.meetupflowagent.outbox.OutboxDeliveryTask
import org.jep21s.meetupflowagent.outbox.OutboxPublicationPayload
import org.jep21s.meetupflowagent.outbox.OutboxTransport
import org.jep21s.meetupflowagent.outbox.PublishedEvent
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

/**
 * Транспорт доставки (клиент замокан): адресация по destinations.config,
 * сборка тела, идемпотентный 409, обёртка ошибок API в OutboxDeliveryException.
 */
class GoogleCalendarOutboxTransportTest {

  private val client = mockk<GoogleCalendarClient>()

  @Test
  fun `type matches destinations dictionary`() {
    assertThat(GoogleCalendarOutboxTransport(client)).isInstanceOf(OutboxTransport::class.java)
    assertThat(GoogleCalendarOutboxTransport(client).type).isEqualTo("google_calendar")
  }

  @Test
  fun `missing calendarId in destination config fails with clear message`() {
    val transport = GoogleCalendarOutboxTransport(client)
    val task = task(configJson = """{}""")

    val ex = assertThrows<OutboxDeliveryException> { runBlocking { transport.deliver(task) } }

    assertThat(ex.message).contains("google_main").contains("calendarId")
  }

  @Test
  fun `happy path sends formatted body to destination calendar`() {
    val bodySlot = slot<com.fasterxml.jackson.databind.JsonNode>()
    coEvery { client.insertEvent("test-cal@group.calendar.google.com", capture(bodySlot)) } answers {
      InsertOutcome.Inserted("gcal-evt-1")
    }
    val transport = GoogleCalendarOutboxTransport(client)

    runBlocking { transport.deliver(task(configJson = """{"calendarId":"test-cal@group.calendar.google.com"}""")) }

    val body = bodySlot.captured
    assertThat(body.path("summary").asText()).isEqualTo("SPb Go #20")
    assertThat(body.path("id").asText()).isEqualTo("mfa0f14d0ab96054a62a9e45ed26688389b")
    assertThat(body.path("start").path("dateTime").asText()).isEqualTo("2026-10-15T19:00:00+03:00")
    assertThat(body.path("end").path("dateTime").asText()).isEqualTo("2026-10-15T22:00:00+03:00") // +180 мин
  }

  @Test
  fun `409 AlreadyPresent completes without exception`() {
    coEvery { client.insertEvent(any(), any()) } returns InsertOutcome.AlreadyPresent
    val transport = GoogleCalendarOutboxTransport(client)

    runBlocking { transport.deliver(task(configJson = """{"calendarId":"test-cal@group.calendar.google.com"}""")) }
    // исключения нет — доставка считается успешной (событие уже в календаре)
  }

  @Test
  fun `GoogleApiException is wrapped into OutboxDeliveryException with message`() {
    coEvery { client.insertEvent(any(), any()) } throws
      GoogleApiException("google calendar events.insert HTTP 500 calendar=test-cal: boom")
    val transport = GoogleCalendarOutboxTransport(client)

    val ex = assertThrows<OutboxDeliveryException> {
      runBlocking { transport.deliver(task(configJson = """{"calendarId":"test-cal@group.calendar.google.com"}""")) }
    }

    assertThat(ex.message).contains("HTTP 500")
    assertThat(ex.cause).isInstanceOf(GoogleApiException::class.java)
  }

  private fun task(configJson: String): OutboxDeliveryTask = OutboxDeliveryTask(
    deliveryId = UUID.randomUUID(),
    messageId = UUID.randomUUID(),
    attempts = 0,
    destination = DestinationRow(
      id = UUID.randomUUID(),
      type = "google_calendar",
      name = "google_main",
      config = jacksonMapper.readTree(configJson),
      isActive = true,
    ),
    payload = OutboxPublicationPayload(
      flowId = "11111111-1111-1111-1111-111111111111",
      deliveryDedupKey = "22222222-2222-2222-2222-222222222222",
      event = PublishedEvent(
        eventId = "0f14d0ab-9605-4a62-a9e4-5ed26688389b",
        title = "SPb Go #20",
        startsAt = Instant.parse("2026-10-15T16:00:00Z"),
        endsAt = null,
        venueName = "Мраморный зал ПОМИ РАН",
        city = "Санкт-Петербург",
      ),
      finishedAt = Instant.parse("2026-09-20T10:00:00Z"),
    ),
  )
}
