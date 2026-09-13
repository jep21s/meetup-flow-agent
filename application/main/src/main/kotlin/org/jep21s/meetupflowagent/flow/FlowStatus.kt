package org.jep21s.meetupflowagent.flow

/** Статусы флоу (колонка flows.status, §6). Переходы WAITING-статусов — этап project. */
enum class FlowStatus {
  GUARDRAILS_PASSED,
  PROCESSING,
  WAITING_HUMAN,
  WAITING_TOOL_APPROVAL,
  WAITING_RETRY,
  COMPLETED,
  REJECTED,
  DUPLICATE,
  EXPIRED,
  FAILED_PERMANENT,
}

/** Недопустимый переход стейт-машины флоу. */
class IllegalFlowTransitionException(from: FlowStatus, to: FlowStatus) :
  RuntimeException("illegal flow transition: $from -> $to")

/**
 * Матрица переходов стейт-машины (этап 5, PLAN_5 §1.2):
 *
 * | из | в | условие |
 * |---|---|---|
 * | — | PROCESSING | флоу создан (guardrails-ветка REJECTED — этап 6) |
 * | PROCESSING | PROCESSING | итерация цикла (tool_call → observation) |
 * | PROCESSING | COMPLETED | финал распознан: APPROVED (событие создано) или NEEDS_REVIEW |
 * | PROCESSING | REJECTED | вердикт REJECTED (платное / не СПб / online-only) |
 * | PROCESSING | DUPLICATE | дубль ≥ 0.92 (связь в duplicates) |
 *
 * Остальные пары — недопустимы на этом этапе (WAITING-статусы и резюм — project).
 */
object FlowTransitions {

  private val allowed: Set<Pair<FlowStatus, FlowStatus>> = setOf(
    // guardrails появится на этапе 6: GUARDRAILS_PASSED -> PROCESSING / REJECTED
    FlowStatus.GUARDRAILS_PASSED to FlowStatus.PROCESSING,
    FlowStatus.PROCESSING to FlowStatus.PROCESSING,
    FlowStatus.PROCESSING to FlowStatus.COMPLETED,
    FlowStatus.PROCESSING to FlowStatus.REJECTED,
    FlowStatus.PROCESSING to FlowStatus.DUPLICATE,
  )

  fun checkTransition(from: FlowStatus, to: FlowStatus) {
    if (from != to && (from to to) !in allowed) {
      throw IllegalFlowTransitionException(from, to)
    }
  }

  fun isAllowed(from: FlowStatus, to: FlowStatus): Boolean =
    from == to || (from to to) in allowed
}
