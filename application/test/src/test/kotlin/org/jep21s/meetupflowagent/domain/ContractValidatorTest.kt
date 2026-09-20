package org.jep21s.meetupflowagent.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Таблица решений детерминированной пост-валидации контракта (§7). */
class ContractValidatorTest {

  private fun dto(
    title: String? = "Митап",
    city: String? = "Санкт-Петербург",
    isFree: Boolean? = true,
    price: String? = null,
    formats: List<String> = listOf("OFFLINE"),
    startsAt: String? = "2026-10-02T19:00+03:00",
    endsAt: String? = "2026-10-02T22:00+03:00",
    venueName: String? = "Севкабель Порт",
    registrationUrl: String? = "https://example.com",
  ) = EventContractDto(
    title = title, city = city, isFree = isFree, price = price, formats = formats,
    startsAt = startsAt, endsAt = endsAt, venueName = venueName, registrationUrl = registrationUrl,
  )

  @Test
  fun `happy path is APPROVED`() {
    val v = ContractValidator.validate(dto()).verdict
    assertThat(v.status).isEqualTo(VerdictStatus.APPROVED)
    assertThat(v.reasons).isEmpty()
  }

  @Test
  fun `paid event is REJECTED with PAID`() {
    val v = ContractValidator.validate(dto(isFree = false, price = "от 3500 ₽")).verdict
    assertThat(v.status).isEqualTo(VerdictStatus.REJECTED)
    assertThat(v.reasons).contains("PAID")
  }

  @Test
  fun `non-SPb city is REJECTED with NOT_SPB`() {
    val v = ContractValidator.validate(dto(city = "Москва")).verdict
    assertThat(v.status).isEqualTo(VerdictStatus.REJECTED)
    assertThat(v.reasons).contains("NOT_SPB")
  }

  @Test
  fun `city synonym normalizes to SPb and stays APPROVED`() {
    val validated = ContractValidator.validate(dto(city = "СПб"))
    assertThat(validated.verdict.status).isEqualTo(VerdictStatus.APPROVED)
    assertThat(validated.cityNormalized).isEqualTo("Санкт-Петербург")
  }

  @Test
  fun `online-only is REJECTED with ONLINE_ONLY`() {
    val v = ContractValidator.validate(dto(formats = listOf("ONLINE"))).verdict
    assertThat(v.status).isEqualTo(VerdictStatus.REJECTED)
    assertThat(v.reasons).contains("ONLINE_ONLY")
  }

  @Test
  fun `missing registration url is NEEDS_REVIEW with MISSING_DATA`() {
    val v = ContractValidator.validate(dto(registrationUrl = null)).verdict
    assertThat(v.status).isEqualTo(VerdictStatus.NEEDS_REVIEW)
    assertThat(v.reasons).contains("MISSING_DATA")
  }

  @Test
  fun `missing endsAt stays APPROVED — delivery channel applies default duration`() {
    // страницы мероприятий часто не публикуют время окончания (live-кейс:
    // team.vk.company/ai-security-nights → NEEDS_REVIEW/MISSING_DATA без события)
    val validated = ContractValidator.validate(dto(endsAt = null))
    assertThat(validated.verdict.status).isEqualTo(VerdictStatus.APPROVED)
    assertThat(validated.verdict.reasons).isEmpty()
    assertThat(validated.endsAtInstant).isNull()
  }

  @Test
  fun `missing mandatory fields are NEEDS_REVIEW not REJECTED`() {
    val v = ContractValidator.validate(dto(title = null, startsAt = null, city = null)).verdict
    assertThat(v.status).isEqualTo(VerdictStatus.NEEDS_REVIEW)
    assertThat(v.reasons).contains("MISSING_DATA")
  }

  @Test
  fun `parsed instants are exposed for scheduling`() {
    val validated = ContractValidator.validate(dto())
    assertThat(validated.startsAtInstant?.toString()).isEqualTo("2026-10-02T16:00:00Z")
    assertThat(validated.endsAtInstant?.toString()).isEqualTo("2026-10-02T19:00:00Z")
  }

  @Test
  fun `rejected rules win over review reasons`() {
    val v = ContractValidator.validate(
      dto(isFree = false, registrationUrl = null),
    ).verdict
    assertThat(v.status).isEqualTo(VerdictStatus.REJECTED)
    assertThat(v.reasons).contains("PAID")
  }
}
