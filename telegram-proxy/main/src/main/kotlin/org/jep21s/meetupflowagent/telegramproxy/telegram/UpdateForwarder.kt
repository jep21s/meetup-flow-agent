package org.jep21s.meetupflowagent.telegramproxy.telegram

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.core.annotation.Named
import org.koin.core.annotation.Singleton
import org.telegram.telegrambots.meta.api.objects.Update

private val logger = KotlinLogging.logger { }

/**
 * Вход «тупой трубы»: каждый апдейт из [UpdateEventRelay] сериализуется ЦЕЛИКОМ
 * и уходит в основной сервис POST {meetup-flow.url}/api/telegram/updates
 * (Authorization: <meetup-flow.token> RAW — контракт /api основного сервиса).
 * Никакого разбора/решений: фильтры, HITL и флоу — на той стороне (модуль
 * application/telegram). Ретраи 2× на 5xx/сеть — апдейт одноразовый.
 */
@Singleton(createdAtStart = true)
class UpdateForwarder(
  private val httpClient: HttpClient = defaultHttpClient(),
  @Named("applicationCoroutineScope") private val scope: CoroutineScope,
) {

  init {
    scope.launch {
      UpdateEventRelay.getUpdateEventFlow().collect { update ->
        try {
          forward(update)
        } catch (e: Exception) {
          logger.error(e) { "update forwarding failed: updateId=${update.updateId}" }
        }
      }
    }
  }

  internal suspend fun forward(update: Update) {
    val baseUrl = ConfigLoader.getProperty("meetup-flow.url", "http://localhost:8090").trim().trimEnd('/')
    val token = ConfigLoader.getProperty("meetup-flow.token")
    val payload = jacksonMapper.writeValueAsString(update)
    var attempt = 0
    while (true) {
      val response = try {
        httpClient.post("$baseUrl/api/telegram/updates") {
          header(HttpHeaders.Authorization, token) // RAW, без Bearer — контракт main
          contentType(ContentType.Application.Json)
          setBody(payload)
        }
      } catch (e: Exception) {
        logger.warn(e) { "forward network failure (attempt ${attempt + 1}): updateId=${update.updateId}" }
        if (attempt < RETRIES) {
          attempt++; delay(300L * attempt); continue
        }
        logger.error { "update lost after retries: updateId=${update.updateId}" }
        return
      }
      when {
        response.status.isSuccess() -> {
          logger.debug { "update forwarded: updateId=${update.updateId}" }
          return
        }
        response.status.value >= 500 && attempt < RETRIES -> {
          attempt++
          logger.warn { "forward 5xx (attempt $attempt): ${response.status.value} updateId=${update.updateId}" }
          delay(300L * attempt)
        }
        else -> {
          val body = response.bodyAsText().take(200)
          logger.error { "update rejected: updateId=${update.updateId} HTTP ${response.status.value}: $body" }
          return
        }
      }
    }
  }

  companion object {
    private const val RETRIES = 2

    fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
      install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 30_000
      }
      expectSuccess = false
    }
  }
}
