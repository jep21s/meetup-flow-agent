package org.jep21s.meetupflowagent.resilience

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger { }

/** Состояние прерывателя (§13). */
enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

/**
 * Собственный CircuitBreaker (§13): скользящее окно [windowSize] вызовов,
 * ≥ [failureRateThreshold] ошибок → OPEN на [openDuration]; затем HALF_OPEN —
 * один пробный вызов: успех → CLOSED, неудача → OPEN. Gauge
 * meetup_circuit_state{dependency} обновляет вызывающая сторона.
 */
class CircuitBreaker(
  val name: String,
  private val windowSize: Int = 20,
  private val failureRateThreshold: Double = 0.5,
  private val openDurationMs: Long = 60_000,
) {
  private val state = AtomicReference(CircuitState.CLOSED)
  private val failures = AtomicInteger(0)
  private val calls = AtomicInteger(0)
  private var openedAt: Instant? = null

  fun currentState(): CircuitState {
    refreshHalfOpen()
    return state.get()
  }

  /** Разрешён ли вызов (OPEN → false; HALF_OPEN пропускает один пробный). */
  fun allowCall(): Boolean = when (currentState()) {
    CircuitState.CLOSED, CircuitState.HALF_OPEN -> true
    CircuitState.OPEN -> false
  }

  fun recordSuccess() {
    calls.incrementAndGet()
    if (state.compareAndSet(CircuitState.HALF_OPEN, CircuitState.CLOSED)) {
      resetWindow()
      logger.info { "circuit[$name] half-open → closed" }
    }
  }

  fun recordFailure() {
    val total = calls.incrementAndGet()
    val failed = failures.incrementAndGet()
    if (state.get() == CircuitState.HALF_OPEN) {
      trip("probe call failed in half-open")
      return
    }
    if (total >= windowSize && failed.toDouble() / total >= failureRateThreshold) {
      trip("failure rate ${failed.toDouble() / total} over window $total")
    }
  }

  @Synchronized
  private fun trip(reason: String) {
    if (state.get() == CircuitState.OPEN) return
    state.set(CircuitState.OPEN)
    openedAt = Instant.now()
    resetWindow()
    logger.warn { "circuit[$name] open: $reason (на ${openDurationMs}ms)" }
  }

  @Synchronized
  private fun refreshHalfOpen() {
    if (state.get() == CircuitState.OPEN &&
      openedAt?.plusMillis(openDurationMs)?.isBefore(Instant.now()) == true
    ) {
      state.set(CircuitState.HALF_OPEN)
      resetWindow()
      logger.info { "circuit[$name] open → half-open (probe)" }
    }
  }

  private fun resetWindow() {
    calls.set(0)
    failures.set(0)
  }
}
