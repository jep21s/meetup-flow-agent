package org.jep21s.meetupflowagent.guardrails

/** Вердикт guardrails (§8.1): проход / инъекция / подозрительно / не по теме. */
data class GuardrailsVerdict(
  val verdict: Verdict,
  val reasons: List<String> = emptyList(),
) {
  enum class Verdict { PASS, INJECTION, SUSPICIOUS, OFF_TOPIC }

  val isPass: Boolean get() = verdict == Verdict.PASS
}

/**
 * Детерминированные проверки ДО вызова модели (§8): длина, URL-спам,
 * контрольные символы (U+0000–U+0008, U+000E–U+001F; таб/CR/LF допустимы).
 * Возвращает null, если текст чист — тогда решает LLM-модератор.
 */
object DeterministicGuardrails {

  fun check(text: String): GuardrailsVerdict? {
    if (text.length > MAX_TEXT_LENGTH) {
      return blocked("message length ${text.length} exceeds $MAX_TEXT_LENGTH")
    }
    val urlCount = URL_REGEX.findAll(text).count()
    if (urlCount > MAX_URL_COUNT) {
      return blocked("URL spam: $urlCount links (max $MAX_URL_COUNT)")
    }
    val control = CONTROL_RANGES.firstNotNullOfOrNull { range ->
      text.firstOrNull { it.code in range }?.let { "U+%04X".format(it.code) }
    }
    if (control != null) {
      return blocked("control character $control")
    }
    return null
  }

  /** Детерминированное отклонение трактуется как SUSPICIOUS (fail-safe блокировка). */
  private fun blocked(reason: String) =
    GuardrailsVerdict(GuardrailsVerdict.Verdict.SUSPICIOUS, listOf(reason))

  private val URL_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
  private val CONTROL_RANGES = listOf(0x00..0x08, 0x0E..0x1F)

  const val MAX_TEXT_LENGTH = 10_000
  const val MAX_URL_COUNT = 5
}
