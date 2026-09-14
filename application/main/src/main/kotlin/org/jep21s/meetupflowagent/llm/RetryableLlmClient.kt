package org.jep21s.meetupflowagent.llm

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.jep21s.meetupflowagent.llm.dto.ChatCompletionRequest
import org.jep21s.meetupflowagent.llm.dto.ChatCompletionResponse
import org.jep21s.meetupflowagent.observability.Metrics
import java.time.Duration
import kotlin.random.Random

private val logger = KotlinLogging.logger { }

/**
 * Декоратор LlmClient с базовым in-request retry (§10, PLAN_6 §1.3): повторяет
 * только RETRYABLE-ошибки (429/5xx/сеть), максимум [maxRetries] дополнительных
 * попыток, пауза `backoffBaseMs * 2^попытка + jitter`. FATAL/PARSE не ретраятся.
 * Здесь же централизованы метрики вызовов: итоговый outcome, латентность, токены
 * и стоимость (прайс §10).
 *
 * В Koin собирается фабрикой в MainBeanConfig поверх конкретного
 * [KtorOpenAiLlmClient] и биндится как [LlmClient] — агент и guardrails ходят
 * через retry (делегат объявлен интерфейсом ради тестов).
 */
class RetryableLlmClient(
  private val delegate: LlmClient,
  private val metrics: Metrics,
  private val maxRetries: Int = 2,
  private val backoffBaseMs: Long = 500,
) : LlmClient {

  override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse =
    withRetry(request) { delegate.complete(request) }

  override suspend fun streamChat(
    request: ChatCompletionRequest,
    onDelta: suspend (StreamDelta) -> Unit,
  ): ChatCompletionResponse =
    withRetry(request) { delegate.streamChat(request, onDelta) }

  private suspend fun <T> withRetry(
    request: ChatCompletionRequest,
    call: suspend () -> T,
  ): T {
    var attempt = 0
    while (true) {
      val startedAt = System.nanoTime()
      try {
        val result = call()
        val latency = Duration.ofNanos(System.nanoTime() - startedAt)
        metrics.llmCall(request.model, "ok")
        metrics.llmLatency(request.model, latency)
        if (result is ChatCompletionResponse) {
          val usage = result.usage
          if (usage != null) {
            metrics.llmTokens(request.model, usage.promptTokens.toLong(), usage.completionTokens.toLong())
            metrics.llmCost(request.model, usage.promptTokens.toLong(), usage.completionTokens.toLong())
          }
        }
        return result
      } catch (e: LlmException) {
        val latency = Duration.ofNanos(System.nanoTime() - startedAt)
        metrics.llmLatency(request.model, latency)
        if (e.category != LlmException.Category.RETRYABLE || attempt >= maxRetries) {
          metrics.llmCall(request.model, if (e.category == LlmException.Category.RETRYABLE) "retryable_error" else "fatal_error")
          throw e
        }
        attempt++
        val jitterBound = maxOf(1L, backoffBaseMs / 2)
        val backoff = backoffBaseMs * (1L shl (attempt - 1)) + Random.nextLong(0, jitterBound)
        logger.warn {
          "llm call failed (attempt $attempt/${maxRetries + 1}, category=${e.category}), " +
            "retrying in ${backoff}ms: ${e.message?.take(150)}"
        }
        metrics.llmCall(request.model, "retryable_error")
        delay(backoff)
      }
    }
  }
}
