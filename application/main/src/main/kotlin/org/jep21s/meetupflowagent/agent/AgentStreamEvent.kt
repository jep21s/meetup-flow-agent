package org.jep21s.meetupflowagent.agent

import org.jep21s.meetupflowagent.db.PersistOutcome

/**
 * События стриминга агентского прогона (ДЗ3). Роут маппит их в SSE-кадры
 * (reasoning_delta/content_delta/tool_call/tool_result/final/persisted/error).
 *
 * ДЗ4: [Persisted] — результат записи финального ответа в память агента
 * (событие сохранено / распознан дубль / пропущено) после [Final].
 */
sealed interface AgentStreamEvent {
  data class ReasoningDelta(val text: String) : AgentStreamEvent

  data class ContentDelta(val text: String) : AgentStreamEvent

  data class ToolCall(val name: String, val arguments: String) : AgentStreamEvent

  data class ToolResult(val ok: Boolean, val text: String, val errorCode: String?) : AgentStreamEvent

  data class Final(val reply: AgentReply) : AgentStreamEvent

  data class Persisted(val outcome: PersistOutcome) : AgentStreamEvent

  data class Failure(val message: String) : AgentStreamEvent
}
