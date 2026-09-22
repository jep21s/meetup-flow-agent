package org.jep21s.meetupflowagent.domain

import java.time.Instant
import java.time.OffsetDateTime

/**
 * Детерминированная пост-валидация контракта §7 — оркестратор, не модель
 * (§7: «LLM извлекает факты и вердикт, код валидирует обязательные поля»).
 *
 * Правила:
 * - REJECTED: платное (isFree=false или цена), не СПб (город после нормализации),
 *   нет офлайн-формата;
 * - NEEDS_REVIEW: нет обязательных title/startsAt/city, отсутствует площадка
 *   (venueName+address) либо про регистрацию ничего не известно — нет ни
 *   registrationUrl, ни явного registrationNotRequired (MISSING_DATA);
 *   endsAt НЕ обязателен — страницы мероприятий часто не публикуют время
 *   окончания, канал доставки применяет дефолтную длительность;
 * - иначе APPROVED.
 */
object ContractValidator {

  private val SPB_SYNONYMS = setOf(
    "санкт-петербург", "спб", "spb", "saint petersburg", "st. petersburg",
    "st.petersburg", "petersburg", "питер", "северная столица",
  )
  private const val SPB_CANONICAL = "Санкт-Петербург"

  fun validate(dto: EventContractDto): ValidatedContract {
    val rejected = mutableListOf<String>()
    val review = mutableListOf<String>()

    // --- жёсткие правила отбора группы (REJECTED) ---
    if (dto.isFree == false || (!dto.price.isNullOrBlank() && dto.isFree != true)) {
      rejected += "PAID"
    }
    val city = dto.city?.trim()
    val cityNormalized = city?.let(::normalizeCity)
    if (city != null && cityNormalized != SPB_CANONICAL) {
      rejected += "NOT_SPB"
    }
    if (dto.formats.none { it.equals("OFFLINE", ignoreCase = true) }) {
      rejected += "ONLINE_ONLY"
    }

    // --- обязательные данные ---
    if (dto.title.isNullOrBlank() || dto.startsAt.isNullOrBlank() || city == null) {
      review += "MISSING_DATA"
    }
    val startsAt = dto.startsAt?.let(::parseInstant)
    if (dto.startsAt != null && startsAt == null) {
      review += "BAD_STARTS_AT"
    }
    // venueName некритичен при известном address (Площадь Конституции, 2 — адрес и есть площадка);
    // endsAt не обязателен — конец добирается дефолтной длительностью канала доставки;
    // регистрация известна, если есть URL ИЛИ явное «регистрация не требуется»
    // (registrationNotRequired — из сообщения/страницы либо подтверждение человека)
    val registrationKnown = !dto.registrationUrl.isNullOrBlank() || dto.registrationNotRequired == true
    if (!registrationKnown ||
      (dto.venueName.isNullOrBlank() && dto.address.isNullOrBlank())
    ) {
      review += "MISSING_DATA"
    }

    val verdict = when {
      rejected.isNotEmpty() -> Verdict(VerdictStatus.REJECTED, rejected)
      review.isNotEmpty() -> Verdict(VerdictStatus.NEEDS_REVIEW, review.distinct())
      else -> Verdict(VerdictStatus.APPROVED)
    }
    return ValidatedContract(
      verdict = verdict,
      startsAtInstant = startsAt,
      endsAtInstant = dto.endsAt?.let(::parseInstant),
      cityNormalized = cityNormalized,
    )
  }

  private fun normalizeCity(raw: String): String =
    if (raw.lowercase().trim() in SPB_SYNONYMS) SPB_CANONICAL else raw.trim()

  private fun parseInstant(raw: String): Instant? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    return runCatching { Instant.parse(value) }.getOrElse {
      runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
    }
  }
}
