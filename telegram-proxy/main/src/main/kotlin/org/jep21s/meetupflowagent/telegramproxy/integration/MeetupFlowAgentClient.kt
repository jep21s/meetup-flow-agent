package org.jep21s.meetupflowagent.telegramproxy.integration

import io.github.oshai.kotlinlogging.KotlinLogging
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
import kotlinx.coroutines.delay
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.core.annotation.Singleton
import java.util.UUID

private val logger = KotlinLogging.logger { }

/** Результат вызова агента: 202 — принято; Duplicate — 409 (идемпотентно «ок»); Failure — остальное. */
sealed interface AgentCallResult {
  data object Accepted : AgentCallResult
  data object Duplicate : AgentCallResult
  data class Failure(val status: Int?, val error: String?) : AgentCallResult
}

/** Интеграция с meetup-flow-agent (обратная сторона контрактов §2.1/§2.3 плана). */
interface MeetupFlowAgent {
  /**
   * POST /api/messages: сырой passthrough — text = JSON всей DTO Update,
   * idempotencyKey = "tg-<updateId>" (защита от дублей при рестартах).
   */
  suspend fun postMessage(idempotencyKey: String, text: String, meta: Map<String, Any?>): AgentCallResult

  /** POST /api/flows/{flowId}/responses — ответ человека на HITL-вопрос. */
  suspend fun submitHumanResponse(flowId: UUID, responderUserId: Long, answer: String): AgentCallResult
}

/**
 * HTTP-реализация. ВАЖНО (асимметрия контрактов main): /api/messages — RAW
 * Authorization (без "Bearer"), /responses — "Bearer {token}". Ретраи 2× на
 * 5xx/сеть: апдейт одноразовый, потерять нельзя.
 */
@Singleton(binds = [MeetupFlowAgent::class])
class MeetupFlowAgentClient(
  private val httpClient: HttpClient = defaultHttpClient(),
) : MeetupFlowAgent {

  override suspend fun postMessage(idempotencyKey: String, text: String, meta: Map<String, Any?>): AgentCallResult {
    val baseUrl = baseUrl()
    val token = ConfigLoader.getProperty("meetup-flow.token")
    val body = jacksonMapper.writeValueAsString(
      mapOf(
        "idempotencyKey" to idempotencyKey,
        "text" to text,
        "meta" to meta,
      )
    )
    return callWithRetries("postMessage $idempotencyKey") { attempt ->
      val response = httpClient.post("$baseUrl/api/messages") {
        header(HttpHeaders.Authorization, token) // RAW, без Bearer — контракт main
        contentType(ContentType.Application.Json)
        setBody(body)
      }
      when {
        response.status.isSuccess() -> {
          val acceptedBody = response.bodyAsText().take(100)
          logger.info { "message accepted by agent: key=$idempotencyKey flowId=$acceptedBody" }
          AgentCallResult.Accepted
        }
        response.status.value == 409 -> {
          logger.info { "message duplicate (expected on restarts): key=$idempotencyKey" }
          AgentCallResult.Duplicate
        }
        else -> terminalOrRetry(response, "postMessage", attempt)
      }
    }
  }

  override suspend fun submitHumanResponse(flowId: UUID, responderUserId: Long, answer: String): AgentCallResult {
    val baseUrl = baseUrl()
    val token = ConfigLoader.getProperty("meetup-flow.token")
    val body = jacksonMapper.writeValueAsString(
      mapOf(
        "responderUserId" to responderUserId,
        "answer" to answer,
      )
    )
    return callWithRetries("submitHumanResponse $flowId") { attempt ->
      val response = httpClient.post("$baseUrl/api/flows/$flowId/responses") {
        header(HttpHeaders.Authorization, "Bearer $token") // /responses — Bearer
        contentType(ContentType.Application.Json)
        setBody(body)
      }
      when {
        response.status.isSuccess() -> {
          logger.info { "human response accepted: flowId=$flowId responder=$responderUserId" }
          AgentCallResult.Accepted
        }
        response.status.value == 409 -> {
          logger.info { "human response duplicate (first answer wins): flowId=$flowId" }
          AgentCallResult.Duplicate
        }
        else -> terminalOrRetry(response, "submitHumanResponse", attempt)
      }
    }
  }

  private fun baseUrl(): String = ConfigLoader.getProperty("meetup-flow.url", "http://localhost:8090")
    .trim().trimEnd('/')

  /**
   * Ретраи: 5xx/сеть → до 2 повторов (апдейт одноразовый); 4xx — терминально.
   * Возвращает TerminalFailure, если ретраи исчерпаны.
   */
  private suspend fun callWithRetries(
    tag: String,
    block: suspend (attempt: Int) -> AgentCallResult,
  ): AgentCallResult {
    var attempt = 0
    while (true) {
      val result = try {
        block(attempt)
      } catch (e: Exception) {
        logger.warn(e) { "$tag network failure (attempt ${attempt + 1})" }
        AgentCallResult.Failure(null, e.message)
      }
      val retryable = result is AgentCallResult.Failure && (result.status == null || result.status >= 500)
      if (!retryable || attempt >= 2) {
        if (result is AgentCallResult.Failure) {
          logger.warn { "$tag failed permanently: $result" }
        }
        return result
      }
      attempt++
      delay(300L * attempt)
    }
  }

  /** 5xx → сигнал ретрая; 4xx → терминальный Failure. */
  private suspend fun terminalOrRetry(response: HttpResponse, tag: String, attempt: Int): AgentCallResult {
    val body = response.bodyAsText().take(200)
    return if (response.status.value >= 500 && attempt < 2) {
      logger.warn { "$tag 5xx (attempt ${attempt + 1}): ${response.status.value}" }
      AgentCallResult.Failure(response.status.value, body)
    } else {
      logger.warn { "$tag failed: HTTP ${response.status.value}: $body" }
      AgentCallResult.Failure(response.status.value, body)
    }
  }

  companion object {
    fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
      install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 30_000
      }
      expectSuccess = false
    }
  }
}
