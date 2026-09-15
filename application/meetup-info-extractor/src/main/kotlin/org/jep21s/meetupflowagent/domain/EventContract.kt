package org.jep21s.meetupflowagent.domain

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/** Вердикт контракта итогового JSON (§7): статус + причины. */
data class Verdict(
  val status: VerdictStatus,
  val reasons: List<String> = emptyList(),
)

enum class VerdictStatus { APPROVED, REJECTED, NEEDS_REVIEW }

/**
 * Контракт итогового JSON агента (§7 COMMON_PLAN.md). Все поля, кроме title,
 * опциональны на уровне парсинга — обязательность проверяет [ContractValidator]
 * (оркестратор, не модель). Даты — ISO-строки с офсетом.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class EventContractDto(
  val title: String? = null,
  val description: String? = null,
  val organizer: String? = null,
  val city: String? = null,
  val isFree: Boolean? = null,
  val price: String? = null,
  val formats: List<String> = emptyList(),
  val address: String? = null,
  val venueName: String? = null,
  val startsAt: String? = null,
  val endsAt: String? = null,
  val talks: List<TalkDto> = emptyList(),
  val registrationUrl: String? = null,
  val sourceUrls: List<String> = emptyList(),
  val language: String? = null,
  val confidence: Double? = null,
) {
  @JsonIgnoreProperties(ignoreUnknown = true)
  data class TalkDto(
    val title: String? = null,
    val speaker: String? = null,
    val description: String? = null,
  )
}

/** Результат пост-валидации контракта (§7): вердикт оркестратора. */
data class ValidatedContract(
  val verdict: Verdict,
  val startsAtInstant: java.time.Instant? = null,
  val endsAtInstant: java.time.Instant? = null,
  val cityNormalized: String? = null,
)
