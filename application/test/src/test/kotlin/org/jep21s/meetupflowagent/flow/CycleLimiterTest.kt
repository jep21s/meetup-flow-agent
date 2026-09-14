package org.jep21s.meetupflowagent.flow

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProgressDetectorTest {

  @Test
  fun `new facts count as progress`() {
    val detector = ProgressDetector()
    assertThat(detector.update(setOf("title=X"), emptyList())).isTrue()
    assertThat(detector.update(setOf("title=X", "startsAt=2026-10-02"), emptyList())).isTrue()
    assertThat(detector.isStalled).isFalse()
  }

  @Test
  fun `same tool with same args is stagnation`() {
    val detector = ProgressDetector()
    val call = "search_duplicate" to """{"query":"митап"}"""
    detector.update(setOf("title=X"), listOf(call))
    val second = detector.update(setOf("title=X"), listOf(call, call))
    assertThat(second).isTrue() // первая стагнация ещё не порог
    val third = detector.update(setOf("title=X"), listOf(call, call, call))
    assertThat(third).isFalse() // 2 цикла подряд без изменений → прогресса нет
    assertThat(detector.isStalled).isTrue()
  }

  @Test
  fun `changed tool args reset stall counter`() {
    val detector = ProgressDetector()
    detector.update(setOf("title=X"), listOf("search_duplicate" to """{"query":"a"}"""))
    detector.update(setOf("title=X"), listOf("search_duplicate" to """{"query":"a"}"""))
    // уточнённые аргументы — снова прогресс
    val afterChange = detector.update(setOf("title=X"), listOf("search_duplicate" to """{"query":"b"}"""))
    assertThat(afterChange).isTrue()
    assertThat(detector.isStalled).isFalse()
  }
}

class CycleLimiterTest {

  @Test
  fun `extends by extendBy when progress continues`() {
    val limiter = CycleLimiter(baseLimit = 8, extendBy = 4, maxLimit = 16)
    val atBase = limiter.onIterationCompleted(8, hasProgress = true)
    assertThat(atBase.stop).isFalse()
    assertThat(atBase.extended).isTrue()
    // продление на 4: итерации 9–12 идут
    assertThat(limiter.onIterationCompleted(9, true).stop).isFalse()
    assertThat(limiter.onIterationCompleted(12, true).extended).isTrue()
  }

  @Test
  fun `stops at base limit without progress`() {
    val limiter = CycleLimiter(baseLimit = 8, extendBy = 4, maxLimit = 16)
    val decision = limiter.onIterationCompleted(8, hasProgress = false)
    assertThat(decision.stop).isTrue()
    assertThat(decision.reason).contains("no progress")
  }

  @Test
  fun `hard cap at maxLimit even with progress`() {
    val limiter = CycleLimiter(baseLimit = 8, extendBy = 4, maxLimit = 16)
    limiter.onIterationCompleted(8, true) // → 12
    limiter.onIterationCompleted(12, true) // → 16
    val atMax = limiter.onIterationCompleted(16, true)
    assertThat(atMax.stop).isTrue()
    assertThat(atMax.reason).contains("max limit")
  }

  @Test
  fun `below base limit never stops`() {
    val limiter = CycleLimiter(baseLimit = 8, extendBy = 4, maxLimit = 16)
    (1..7).forEach { iteration ->
      val decision = limiter.onIterationCompleted(iteration, hasProgress = false)
      assertThat(decision.stop).describedAs("iteration $iteration").isFalse()
    }
  }
}
