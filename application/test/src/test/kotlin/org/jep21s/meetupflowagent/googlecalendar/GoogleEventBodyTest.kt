package org.jep21s.meetupflowagent.googlecalendar

import org.assertj.core.api.Assertions.assertThat
import org.jep21s.meetupflowagent.outbox.OutboxPublicationPayload
import org.jep21s.meetupflowagent.outbox.PublishedEvent
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Чистая сборка тела Calendar API events.insert из канонического снапшота
 * публикации (без сервера): маппинг полей, дефолтная длительность, таймзона,
 * обрезки, формат клиентского id.
 */
class GoogleEventBodyTest {

  @Test
  fun `maps full payload to calendar event`() {
    val payload = payload(
      PublishedEvent(
        eventId = "0f14d0ab-9605-4a62-a9e4-5ed26688389b",
        title = "SPb Go #20",
        startsAt = Instant.parse("2026-10-15T16:00:00Z"),
        endsAt = Instant.parse("2026-10-15T19:30:00Z"),
        venueName = "Мраморный зал ПОМИ РАН",
        address = "наб. реки Фонтанки, 27",
        city = "Санкт-Петербург",
        registrationUrl = "https://example.com/reg",
        description = "Доклады про Go",
        organizer = "SPb Go",
        tags = listOf("OFFLINE", "GO"),
        isFree = true,
      )
    )

    val body = GoogleCalendarOutboxTransport.buildEventBody(payload)

    assertThat(body.path("id").asText()).isEqualTo("mfa-0f14d0ab96054a62a9e45ed26688389b")
    assertThat(body.path("id").asText()).matches("^mfa-[0-9a-f]+$") // charset id Google (base32hex + -_)
    assertThat(body.path("summary").asText()).isEqualTo("SPb Go #20")
    assertThat(body.path("location").asText())
      .isEqualTo("Мраморный зал ПОМИ РАН, наб. реки Фонтанки, 27, Санкт-Петербург")
    assertThat(body.path("description").asText())
      .contains("Регистрация: https://example.com/reg")
      .contains("Организатор: SPb Go")
      .contains("Доклады про Go")
      .contains("OFFLINE, GO")
    assertThat(body.path("start").path("dateTime").asText()).isEqualTo("2026-10-15T19:00:00+03:00")
    assertThat(body.path("start").path("timeZone").asText()).isEqualTo("Europe/Moscow")
    assertThat(body.path("end").path("dateTime").asText()).isEqualTo("2026-10-15T22:30:00+03:00")
    assertThat(body.path("end").path("timeZone").asText()).isEqualTo("Europe/Moscow")
    val priv = body.path("extendedProperties").path("private")
    assertThat(priv.path("source").asText()).isEqualTo("meetup-flow-agent")
    assertThat(priv.path("eventId").asText()).isEqualTo("0f14d0ab-9605-4a62-a9e4-5ed26688389b")
    assertThat(priv.path("flowId").asText()).isEqualTo("11111111-1111-1111-1111-111111111111")
    assertThat(priv.path("deliveryDedupKey").asText()).isEqualTo("dedup-1")
    assertThat(body.path("reminders").path("useDefault").asBoolean()).isTrue()
  }

  @Test
  fun `null endsAt falls back to default duration of 180 minutes`() {
    val payload = payload(
      event(startsAt = Instant.parse("2026-10-15T16:00:00Z"), endsAt = null),
    )

    val body = GoogleCalendarOutboxTransport.buildEventBody(payload)

    assertThat(body.path("start").path("dateTime").asText()).isEqualTo("2026-10-15T19:00:00+03:00")
    assertThat(body.path("end").path("dateTime").asText()).isEqualTo("2026-10-15T22:00:00+03:00")
  }

  @Test
  fun `default duration is configurable`() {
    val payload = payload(
      event(startsAt = Instant.parse("2026-10-15T16:00:00Z"), endsAt = null),
    )

    val body = GoogleCalendarOutboxTransport.buildEventBody(
      payload, timezone = "Europe/Moscow", defaultDurationMinutes = 60,
    )

    assertThat(body.path("end").path("dateTime").asText()).isEqualTo("2026-10-15T20:00:00+03:00")
  }

  @Test
  fun `custom timezone renders offset and Z for UTC`() {
    val payload = payload(event(startsAt = Instant.parse("2026-10-15T16:00:00Z")))

    val body = GoogleCalendarOutboxTransport.buildEventBody(payload, timezone = "UTC")

    assertThat(body.path("start").path("dateTime").asText()).isEqualTo("2026-10-15T16:00:00Z")
    assertThat(body.path("start").path("timeZone").asText()).isEqualTo("UTC")
  }

  @Test
  fun `blank optional fields are omitted`() {
    val payload = payload(
      PublishedEvent(
        eventId = "0f14d0ab-9605-4a62-a9e4-5ed26688389b",
        title = "Митап",
        startsAt = Instant.parse("2026-10-15T16:00:00Z"),
      )
    )

    val body = GoogleCalendarOutboxTransport.buildEventBody(payload)

    assertThat(body.has("location")).isFalse()
    assertThat(body.has("description")).isFalse()
  }

  @Test
  fun `long description is truncated to 2000 chars`() {
    val payload = payload(
      PublishedEvent(
        eventId = "0f14d0ab-9605-4a62-a9e4-5ed26688389b",
        title = "Митап",
        startsAt = Instant.parse("2026-10-15T16:00:00Z"),
        endsAt = Instant.parse("2026-10-15T19:30:00Z"),
        description = "а".repeat(3000),
      )
    )

    val body = GoogleCalendarOutboxTransport.buildEventBody(payload)

    assertThat(body.path("description").asText()).hasSize(2000)
  }

  private fun event(
    startsAt: Instant = Instant.parse("2026-10-15T16:00:00Z"),
    endsAt: Instant? = Instant.parse("2026-10-15T19:30:00Z"),
    description: String? = "Доклады",
  ) = PublishedEvent(
    eventId = "0f14d0ab-9605-4a62-a9e4-5ed26688389b",
    title = "SPb Go #20",
    startsAt = startsAt,
    endsAt = endsAt,
    venueName = "Мраморный зал ПОМИ РАН",
    address = "наб. реки Фонтанки, 27",
    city = "Санкт-Петербург",
    registrationUrl = "https://example.com/reg",
    description = description,
    organizer = "SPb Go",
    tags = listOf("OFFLINE"),
  )

  private fun payload(event: PublishedEvent) = OutboxPublicationPayload(
    flowId = "11111111-1111-1111-1111-111111111111",
    deliveryDedupKey = "dedup-1",
    event = event,
    finishedAt = Instant.parse("2026-09-20T10:00:00Z"),
  )
}
