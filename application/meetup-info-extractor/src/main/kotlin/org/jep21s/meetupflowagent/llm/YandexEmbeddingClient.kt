package org.jep21s.meetupflowagent.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.core.annotation.Singleton

/** Запрос к OpenAI-compatible /embeddings (Yandex AI Studio, §10). */
data class EmbeddingRequestDto(
  val model: String,
  val input: String,
  @JsonProperty("encoding_format") val encodingFormat: String = "float",
  val dimensions: Int,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EmbeddingResponseDto(
  val data: List<EmbeddingItemDto> = emptyList(),
) {
  @JsonIgnoreProperties(ignoreUnknown = true)
  data class EmbeddingItemDto(val embedding: List<Double> = emptyList())
}

/**
 * Yandex AI Studio (OpenAI-compatible): POST {embedding.baseUrl}/embeddings.
 *
 * Особенности (проверено реальным запросом, §10): `dimensions` ОБЯЗАТЕЛЕН — дефолт
 * модели text-embeddings-v2-doc равен 256, параметр опускает размерность до 768;
 * одна строка за запрос (батчей нет). Авторизация: Bearer API-ключ сервис-аккаунта
 * + дублирующий заголовок `x-folder-id`; folderId входит и в URI модели.
 *
 * Ошибки: 429/5xx → RETRYABLE, прочие 4xx (включая 401/403) → FATAL, битый JSON —
 * PARSE. Конструктор с HttpClient — для тестов (ktor-client-mock).
 */
@Singleton
class YandexEmbeddingClient(
  private val httpClient: HttpClient = defaultHttpClient(),
) : EmbeddingClient {

  override suspend fun embed(text: String): FloatArray {
    val baseUrl = ConfigLoader.getRequiredProperty(
      "embedding.baseUrl",
      "embedding.baseUrl is not configured (EMBEDDING_BASE_URL)",
    ).trimEnd('/')
    val apiKey = ConfigLoader.getRequiredProperty(
      "embedding.apiKey",
      "embedding.apiKey is not configured (EMBEDDING_API_KEY)",
    )
    val folderId = ConfigLoader.getRequiredProperty(
      "embedding.folderId",
      "embedding.folderId is not configured (EMBEDDING_FOLDER_ID)",
    )
    val dimensions = ConfigLoader.getProperty("embedding.dimensions", DEFAULT_DIM.toString()).toInt()
    val modelUri = ConfigLoader.getProperty("embedding.modelUri")
      .ifBlank { "emb://$folderId/text-embeddings-v2-doc/latest" }

    val response = try {
      httpClient.post("$baseUrl/embeddings") {
        header(HttpHeaders.Authorization, "Bearer $apiKey")
        header("x-folder-id", folderId)
        contentType(ContentType.Application.Json)
        // сериализация вручную (без ContentNegotiation): тело — String, нужен только Jackson
        setBody(jacksonMapper.writeValueAsString(EmbeddingRequestDto(model = modelUri, input = text, dimensions = dimensions)))
      }
    } catch (e: Exception) {
      throw EmbeddingException(
        LlmException.Category.RETRYABLE,
        "Embedding network call failed: ${e::class.simpleName}: ${e.message}",
        e,
      )
    }

    checkStatus(response)

    val body = response.bodyAsText()
    val parsed = try {
      jacksonMapper.readValue(body, EmbeddingResponseDto::class.java)
    } catch (e: Exception) {
      throw EmbeddingException(
        LlmException.Category.PARSE,
        "Embedding response is not valid JSON (first 300 chars: ${body.take(300)})",
        e,
      )
    }
    val vector = parsed.data.firstOrNull()?.embedding
      ?: throw EmbeddingException(LlmException.Category.PARSE, "Embedding response has no data[0].embedding")
    if (vector.size != dimensions) {
      throw EmbeddingException(
        LlmException.Category.PARSE,
        "Embedding dimension mismatch: expected $dimensions, got ${vector.size} " +
          "(проверьте параметр dimensions — дефолт модели 256)",
      )
    }
    return FloatArray(dimensions) { vector[it].toFloat() }
  }

  private suspend fun checkStatus(response: HttpResponse) {
    if (response.status.value == 429 || response.status.value >= 500) {
      throw EmbeddingException(
        LlmException.Category.RETRYABLE,
        "Embedding provider returned HTTP ${response.status.value}",
      )
    }
    if (!response.status.isSuccess()) {
      throw EmbeddingException(
        LlmException.Category.FATAL,
        "Embedding provider returned HTTP ${response.status.value}: ${response.bodyAsText().take(300)}",
      )
    }
  }

  companion object {
    const val DEFAULT_DIM: Int = 768

    fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
      install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 30_000
      }
    }
  }
}
