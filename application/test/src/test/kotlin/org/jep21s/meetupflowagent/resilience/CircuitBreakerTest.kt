package org.jep21s.meetupflowagent.resilience

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CircuitBreakerTest {

  @Test
  fun `stays closed below failure threshold`() {
    val breaker = CircuitBreaker("llm-test", windowSize = 10, failureRateThreshold = 0.5, openDurationMs = 1)
    repeat(4) { breaker.recordSuccess() }
    repeat(4) { breaker.recordFailure() }
    assertThat(breaker.currentState()).isEqualTo(CircuitState.CLOSED)
    assertThat(breaker.allowCall()).isTrue()
  }

  @Test
  fun `opens at failure threshold and blocks calls`() {
    val breaker = CircuitBreaker("llm-test", windowSize = 10, failureRateThreshold = 0.5, openDurationMs = 60_000)
    repeat(5) { breaker.recordSuccess() }
    repeat(5) { breaker.recordFailure() }
    assertThat(breaker.currentState()).isEqualTo(CircuitState.OPEN)
    assertThat(breaker.allowCall()).isFalse()
  }

  @Test
  fun `half-open after cool-down and closes on probe success`() {
    val breaker = CircuitBreaker("llm-test", windowSize = 2, failureRateThreshold = 0.5, openDurationMs = 10)
    breaker.recordFailure()
    breaker.recordFailure()
    assertThat(breaker.currentState()).isEqualTo(CircuitState.OPEN)
    Thread.sleep(30)
    assertThat(breaker.currentState()).isEqualTo(CircuitState.HALF_OPEN)
    assertThat(breaker.allowCall()).isTrue()
    breaker.recordSuccess()
    assertThat(breaker.currentState()).isEqualTo(CircuitState.CLOSED)
  }

  @Test
  fun `half-open reopens on probe failure`() {
    val breaker = CircuitBreaker("llm-test", windowSize = 2, failureRateThreshold = 0.5, openDurationMs = 10)
    breaker.recordFailure()
    breaker.recordFailure()
    Thread.sleep(30)
    assertThat(breaker.currentState()).isEqualTo(CircuitState.HALF_OPEN)
    breaker.recordFailure()
    assertThat(breaker.currentState()).isEqualTo(CircuitState.OPEN)
  }
}
