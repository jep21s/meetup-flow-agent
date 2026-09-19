package org.jep21s.meetupflowagent.llm

import org.jep21s.meetupflowagent.llm.dto.ChatCompletionRequest
import org.jep21s.meetupflowagent.llm.dto.ChatCompletionResponse

/** Ошибка вызова LLM с классификацией для retry-механики (resilience-слой, этап 6). */
class LlmException(
  val category: Category,
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause) {

  enum class Category {
    /** HTTP 429/5xx, таймауты, сетевые сбои — повтор имеет смысл. */
    RETRYABLE,

    /** Остальные 4xx (авторизация, плохой запрос) — повтор бессмыслен. */
    FATAL,

    /** Ответ провайдера не распарсился как JSON/chat.completions. */
    PARSE,
  }
}

/**
 * Дельта стримингового ответа (chat/completions, `stream=true`). GLM шлёт
 * `reasoning_content` вперемешку с `content`; аргументы tool_calls приходят
 * кусками и наращиваются по `index`.
 */
sealed interface StreamDelta {
  data class ReasoningDelta(val text: String) : StreamDelta

  data class ContentDelta(val text: String) : StreamDelta

  data class ToolCallDelta(
    val index: Int,
    val id: String?,
    val functionName: String?,
    val argumentsChunk: String,
  ) : StreamDelta

  data class Finish(val reason: String) : StreamDelta
}

/** Тонкий OpenAI-compatible клиент chat/completions. */
interface LlmClient {

  suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse

  /**
   * Стриминговый вызов: дельты отдаются в [onDelta] по мере поступления, параллельно
   * накапливаются; по завершении возвращается собранный [ChatCompletionResponse]
   * (эквивалент результата [complete]).
   */
  suspend fun streamChat(
    request: ChatCompletionRequest,
    onDelta: suspend (StreamDelta) -> Unit,
  ): ChatCompletionResponse = runStreamingOverComplete(request, onDelta)
}

/** Фолбэк-реализация стриминга поверх не-стримингового [LlmClient.complete]. */
private suspend fun LlmClient.runStreamingOverComplete(
  request: ChatCompletionRequest,
  onDelta: suspend (StreamDelta) -> Unit,
): ChatCompletionResponse {
  val response = complete(request)
  val message = response.firstMessage()
  message.reasoningContent?.let { onDelta(StreamDelta.ReasoningDelta(it)) }
  message.content?.let { onDelta(StreamDelta.ContentDelta(it)) }
  message.toolCalls.orEmpty().forEachIndexed { index, call ->
    onDelta(
      StreamDelta.ToolCallDelta(
        index = index,
        id = call.id,
        functionName = call.function.name,
        argumentsChunk = call.function.arguments,
      ),
    )
  }
  response.choices.firstOrNull()?.finishReason?.let { onDelta(StreamDelta.Finish(it)) }
  return response
}
