package org.jep21s.meetupflowagent.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.jackson.jackson
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.jep21s.meetupflowagent.llm.dto.ChatCompletionRequest
import org.jep21s.meetupflowagent.llm.dto.ChatMessage
import org.jep21s.meetupflowagent.starter.jackson.JacksonConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SseStreamParsingTest {

  @Test
  fun `assembles tool_call split into argument chunks`() {
    val body = sse(
      """{"choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"Смотрю на ссылку"},"finish_reason":null}]}""",
      """{"choices":[{"index":0,"delta":{"reasoning_content":", открою страницу"},"finish_reason":null}]}""",
      """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"fetch_web_page","arguments":""}}]},"finish_reason":null}]}""",
      """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"ur"}}]},"finish_reason":null}]}""",
      """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"l\":\"https://example.com\"}"}}]},"finish_reason":"tool_calls"}]}""",
    )
    var acceptHeader: String? = null
    val client = client(body) { requestData: HttpRequestData ->
      acceptHeader = requestData.headers["Accept"]
    }

    val deltas = mutableListOf<StreamDelta>()
    val response = runBlocking { client.streamChat(request()) { deltas += it } }

    assertThat(acceptHeader).isEqualTo("text/event-stream")
    assertThat(deltas).containsExactly(
      StreamDelta.ReasoningDelta("Смотрю на ссылку"),
      StreamDelta.ReasoningDelta(", открою страницу"),
      StreamDelta.ToolCallDelta(0, "call_1", "fetch_web_page", ""),
      StreamDelta.ToolCallDelta(0, "call_1", "fetch_web_page", "{\"ur"),
      StreamDelta.ToolCallDelta(0, "call_1", "fetch_web_page", "l\":\"https://example.com\"}"),
      StreamDelta.Finish("tool_calls"),
    )

    val message = response.firstMessage()
    assertThat(message.content).isNull()
    assertThat(message.reasoningContent).isEqualTo("Смотрю на ссылку, открою страницу")
    assertThat(message.toolCalls).hasSize(1)
    assertThat(message.toolCalls!!.single().id).isEqualTo("call_1")
    assertThat(message.toolCalls!!.single().function.name).isEqualTo("fetch_web_page")
    assertThat(message.toolCalls!!.single().function.arguments)
      .isEqualTo("""{"url":"https://example.com"}""")
    assertThat(response.choices.single().finishReason).isEqualTo("tool_calls")
  }

  @Test
  fun `assembles reasoning and content deltas in arrival order`() {
    val body = sse(
      """{"choices":[{"index":0,"delta":{"reasoning_content":"думаю"},"finish_reason":null}]}""",
      """{"choices":[{"index":0,"delta":{"content":"{\"tit"},"finish_reason":null}]}""",
      """{"choices":[{"index":0,"delta":{"reasoning_content":" ещё"},"finish_reason":null}]}""",
      """{"choices":[{"index":0,"delta":{"content":"le\":\"X\"}"},"finish_reason":"stop"}]}""",
    )
    val client = client(body)

    val deltas = mutableListOf<StreamDelta>()
    val response = runBlocking { client.streamChat(request()) { deltas += it } }

    assertThat(deltas).containsExactly(
      StreamDelta.ReasoningDelta("думаю"),
      StreamDelta.ContentDelta("{\"tit"),
      StreamDelta.ReasoningDelta(" ещё"),
      StreamDelta.ContentDelta("le\":\"X\"}"),
      StreamDelta.Finish("stop"),
    )
    assertThat(response.firstMessage().content).isEqualTo("""{"title":"X"}""")
    assertThat(response.firstMessage().reasoningContent).isEqualTo("думаю ещё")
  }

  @Test
  fun `reads usage from final chunk`() {
    val body = sse(
      """{"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}],"usage":null}""",
      """{"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":45,"total_tokens":165}}""",
    )
    val client = client(body)

    val response = runBlocking { client.streamChat(request()) { } }

    assertThat(response.usage?.promptTokens).isEqualTo(120)
    assertThat(response.usage?.totalTokens).isEqualTo(165)
  }

  @Test
  fun `429 on stream is RETRYABLE`() {
    val client = client("rate limited", HttpStatusCode.TooManyRequests)

    val ex = assertThrows<LlmException> {
      runBlocking { client.streamChat(request()) { } }
    }
    assertThat(ex.category).isEqualTo(LlmException.Category.RETRYABLE)
  }

  @Test
  fun `invalid chunk json maps to PARSE`() {
    val client = client("data: не json вообще\n\n")

    val ex = assertThrows<LlmException> {
      runBlocking { client.streamChat(request()) { } }
    }
    assertThat(ex.category).isEqualTo(LlmException.Category.PARSE)
  }

  private fun sse(vararg chunks: String): String =
    chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

  private fun client(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    onRequest: (HttpRequestData) -> Unit = {},
  ): KtorOpenAiLlmClient =
    KtorOpenAiLlmClient(
      HttpClient(
        MockEngine { requestData: HttpRequestData ->
          onRequest(requestData)
          respond(
            content = ByteReadChannel(body),
            status = status,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
          )
        },
      ) {
        install(ContentNegotiation) { jackson { JacksonConfig.customizer(this) } }
      },
    )

  private fun request() = ChatCompletionRequest(
    model = "glm-test",
    messages = listOf(ChatMessage.user("тест")),
  )
}
