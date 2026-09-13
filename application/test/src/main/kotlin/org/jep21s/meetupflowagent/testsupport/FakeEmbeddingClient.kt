package org.jep21s.meetupflowagent.testsupport

import org.jep21s.meetupflowagent.llm.EmbeddingClient
import kotlin.math.sqrt

/**
 * Детерминированный фейк эмбеддингов (768d) для тестов: ноль внешних вызовов.
 *
 * Правило «похожести»: каждому ключевому слову соответствует свой базовый
 * псевдослучайный вектор (hash-затравка); вектор текста = нормированная сумма
 * векторов всех встречающихся ключевых слов. Одинаковые наборы ключевых слов →
 * cosine similarity ≈ 1; разные — низкая/нулевая близость. Текст без ключевых
 * слов получает уникальный hash-вектор (практически ортогонален остальным).
 */
class FakeEmbeddingClient(
  private val keywords: Set<String> = emptySet(),
  private val dim: Int = 768,
) : EmbeddingClient {

  override suspend fun embed(text: String): FloatArray {
    val lower = text.lowercase()
    val matched = keywords.filter { lower.contains(it) }
    return if (matched.isEmpty()) {
      randomUnitVector(text.hashCode())
    } else {
      val sum = FloatArray(dim)
      matched.forEach { kw -> addInPlace(sum, randomUnitVector(kw.hashCode())) }
      normalize(sum)
      sum
    }
  }

  private fun addInPlace(target: FloatArray, other: FloatArray) {
    for (i in target.indices) target[i] += other[i]
  }

  private fun normalize(v: FloatArray) {
    val norm = sqrt(v.map { it.toDouble() * it.toDouble() }.sum()).toFloat()
    if (norm > 0f) for (i in v.indices) v[i] /= norm
  }

  /** Детерминированный LCG-вектор с нормой 1. */
  private fun randomUnitVector(seed: Int): FloatArray {
    var state = seed.toLong() and 0xFFFFFFFFL
    val v = FloatArray(dim)
    for (i in v.indices) {
      state = state * 6364136223846793005L + 1442695040888963407L
      v[i] = ((state ushr 33) % 2001 - 1000).toFloat() / 1000f
    }
    normalize(v)
    return v
  }
}
