package org.jep21s.meetupflowagent.llm

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.jep21s.meetupflowagent.llm.dto.ChatCompletionRequest
import org.jep21s.meetupflowagent.llm.dto.ChatCompletionResponse
import org.jep21s.meetupflowagent.llm.dto.ChatMessage
import org.jep21s.meetupflowagent.llm.dto.Usage
import org.jep21s.meetupflowagent.observability.Metrics
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger

/** In-request retry декоратора: только RETRYABLE, максимум 2 повтора, экспонента. */
class LlmRetryTest {

  private val metrics = Metrics.inMemory()
  private val request = ChatCompletionRequest(model = "glm-5.3-flash", messages = listOf(ChatMessage.user("тест")))

  private fun okResponse() = ChatCompletionResponse(
    choices = emptyList(),
    usage = Usage(promptTokens = 100, completionTokens = 50, totalTokens = 150),
  )

  @Test
  fun `retryable errors retried then succeed`() {
    var calls = 0
    val flaky = object : LlmClient {
      override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        calls++
        if (calls <= 2) throw LlmException(LlmException.Category.RETRYABLE, "HTTP 429")
        return okResponse()
      }
    }

    val result = runBlocking { client(flaky).complete(request) }

    assertThat(calls).isEqualTo(3)
    assertThat(result.usage?.totalTokens).isEqualTo(150)
  }

  @Test
  fun `exhausted retries throw RETRYABLE`() {
    var calls = 0
    val alwaysFailing = object : LlmClient {
      override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        calls++
        throw LlmException(LlmException.Category.RETRYABLE, "HTTP 503")
      }
    }

    val error = assertThrows<LlmException> { runBlocking { client(alwaysFailing).complete(request) } }
    assertThat(error.category).isEqualTo(LlmException.Category.RETRYABLE)
    assertThat(calls).isEqualTo(3) // 1 + 2 повтора
  }

  @Test
  fun `fatal error is not retried`() {
    var calls = 0
    val fatal = object : LlmClient {
      override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        calls++
        throw LlmException(LlmException.Category.FATAL, "HTTP 401")
      }
    }

    assertThrows<LlmException> { runBlocking { client(fatal).complete(request) } }
    assertThat(calls).isEqualTo(1)
  }

  @Test
  fun `successful call reports metrics`() {
    val metrics = Metrics.inMemory()
    var calls = 0
    val once = object : LlmClient {
      override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        calls++
        return okResponse()
      }
    }

    runBlocking { RetryableLlmClient(once, metrics, backoffBaseMs = 1).complete(request) }

    assertThat(calls).isEqualTo(1)
    // метрики вызова зарегистрированы: outcome=ok, стоимость по прайсу flash > 0
    assertThat(metrics.registryForTests().find("meetup_llm_calls_total")
      .tag("model", "glm-5.3-flash").tag("outcome", "ok").counter()!!.count()).isEqualTo(1.0)
    assertThat(metrics.registryForTests().find("meetup_llm_cost_usd_total")
      .tag("model", "glm-5.3-flash").counter()!!.count()).isPositive()
  }

  private fun client(delegate: LlmClient): RetryableLlmClient =
    RetryableLlmClient(delegate, metrics, backoffBaseMs = 1)
}
