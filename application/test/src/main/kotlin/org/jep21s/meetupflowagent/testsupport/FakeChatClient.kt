package org.jep21s.meetupflowagent.testsupport

import org.jep21s.meetupflowagent.llm.LlmClient
import org.jep21s.meetupflowagent.llm.dto.ChatCompletionRequest
import org.jep21s.meetupflowagent.llm.dto.ChatCompletionResponse
import org.jep21s.meetupflowagent.llm.dto.ChatMessage
import org.jep21s.meetupflowagent.llm.dto.Choice
import org.jep21s.meetupflowagent.llm.dto.FunctionCall
import org.jep21s.meetupflowagent.llm.dto.ToolCall
import org.jep21s.meetupflowagent.llm.dto.Usage

/**
 * Скриптованный детерминированный LlmClient для тестов: возвращает заготовленные
 * ответы по очереди; фиксирует все запросы для ассертов.
 */
class FakeChatClient(private val script: ArrayDeque<ChatCompletionResponse>) : LlmClient {

  constructor(vararg scripted: ChatCompletionResponse) : this(ArrayDeque(scripted.toList()))

  val requests = mutableListOf<ChatCompletionRequest>()

  /** Добавляет ответ в конец скрипта (удобно в @BeforeEach-сценариях). */
  fun enqueue(response: ChatCompletionResponse) {
    script.addLast(response)
  }

  override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
    requests += request
    if (script.isEmpty()) {
      throw IllegalStateException(
        "FakeChatClient: script exhausted (already served ${requests.size} requests)",
      )
    }
    return script.removeFirst()
  }

  companion object {

    /** Ответ с чистым текстом (финал агентского цикла). */
    fun text(content: String): ChatCompletionResponse = ChatCompletionResponse(
      choices = listOf(
        Choice(message = ChatMessage.assistant(content), finishReason = "stop"),
      ),
      usage = Usage(promptTokens = 10, completionTokens = 5),
    )

    /** Ответ с одним tool_call. */
    fun toolCall(
      id: String = "call_1",
      name: String,
      argumentsJson: String,
    ): ChatCompletionResponse = ChatCompletionResponse(
      choices = listOf(
        Choice(
          message = ChatMessage.assistantToolCalls(
            listOf(ToolCall(id = id, function = FunctionCall(name, argumentsJson))),
          ),
          finishReason = "tool_calls",
        ),
      ),
      usage = Usage(promptTokens = 10, completionTokens = 5),
    )
  }
}
