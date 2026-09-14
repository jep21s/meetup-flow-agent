package org.jep21s.meetupflowagent.flow

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Матрица переходов стейт-машины (PLAN_5 §1.2): допустимые проходят, прочие — исключение. */
class FlowTransitionTest {

  @Test
  fun `allowed transitions pass`() {
    val allowed = listOf(
      FlowStatus.PROCESSING to FlowStatus.PROCESSING,
      FlowStatus.PROCESSING to FlowStatus.COMPLETED,
      FlowStatus.PROCESSING to FlowStatus.REJECTED,
      FlowStatus.PROCESSING to FlowStatus.DUPLICATE,
      FlowStatus.GUARDRAILS_PASSED to FlowStatus.PROCESSING,
    )
    allowed.forEach { (from, to) ->
      FlowTransitions.checkTransition(from, to)
      assertThat(FlowTransitions.isAllowed(from, to)).isTrue()
    }
  }

  @Test
  fun `terminal statuses are final`() {
    val terminals = listOf(FlowStatus.COMPLETED, FlowStatus.REJECTED, FlowStatus.DUPLICATE)
    val anyStatus = FlowStatus.entries
    terminals.forEach { terminal ->
      anyStatus.filter { it != terminal }.forEach { other ->
        val error = runCatching { FlowTransitions.checkTransition(terminal, other) }.exceptionOrNull()
        assertThat(error)
          .describedAs("transition $terminal to $other must be rejected")
          .isInstanceOf(IllegalFlowTransitionException::class.java)
        assertThat(FlowTransitions.isAllowed(terminal, other)).isFalse()
      }
    }
  }

  @Test
  fun `project-stage resilience transitions are allowed`() {
    val resilience = listOf(
      FlowStatus.PROCESSING to FlowStatus.WAITING_RETRY,
      FlowStatus.WAITING_RETRY to FlowStatus.PROCESSING,
      FlowStatus.WAITING_RETRY to FlowStatus.FAILED_PERMANENT,
      FlowStatus.PROCESSING to FlowStatus.WAITING_HUMAN,
      FlowStatus.WAITING_HUMAN to FlowStatus.COMPLETED,
      FlowStatus.WAITING_HUMAN to FlowStatus.EXPIRED,
    )
    resilience.forEach { (from, to) ->
      FlowTransitions.checkTransition(from, to)
      assertThat(FlowTransitions.isAllowed(from, to)).isTrue()
    }
  }
}
