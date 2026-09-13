package org.jep21s.meetupflowagent.flow

import org.jep21s.meetupflowagent.agent.ToolCallRecord
import org.jep21s.meetupflowagent.domain.VerdictStatus
import java.util.UUID

/** Итог флоу: статус стейт-машины + вердикт пост-валидации + артефакты. */
data class FlowResult(
  val flowId: UUID,
  val status: FlowStatus,
  val verdictStatus: VerdictStatus,
  val reasons: List<String>,
  val eventId: UUID? = null,
  val duplicateOf: UUID? = null,
  val similarity: Double? = null,
  val reply: String = "",
  val toolCalls: List<ToolCallRecord> = emptyList(),
  val iterations: Int = 0,
  val limitReached: Boolean = false,
)
