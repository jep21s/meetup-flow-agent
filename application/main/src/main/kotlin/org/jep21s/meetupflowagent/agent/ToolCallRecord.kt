package org.jep21s.meetupflowagent.agent

/** Выполненный вызов тула в рамках флоу. */
data class ToolCallRecord(
  val name: String,
  val args: String,
  val ok: Boolean,
  val errorCode: String? = null,
)
