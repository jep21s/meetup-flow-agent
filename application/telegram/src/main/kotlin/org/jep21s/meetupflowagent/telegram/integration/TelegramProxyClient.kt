package org.jep21s.meetupflowagent.telegram.integration

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

private val logger = KotlinLogging.logger { }

/** Кнопка под сообщением: текст + callback_data (формат задаёт telegram-модуль). */
data class TgButton(val text: String, val callbackData: String)

/**
 * HTTP-клиент telegram-прокси (Railway): прокси — тупая труба, вся логика
 * решений здесь, в основном сервисе. Пустой proxy.baseUrl → не сконфигурирован
 * (dev-режим: [configured] = false, вызовы не выполняются).
 */
@Singleton
class TelegramProxyClient(
  private val httpClient: HttpClient = defaultHttpClient(),
) {

  /** Прокси сконфигурирован (иначе уведомления noop, доставки outbox считаются успешными). */
  val configured: Boolean
    get() = baseUrl().isNotEmpty()

  /** Отправить текст; messageId или null (все попытки упали). */
  suspend fun sendText(chatId: Long, text: String): Long? =
    send("send", mapOf("chatId" to chatId, "text" to text)) { json ->
      json.path("messageId").asLong()
    }

  /** Отправить текст с кнопками (HITL-вопрос); messageId или null. */
  suspend fun sendQuestion(chatId: Long, text: String, buttons: List<TgButton>): Long? =
    send("send", mapOf("chatId" to chatId, "text" to text, "buttons" to buttons)) { json ->
      json.path("messageId").asLong()
    }

  /** Тост-ответ на клик по кнопке; best-effort. */
  suspend fun answerCallback(callbackQueryId: String, text: String) {
    send("callback-answer", mapOf("callbackQueryId" to callbackQueryId, "text" to text))
  }

  /** Снять inline-кнопки с сообщения (защита от повторных кликов); best-effort. */
  suspend fun removeKeyboard(chatId: Long, messageId: Long) {
    send("message-keyboard-remove", mapOf("chatId" to chatId, "messageId" to messageId))
  }

  private suspend fun send(
    op: String,
    body: Map<String, Any?>,
    parse: (com.fasterxml.jackson.databind.JsonNode) -> Long = { -1L },
  ): Long? {
    val baseUrl = baseUrl()
    if (baseUrl.isEmpty()) {
      logger.debug { "proxy.baseUrl empty — skip send '$op'" }
      return null
    }
    val token = ConfigLoader.getProperty("proxy.token")
    val payload = jacksonMapper.writeValueAsString(body)
    var attempt = 0
    while (true) {
      val response = try {
        httpClient.post("$baseUrl/api/$op") {
          header(HttpHeaders.Authorization, "Bearer $token")
          contentType(ContentType.Application.Json)
          setBody(payload)
        }
      } catch (e: Exception) {
        logger.warn(e) { "proxy '$op' network failure (attempt ${attempt + 1})" }
        if (attempt < RETRIES) {
          attempt++; delay(300L * attempt); continue
        }
        return null
      }
      when {
        response.status.isSuccess() -> return parse(jacksonMapper.readTree(response.bodyAsText()))
        response.status.value >= 500 && attempt < RETRIES -> {
          attempt++
          logger.warn { "proxy '$op' 5xx (attempt $attempt): ${response.status.value}" }
          delay(300L * attempt)
        }
        else -> {
          val errorBody = response.bodyAsText().take(200)
          logger.warn { "proxy '$op' failed: HTTP ${response.status.value}: $errorBody" }
          return null
        }
      }
    }
  }

  private fun baseUrl(): String = ConfigLoader.getProperty("proxy.baseUrl").trim().trimEnd('/')

  companion object {
    private const val RETRIES = 2

    fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
      install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 15_000
      }
      expectSuccess = false
    }
  }
}
