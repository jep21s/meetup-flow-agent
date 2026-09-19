package org.jep21s.meetupflowagent.llm

/**
 * Ошибка вызова эмбеддинг-провайдера с классификацией (аналог [LlmException]):
 * retry-механика уровня флоу появится на этапе 6, тул возвращает ошибку агенту
 * как observation (SOP §9: не ложится на агента).
 */
class EmbeddingException(
  val category: LlmException.Category,
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Клиент эмбеддингов: одна строка → вектор фиксированной размерности (768). */
interface EmbeddingClient {
  suspend fun embed(text: String): FloatArray
}
