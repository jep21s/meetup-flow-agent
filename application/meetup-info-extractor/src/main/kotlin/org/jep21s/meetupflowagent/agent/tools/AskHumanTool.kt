package org.jep21s.meetupflowagent.agent.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.core.annotation.Singleton

/** Тул запросил ввод человека: флоу переводится в WAITING_HUMAN (§8.3). */
class HumanInputRequiredException(
  val question: ObjectNode,
  /** Прерванный tool_call (id/имя/аргументы) — резюм добавит ответ как observation. */
  val pendingToolCall: PendingToolCall? = null,
) : RuntimeException(question.path("question").asText("human input required")) {
  data class PendingToolCall(val id: String, val name: String, val arguments: String)
}

/**
 * Тул `ask_human(question, contextSummary, options[])`: HITL. execute НЕ
 * возвращает observation — кидает [HumanInputRequiredException]; FlowEngine
 * снапшотит состояние, пишет human_requests, переводит флоу в WAITING_HUMAN и
 * уведомляет прокси. Ответ (POST /api/flows/{id}/responses) добавляется как
 * observation к прерванному вызову, цикл продолжается. Один открытый вопрос на
 * флоу; ожидание ≤48ч (HumanTimeoutPoller: REMINDER → EXPIRED).
 */
@Singleton
class AskHumanTool : AgentTool {

  override val name = "ask_human"

  override val description =
    "Спросить человека, когда данные невосстановимы другими инструментами " +
      "(нет регистрации, сомнение в дубле, не хватает критичных полей). " +
      "Один открытый вопрос на флоу; ответ ожидается до 48 часов."

  override val parametersSchema: ObjectNode = JsonNodeFactory.instance.objectNode().apply {
    put("type", "object")
    putObject("properties").apply {
      putObject("question").apply {
        put("type", "string")
        put("description", "Короткий конкретный вопрос человеку")
      }
      putObject("contextSummary").apply {
        put("type", "string")
        put("description", "Что уже известно и чего не хватает")
      }
      putObject("options").apply {
        put("type", "array")
        putObject("items").apply { put("type", "string") }
        put("description", "Варианты ответа, если применимы")
      }
    }
    putArray("required").add("question")
  }

  override suspend fun execute(args: JsonNode): ToolResult {
    // unreachable: FlowEngine перехватывает вызов ask_human до исполнения
    throw HumanInputRequiredException(
      jacksonMapper.createObjectNode().apply {
        put("question", args.path("question").asText(""))
        put("contextSummary", args.path("contextSummary").asText(""))
        putArray("options").apply { args.path("options").forEach { add(it.asText()) } }
      },
    )
  }
}
