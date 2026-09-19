package org.jep21s.meetupflowagent.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Мок-тесты Yandex-клиента: тело/заголовки запроса, парсинг, классификация ошибок. */
class YandexEmbeddingClientTest {

  @Test
  fun `sends correct body and headers, parses 768-dim vector`() {
    var capturedAuth: String? = null
    var capturedFolder: String? = null
    var capturedUrl: String? = null
    var capturedBody: String? = null
    val okBody = """{"object":"list","model":"emb","data":[{"object":"embedding","index":0,
        "embedding":[${(1..768).joinToString(",") { "0.0$it" }}]}]}"""

    val client = YandexEmbeddingClient(
      HttpClient(
        MockEngine { requestData: HttpRequestData ->
          capturedAuth = requestData.headers["Authorization"]
          capturedFolder = requestData.headers["x-folder-id"]
          capturedUrl = requestData.url.toString()
          capturedBody = (requestData.body as? TextContent)?.text
          respond(okBody, HttpStatusCode.OK, jsonHeaders())
        },
      ) {
        expectSuccess = false
      },
    )

    val vector = runBlocking { client.embed("PiterJS митап в Севкабель Порт") }

    assertThat(capturedUrl).isEqualTo("https://ai.api.cloud.yandex.net/v1/embeddings")
    assertThat(capturedAuth).isEqualTo("Bearer test-embedding-key")
    assertThat(capturedFolder).isEqualTo("test-folder")
    val body = jacksonMapper.readTree(capturedBody!!)
    assertThat(body.path("input").asText()).isEqualTo("PiterJS митап в Севкабель Порт")
    // dimensions ОБЯЗАТЕЛЕН: дефолт модели 256 (§10)
    assertThat(body.path("dimensions").asInt()).isEqualTo(768)
    assertThat(body.path("encoding_format").asText()).isEqualTo("float")
    assertThat(body.path("model").asText()).isEqualTo("emb://test-folder/text-embeddings-v2-doc/latest")
    assertThat(vector.size).isEqualTo(768)
  }

  @Test
  fun `401 maps to FATAL`() {
    val client = clientResponding("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized)
    val ex = assertThrows<EmbeddingException> { runBlocking { client.embed("текст") } }
    assertThat(ex.category).isEqualTo(LlmException.Category.FATAL)
  }

  @Test
  fun `429 and 5xx map to RETRYABLE`() {
    assertThat(categoryOf(HttpStatusCode.TooManyRequests)).isEqualTo(LlmException.Category.RETRYABLE)
    assertThat(categoryOf(HttpStatusCode.InternalServerError)).isEqualTo(LlmException.Category.RETRYABLE)
  }

  @Test
  fun `invalid json maps to PARSE`() {
    val client = clientResponding("не json", HttpStatusCode.OK)
    val ex = assertThrows<EmbeddingException> { runBlocking { client.embed("текст") } }
    assertThat(ex.category).isEqualTo(LlmException.Category.PARSE)
  }

  @Test
  fun `dimension mismatch maps to PARSE`() {
    // 3-мерный ответ вместо 768
    val client = clientResponding("""{"data":[{"embedding":[0.1,0.2,0.3]}]}""", HttpStatusCode.OK)
    val ex = assertThrows<EmbeddingException> { runBlocking { client.embed("текст") } }
    assertThat(ex.category).isEqualTo(LlmException.Category.PARSE)
    assertThat(ex.message).contains("256")
  }

  private fun categoryOf(status: HttpStatusCode): LlmException.Category {
    val client = clientResponding("""{"error":"x"}""", status)
    return assertThrows<EmbeddingException> { runBlocking { client.embed("текст") } }.category
  }

  private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

  private fun clientResponding(body: String, status: HttpStatusCode): YandexEmbeddingClient =
    YandexEmbeddingClient(
      HttpClient(MockEngine { respond(body, status, jsonHeaders()) }) {
        expectSuccess = false
      },
    )
}
