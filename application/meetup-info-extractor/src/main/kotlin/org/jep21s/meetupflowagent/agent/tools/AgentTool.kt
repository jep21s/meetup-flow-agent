package org.jep21s.meetupflowagent.agent.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jep21s.meetupflowagent.starter.config.ConfigLoader

/** Результат исполнения тула. Любой вариант возвращается агенту как observation. */
sealed interface ToolResult {
  data class Success(val text: String) : ToolResult

  /** [code] — машиночитаемый код ошибки (TIMEOUT, HTTP_4XX, SSRF_BLOCKED, …). */
  data class Error(val message: String, val code: String) : ToolResult
}

/** Инструмент агента, вызываемый через function calling. */
interface AgentTool {
  val name: String
  val description: String

  /** JSON Schema параметров (объект верхнего уровня). */
  val parametersSchema: ObjectNode

  suspend fun execute(args: JsonNode): ToolResult
}

/** Режим политики вызова тула (config: `tool.<name>.policy`). */
enum class ToolPolicyMode { ALLOW, ASK, BLOCK }

/**
 * Каркас политик тулов. В v1 все тулы `allow`; механика `ask` (WAITING_TOOL_APPROVAL)
 * запроектирована, но активируется на этапе project.
 */
object ToolPolicies {

  fun policyFor(toolName: String): ToolPolicyMode {
    val raw = ConfigLoader.getProperty("tool.$toolName.policy", "allow").trim().uppercase()
    return ToolPolicyMode.entries.firstOrNull { it.name == raw } ?: ToolPolicyMode.ALLOW
  }
}
