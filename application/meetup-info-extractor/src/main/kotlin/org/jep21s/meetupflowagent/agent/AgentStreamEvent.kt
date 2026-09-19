package org.jep21s.meetupflowagent.agent

import org.jep21s.meetupflowagent.flow.FlowResult

/**
 * События стриминга агентского флоу. Роут маппит их в SSE-кадры
 * (reasoning_delta/content_delta/tool_call/tool_result/final/error).
 *
 * ДЗ5: [Final] несёт [FlowResult] — итог стейт-машины (статус, вердикт
 * пост-валидации, eventId/duplicate-артефакты).
 */
sealed interface AgentStreamEvent {
  data class ReasoningDelta(val text: String) : AgentStreamEvent

  data class ContentDelta(val text: String) : AgentStreamEvent

  data class ToolCall(val name: String, val arguments: String) : AgentStreamEvent

  data class ToolResult(val ok: Boolean, val text: String, val errorCode: String?) : AgentStreamEvent

  data class Final(val result: FlowResult) : AgentStreamEvent

  data class Failure(val message: String) : AgentStreamEvent
}
