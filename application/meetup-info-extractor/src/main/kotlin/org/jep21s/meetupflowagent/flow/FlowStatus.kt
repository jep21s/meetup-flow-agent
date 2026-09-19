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
 * С этапа project добавлены переходы resilience/HITL: WAITING_RETRY-цикл
 * (и → FAILED_PERMANENT), WAITING_HUMAN (и → EXPIRED/финалы), WAITING_TOOL_APPROVAL.
 */
object FlowTransitions {

  private val allowed: Set<Pair<FlowStatus, FlowStatus>> = setOf(
    // guardrails появится на этапе 6: GUARDRAILS_PASSED -> PROCESSING / REJECTED
    FlowStatus.GUARDRAILS_PASSED to FlowStatus.PROCESSING,
    FlowStatus.PROCESSING to FlowStatus.PROCESSING,
    FlowStatus.PROCESSING to FlowStatus.COMPLETED,
    FlowStatus.PROCESSING to FlowStatus.REJECTED,
    FlowStatus.PROCESSING to FlowStatus.DUPLICATE,
    // resilience + HITL (project): retry-цикл и ожидание человека
    FlowStatus.PROCESSING to FlowStatus.WAITING_RETRY,
    FlowStatus.WAITING_RETRY to FlowStatus.PROCESSING,
    FlowStatus.WAITING_RETRY to FlowStatus.FAILED_PERMANENT,
    FlowStatus.PROCESSING to FlowStatus.WAITING_HUMAN,
    FlowStatus.WAITING_HUMAN to FlowStatus.PROCESSING,
    FlowStatus.WAITING_HUMAN to FlowStatus.COMPLETED,
    FlowStatus.WAITING_HUMAN to FlowStatus.REJECTED,
    FlowStatus.WAITING_HUMAN to FlowStatus.EXPIRED,
    FlowStatus.PROCESSING to FlowStatus.WAITING_TOOL_APPROVAL,
    FlowStatus.WAITING_TOOL_APPROVAL to FlowStatus.PROCESSING,
  )

  fun checkTransition(from: FlowStatus, to: FlowStatus) {
    if (from != to && (from to to) !in allowed) {
      throw IllegalFlowTransitionException(from, to)
    }
  }

  fun isAllowed(from: FlowStatus, to: FlowStatus): Boolean =
    from == to || (from to to) in allowed
}
