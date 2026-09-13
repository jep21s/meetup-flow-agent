package org.jep21s.meetupflowagent.llm

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.jackson.jackson
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import org.jep21s.meetupflowagent.llm.dto.ChatCompletionRequest
import org.jep21s.meetupflowagent.llm.dto.ChatCompletionResponse
import org.jep21s.meetupflowagent.llm.dto.ChatMessage
import org.jep21s.meetupflowagent.llm.dto.ChatRole
import org.jep21s.meetupflowagent.llm.dto.Choice
import org.jep21s.meetupflowagent.llm.dto.FunctionCall
import org.jep21s.meetupflowagent.llm.dto.ToolCall
import org.jep21s.meetupflowagent.llm.dto.Usage
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
    val response = postChat(request, sse = false)
    checkStatus(response)

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

  override suspend fun streamChat(
    request: ChatCompletionRequest,
    onDelta: suspend (StreamDelta) -> Unit,
  ): ChatCompletionResponse {
    val response = postChat(request, sse = true)
    checkStatus(response)
    return try {
      parseSseStream(response.bodyAsChannel(), onDelta)
    } catch (e: LlmException) {
      throw e
    } catch (e: Exception) {
      throw toNetworkException(e)
    }
  }

  private suspend fun postChat(request: ChatCompletionRequest, sse: Boolean): HttpResponse {
    val baseUrl = ConfigLoader.getRequiredProperty(
      "llm.baseUrl",
      "llm.baseUrl is not configured (LLM_BASE_URL)",
    ).trimEnd('/')
    val apiKey = ConfigLoader.getRequiredProperty(
      "llm.apiKey",
      "llm.apiKey is not configured (LLM_API_KEY)",
    )

    return try {
      httpClient.post("$baseUrl/chat/completions") {
        header(HttpHeaders.Authorization, "Bearer $apiKey")
        contentType(ContentType.Application.Json)
        if (sse) {
          accept(ContentType.Text.EventStream)
          setBody(request.copy(stream = true))
        } else {
          setBody(request)
        }
      }
    } catch (e: Exception) {
      throw toNetworkException(e)
    }
  }

  private suspend fun checkStatus(response: HttpResponse) {
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
  }

  /**
   * SSE-парсинг: `data: {chunk}` → дельты; `data: [DONE]` — конец стрима. Строки
   * event/id/retry и комментарии игнорируются (OpenAI-совместимые провайдеры шлют
   * только data). Полный ответ накапливается параллельно с эмиссией дельт.
   */
  private suspend fun parseSseStream(
    channel: ByteReadChannel,
    onDelta: suspend (StreamDelta) -> Unit,
  ): ChatCompletionResponse {
    val reasoning = StringBuilder()
    val content = StringBuilder()
    val toolCalls = sortedMapOf<Int, ToolCallAccumulator>()
    var finishReason: String? = null
    var usage: Usage? = null

    suspend fun handleData(data: String): Boolean {
      if (data == DONE_MARKER) return false
      val chunk = try {
        jacksonMapper.readTree(data)
      } catch (e: Exception) {
        throw LlmException(
          LlmException.Category.PARSE,
          "SSE chunk is not valid JSON (first 300 chars: ${data.take(300)})",
          e,
        )
      }

      chunk.path("usage").takeIf { it.isObject && !it.isEmpty }?.let {
        usage = Usage(
          promptTokens = it.path("prompt_tokens").asInt(0),
          completionTokens = it.path("completion_tokens").asInt(0),
          totalTokens = it.path("total_tokens").asInt(0),
        )
      }

      val choice = chunk.path("choices").firstOrNull() ?: return true
      val finish = choice.path("finish_reason").textOrNull()?.takeIf { it.isNotEmpty() }

      val delta = choice.path("delta")
      delta.path("reasoning_content").textOrNull()?.let {
        if (it.isNotEmpty()) {
          reasoning.append(it)
          onDelta(StreamDelta.ReasoningDelta(it))
        }
      }
      delta.path("content").textOrNull()?.let {
        if (it.isNotEmpty()) {
          content.append(it)
          onDelta(StreamDelta.ContentDelta(it))
        }
      }
      delta.path("tool_calls").forEach { callDelta ->
        val index = callDelta.path("index").asInt(0)
        val acc = toolCalls.getOrPut(index) { ToolCallAccumulator(index) }
        callDelta.path("id").textOrNull()?.let { acc.id = it }
        callDelta.path("function").path("name").textOrNull()?.let { acc.name = it }
        callDelta.path("function").path("arguments").textOrNull()?.let { chunkArgs ->
          acc.arguments.append(chunkArgs)
          onDelta(
            StreamDelta.ToolCallDelta(
              index = index,
              id = acc.id,
              functionName = acc.name,
              argumentsChunk = chunkArgs,
            ),
          )
        }
      }
      if (finish != null) {
        finishReason = finish
        onDelta(StreamDelta.Finish(finish))
      }
      return true
    }

    val dataLines = mutableListOf<String>()
    while (true) {
      // readUTF8Line устарел; readLine + ручной trim CR — устойчиво к LF и CRLF
      val line = channel.readLine()?.trimEnd('\r') ?: break
      when {
        line.isEmpty() -> {
          if (dataLines.isNotEmpty()) {
            val data = dataLines.joinToString("\n")
            dataLines.clear()
            if (!handleData(data)) break
          }
        }

        line.startsWith("data:") -> dataLines += line.removePrefix("data:").removePrefix(" ")
        // event:/id:/retry:/комментарии — не используются, пропускаем
      }
    }
    // хвостовой ивент без завершающей пустой строки (провайдер закрыл соединение)
    if (dataLines.isNotEmpty()) {
      handleData(dataLines.joinToString("\n"))
    }

    val message = ChatMessage(
      role = ChatRole.ASSISTANT,
      content = content.toString().ifEmpty { null },
      reasoningContent = reasoning.toString().ifEmpty { null },
      toolCalls = toolCalls.values.map { acc ->
        ToolCall(
          id = acc.id ?: "call_${acc.index}",
          type = "function",
          function = FunctionCall(name = acc.name.orEmpty(), arguments = acc.arguments.toString()),
        )
      }.takeIf { it.isNotEmpty() },
    )
    return ChatCompletionResponse(
      choices = listOf(Choice(message = message, finishReason = finishReason)),
      usage = usage ?: Usage(),
    )
  }

  private fun JsonNode.textOrNull(): String? =
    if (isMissingNode || isNull) null else asText()

  private fun toNetworkException(e: Exception): LlmException =
    // таймауты/коннект-сбои/DNS — все сетевые ошибки считаем транзиентными
    LlmException(
      LlmException.Category.RETRYABLE,
      "LLM network call failed: ${e::class.simpleName}: ${e.message}",
      e,
    )

  /** Наращивание tool_call по index: id/name приходят в первой дельте, аргументы — кусками. */
  private class ToolCallAccumulator(val index: Int) {
    var id: String? = null
    var name: String? = null
    val arguments = StringBuilder()
  }

  companion object {
    private const val DONE_MARKER = "[DONE]"

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
