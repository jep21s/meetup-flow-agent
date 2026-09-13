package org.jep21s.meetupflowagent.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.jackson.jackson
import org.jep21s.meetupflowagent.llm.dto.ChatCompletionRequest
import org.jep21s.meetupflowagent.llm.dto.ChatCompletionResponse
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.jep21s.meetupflowagent.starter.jackson.JacksonConfig
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.core.annotation.Singleton

/**
 * Тонкий OpenAI-compatible клиент: POST {llm.baseUrl}/chat/completions, Bearer llm.apiKey.
 *
 * Ошибки классифицируются в [LlmException.Category] — сам клиент НЕ ретраит
 * (короткий in-request retry появится на этапе 6, флоу-уровень — в resilience-слое).
 * Конструктор с HttpClient — для тестов (ktor-client-mock).
 */
@Singleton
class KtorOpenAiLlmClient(
  private val httpClient: HttpClient = defaultHttpClient(),
) : LlmClient {

  override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
    val baseUrl = ConfigLoader.getRequiredProperty(
      "llm.baseUrl",
      "llm.baseUrl is not configured (LLM_BASE_URL)",
    ).trimEnd('/')
    val apiKey = ConfigLoader.getRequiredProperty(
      "llm.apiKey",
      "llm.apiKey is not configured (LLM_API_KEY)",
    )

    val response = try {
      httpClient.post("$baseUrl/chat/completions") {
        header(HttpHeaders.Authorization, "Bearer $apiKey")
        contentType(ContentType.Application.Json)
        setBody(request)
      }
    } catch (e: Exception) {
      throw toNetworkException(e)
    }

    if (response.status.value == 429 || response.status.value >= 500) {
      throw LlmException(
        LlmException.Category.RETRYABLE,
        "LLM provider returned HTTP ${response.status.value}",
      )
    }
    if (!response.status.isSuccess()) {
      throw LlmException(
        LlmException.Category.FATAL,
        "LLM provider returned HTTP ${response.status.value}: ${response.bodyAsText().take(500)}",
      )
    }

    val body = response.bodyAsText()
    return try {
      jacksonMapper.readValue(body, ChatCompletionResponse::class.java)
    } catch (e: Exception) {
      throw LlmException(
        LlmException.Category.PARSE,
        "LLM response is not valid JSON (first 300 chars: ${body.take(300)})",
        e,
      )
    }
  }

  private fun toNetworkException(e: Exception): LlmException =
    // таймауты/коннект-сбои/DNS — все сетевые ошибки считаем транзиентными
    LlmException(
      LlmException.Category.RETRYABLE,
      "LLM network call failed: ${e::class.simpleName}: ${e.message}",
      e,
    )

  companion object {
    fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
      install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 120_000
      }
      install(ContentNegotiation) {
        jackson { JacksonConfig.customizer(this) }
      }
    }
  }
}
