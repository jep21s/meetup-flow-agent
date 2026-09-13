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

/** Тонкий OpenAI-compatible клиент chat/completions. */
interface LlmClient {
  suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse
}
