package org.jep21s.meetupflowagent.telegram

import org.assertj.core.api.Assertions.assertThat
import org.jep21s.meetupflowagent.outbox.OutboxPublicationPayload
import org.jep21s.meetupflowagent.outbox.PublishedEvent
import org.jep21s.meetupflowagent.telegram.outbox.TelegramOutboxTransport
import org.junit.jupiter.api.Test
import java.time.Instant

/** Рендер анонса: регистрация — либо ссылка, либо явное «не требуется». */
class AnnouncementFormatTest {

  private fun payload(registrationUrl: String? = null, registrationNotRequired: Boolean? = null) =
    OutboxPublicationPayload(
      flowId = "815b898d-4b8b-4d76-8fa6-854419a3e2a0",
      deliveryDedupKey = "d",
      event = PublishedEvent(
        eventId = "e",
        title = "Coffee&Code QA",
        startsAt = Instant.parse("2026-10-09T16:00:00Z"),
        registrationUrl = registrationUrl,
        registrationNotRequired = registrationNotRequired,
      ),
      finishedAt = Instant.EPOCH,
    )

  @Test
  fun `no-registration event says so explicitly`() {
    val text = TelegramOutboxTransport.formatAnnouncement(payload(registrationNotRequired = true))
    assertThat(text).contains("🎟 Регистрация не требуется")
    assertThat(text).contains("регистрация не нужна")
    assertThat(text).doesNotContain("🔗")
  }

  @Test
  fun `url registration renders link and default footer`() {
    val text = TelegramOutboxTransport.formatAnnouncement(payload(registrationUrl = "https://timepad.ru/x"))
    assertThat(text).contains("🔗 https://timepad.ru/x")
    assertThat(text).contains("участие после регистрации")
    assertThat(text).doesNotContain("Регистрация не требуется")
  }
}
