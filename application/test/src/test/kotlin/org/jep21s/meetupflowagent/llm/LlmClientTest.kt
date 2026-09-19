package org.jep21s.meetupflowagent.llm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.jackson.jackson
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.jep21s.meetupflowagent.llm.dto.ChatCompletionRequest
import org.jep21s.meetupflowagent.llm.dto.ChatCompletionResponse
import org.jep21s.meetupflowagent.llm.dto.ChatMessage
import org.jep21s.meetupflowagent.starter.jackson.JacksonConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LlmClientTest {

  private val mapper = jacksonObjectMapper().apply { JacksonConfig.customizer(this) }

  @Test
  fun `parses plain content response`() {
    val json = """
      {"choices":[{"index":0,"message":{"role":"assistant","content":"финальный JSON"},"finish_reason":"stop"}],
       "usage":{"prompt_tokens":120,"completion_tokens":45,"total_tokens":165}}
    """.trimIndent()

    val response = mapper.readValue(json, ChatCompletionResponse::class.java)

    assertThat(response.choices).hasSize(1)
    assertThat(response.firstMessage().content).isEqualTo("финальный JSON")
    assertThat(response.usage?.promptTokens).isEqualTo(120)
    assertThat(response.usage?.completionTokens).isEqualTo(45)
  }

  @Test
  fun `parses tool_calls response`() {
    val json = """
      {"choices":[{"index":0,"message":{"role":"assistant","content":null,
        "tool_calls":[{"id":"call_abc","type":"function",
          "function":{"name":"fetch_web_page","arguments":"{\"url\":\"https://example.com\"}"}}]},
        "finish_reason":"tool_calls"}],
       "usage":{"prompt_tokens":50,"completion_tokens":20,"total_tokens":70}}
    """.trimIndent()

    val response = mapper.readValue(json, ChatCompletionResponse::class.java)

    val calls = response.firstMessage().toolCalls
    assertThat(calls).hasSize(1)
    assertThat(calls!!.first().id).isEqualTo("call_abc")
    assertThat(calls.first().function.name).isEqualTo("fetch_web_page")
    assertThat(calls.first().function.arguments).contains("example.com")
  }

  @Test
  fun `classifies http error statuses`() {
    assertThat(categoryOf(HttpStatusCode.TooManyRequests)).isEqualTo(LlmException.Category.RETRYABLE)
    assertThat(categoryOf(HttpStatusCode.InternalServerError)).isEqualTo(LlmException.Category.RETRYABLE)
    assertThat(categoryOf(HttpStatusCode.BadGateway)).isEqualTo(LlmException.Category.RETRYABLE)
    assertThat(categoryOf(HttpStatusCode.Unauthorized)).isEqualTo(LlmException.Category.FATAL)
    assertThat(categoryOf(HttpStatusCode.BadRequest)).isEqualTo(LlmException.Category.FATAL)
  }

  @Test
  fun `invalid json body maps to PARSE`() {
    val client = clientResponding("не json вообще")

    val ex = assertThrows<LlmException> {
      runBlocking { client.complete(request()) }
    }
    assertThat(ex.category).isEqualTo(LlmException.Category.PARSE)
  }

  @Test
  fun `sends bearer token and parses response`() {
    var capturedAuth: String? = null
    var capturedBody: String? = null
    val okBody = """
      {"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
       "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
    """.trimIndent()
    val client = KtorOpenAiLlmClient(
      HttpClient(
        MockEngine { requestData: HttpRequestData ->
          capturedAuth = requestData.headers["Authorization"]
          capturedBody = (requestData.body as? TextContent)?.text
          respond(okBody, HttpStatusCode.OK, jsonHeaders())
        },
      ) {
        install(ContentNegotiation) { jackson { JacksonConfig.customizer(this) } }
      },
    )

    val response = runBlocking { client.complete(request()) }

    assertThat(capturedAuth).isEqualTo("Bearer test-key")
    // тело сериализуется ContentNegotiation-конвертером (не TextContent) — проверяем тип
    assertThat(capturedBody).isNull()
    assertThat(response.firstMessage().content).isEqualTo("ok")
  }

  private fun categoryOf(status: HttpStatusCode): LlmException.Category {
    val client = clientResponding("err", status)
    val ex = assertThrows<LlmException> {
      runBlocking { client.complete(request()) }
    }
    return ex.category
  }

  private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

  private fun clientResponding(body: String, status: HttpStatusCode = HttpStatusCode.OK): KtorOpenAiLlmClient =
    KtorOpenAiLlmClient(
      HttpClient(MockEngine { respond(body, status, jsonHeaders()) }) {
        install(ContentNegotiation) { jackson { JacksonConfig.customizer(this) } }
      },
    )

  private fun request() = ChatCompletionRequest(
    model = "glm-test",
    messages = listOf(ChatMessage.user("тест")),
  )
}
