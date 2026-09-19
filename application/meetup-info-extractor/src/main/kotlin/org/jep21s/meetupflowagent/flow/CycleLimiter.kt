package org.jep21s.meetupflowagent.flow

import java.security.MessageDigest

/**
 * Прогресс-детектор цикла ReAct (§8.4): сигнатура итерации = каноническое
 * представление {извлечённые факты контракта, история tool-вызовов (имя + хэш
 * аргументов)}; сигнатура не менялась [STALL_THRESHOLD] циклов подряд → прогресса нет.
 */
class ProgressDetector {

  private var previousSignature: String? = null
  private var stallCount = 0

  /**
   * Фиксирует итерацию; возвращает true, если есть прогресс (сигнатура изменилась
   * или стагнация ещё не достигла порога).
   */
  fun update(facts: Set<String>, toolCalls: List<Pair<String, String>>): Boolean {
    val signature = signature(facts, toolCalls)
    if (signature == previousSignature) {
      stallCount++
    } else {
      stallCount = 0
      previousSignature = signature
    }
    return stallCount < STALL_THRESHOLD
  }

  val isStalled: Boolean get() = stallCount >= STALL_THRESHOLD

  private fun signature(facts: Set<String>, toolCalls: List<Pair<String, String>>): String {
    val canonical = buildString {
      append("{\"facts\":[")
      append(facts.sorted().joinToString(",") { "\"${escape(it)}\"" })
      // множество (не последовательность): повтор того же вызова не меняет сигнатуру
      append("],\"tools\":[")
      append(toolCalls.map { (name, args) -> "${escape(name)}:${sha256(args).take(16)}" }.distinct().sorted()
        .joinToString(",") { "\"$it\"" })
      append("]}")
    }
    return sha256(canonical)
  }

  private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

  private fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
      .joinToString("") { "%02x".format(it) }

  companion object {
    const val STALL_THRESHOLD = 2
  }
}

/**
 * Лимит циклов ReAct (§8.4): базовый [baseLimit] (react.baseLimit, 8); при
 * исчерпании — продление +[extendBy] ТОЛЬКО если есть прогресс; жёсткий кап
 * [maxLimit] (react.maxLimit, 16). На остановке возвращает причину для
 * NEEDS_REVIEW/CYCLE_LIMIT.
 */
class CycleLimiter(
  private val baseLimit: Int = 8,
  private val extendBy: Int = 4,
  private val maxLimit: Int = 16,
) {
  private var effectiveLimit = baseLimit

  fun onIterationCompleted(iteration: Int, hasProgress: Boolean): CycleDecision {
    if (iteration >= maxLimit) {
      return CycleDecision(stop = true, extended = false, reason = "max limit $maxLimit iterations reached")
    }
    if (iteration >= effectiveLimit) {
      if (!hasProgress) {
        return CycleDecision(stop = true, extended = false, reason = "no progress after $iteration iterations (base limit $baseLimit)")
      }
      effectiveLimit = minOf(effectiveLimit + extendBy, maxLimit)
      return CycleDecision(stop = false, extended = true, reason = "extended to $effectiveLimit due to progress")
    }
    return CycleDecision(stop = false, extended = false, reason = null)
  }

  data class CycleDecision(
    val stop: Boolean,
    val extended: Boolean,
    val reason: String?,
  )
}
