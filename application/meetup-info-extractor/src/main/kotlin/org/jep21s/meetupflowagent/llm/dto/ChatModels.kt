package org.jep21s.meetupflowagent.llm.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** Роль сообщения чата (OpenAI-compatible). */
enum class ChatRole {
  @JsonProperty("system")
  SYSTEM,

  @JsonProperty("user")
  USER,

  @JsonProperty("assistant")
  ASSISTANT,

  @JsonProperty("tool")
  TOOL,
}

/**
 * Сообщение агентской сессии. [content] отсутствует у assistant-сообщений, состоящих
 * только из tool_calls; [toolCalls] — только у assistant; [toolCallId] — только у TOOL.
 */
data class ChatMessage(
  val role: ChatRole,
  val content: String? = null,
  @JsonProperty("tool_calls")
  val toolCalls: List<ToolCall>? = null,
  @JsonProperty("tool_call_id")
  val toolCallId: String? = null,
  /** CoT модели (GLM reasoning_content). Только читается из ответов провайдера — в исходящие запросы не сериализуется. */
  @JsonProperty("reasoning_content", access = JsonProperty.Access.WRITE_ONLY)
  val reasoningContent: String? = null,
) {
  companion object {
    fun system(text: String) = ChatMessage(role = ChatRole.SYSTEM, content = text)
    fun user(text: String) = ChatMessage(role = ChatRole.USER, content = text)
    fun assistant(content: String?, reasoning: String? = null) =
      ChatMessage(role = ChatRole.ASSISTANT, content = content, reasoningContent = reasoning)
    fun assistantToolCalls(calls: List<ToolCall>) =
      ChatMessage(role = ChatRole.ASSISTANT, content = null, toolCalls = calls)

    fun tool(toolCallId: String, text: String) =
      ChatMessage(role = ChatRole.TOOL, content = text, toolCallId = toolCallId)
  }
}

/** Вызов функции, запрошенный моделью. [function.arguments] — строка с JSON-аргументами. */
data class ToolCall(
  val id: String,
  val type: String = "function",
  val function: FunctionCall,
)

data class FunctionCall(
  val name: String,
  val arguments: String,
)

/** Описание инструмента для function calling. */
data class ToolSpec(
  val type: String = "function",
  val function: FunctionSpec,
)

data class FunctionSpec(
  val name: String,
  val description: String,
  /** JSON Schema параметров (object). */
  val parameters: ObjectNode,
)

/** Запрос chat/completions (не-стриминговый вариант — этап 2). */
data class ChatCompletionRequest(
  val model: String,
  val messages: List<ChatMessage>,
  val tools: List<ToolSpec>? = null,
  val stream: Boolean = false,
  @JsonProperty("tool_choice")
  val toolChoice: String? = null,
)

data class ChatCompletionResponse(
  val choices: List<Choice> = emptyList(),
  val usage: Usage? = null,
) {
  /** Первое сообщение ответа; пустой choices считаем невалидным ответом провайдера. */
  fun firstMessage(): ChatMessage =
    choices.firstOrNull()?.message
      ?: throw IllegalStateException("LLM response has no choices")
}

data class Choice(
  val index: Int = 0,
  val message: ChatMessage,
  @JsonProperty("finish_reason")
  val finishReason: String? = null,
)

data class Usage(
  @JsonProperty("prompt_tokens")
  val promptTokens: Int = 0,
  @JsonProperty("completion_tokens")
  val completionTokens: Int = 0,
  @JsonProperty("total_tokens")
  val totalTokens: Int = 0,
)

/** Аргументы tool-вызова как JsonNode (парсинг строки [FunctionCall.arguments] наружи). */
fun FunctionCall.argumentsNode(mapper: com.fasterxml.jackson.databind.ObjectMapper): JsonNode =
  mapper.readTree(arguments)
