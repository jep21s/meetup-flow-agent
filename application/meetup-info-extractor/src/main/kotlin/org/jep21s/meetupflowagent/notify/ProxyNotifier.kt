package org.jep21s.meetupflowagent.notify

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
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.core.annotation.Singleton
import java.util.UUID

private val logger = KotlinLogging.logger { }

/** Уведомление прокси-проекту (§11): событие флоу + текст + адресаты. */
data class ProxyNotification(
  val flowId: UUID,
  val event: String,
  val userIds: List<Long>,
  val text: String,
  val options: List<String> = emptyList(),
)

/** Отправка уведомлений Telegram-прокси. Пустой proxy.baseUrl → noop (dev без прокси). */
interface ProxyNotifier {
  suspend fun notify(notification: ProxyNotification)
}

/**
 * HTTP-реализация: POST {proxy.baseUrl}/api/notify, Bearer {proxy.token};
 * retry 2× на 5xx. Конструктор с HttpClient — для WireMock-тестов.
 */
@Singleton(binds = [ProxyNotifier::class])
class HttpProxyNotifier(
  private val httpClient: HttpClient = defaultHttpClient(),
) : ProxyNotifier {

  override suspend fun notify(notification: ProxyNotification) {
    val baseUrl = ConfigLoader.getProperty("proxy.baseUrl").trim().trimEnd('/')
    if (baseUrl.isEmpty()) {
      logger.debug { "proxy.baseUrl empty — notification skipped: ${notification.event} flowId=${notification.flowId}" }
      return
    }
    val token = ConfigLoader.getProperty("proxy.token")
    val body = jacksonMapper.writeValueAsString(
      mapOf(
        "flowId" to notification.flowId.toString(),
        "event" to notification.event,
        "userIds" to notification.userIds,
        "text" to notification.text,
        "options" to notification.options,
      ),
    )
    var attempt = 0
    while (true) {
      val response = try {
        httpClient.post("$baseUrl/api/notify") {
          if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
          contentType(ContentType.Application.Json)
          setBody(body)
        }
      } catch (e: Exception) {
        logger.warn(e) { "proxy notify network failure (attempt ${attempt + 1}): ${notification.event}" }
        return // сетевые сбои не ретраим: некритичное уведомление
      }
      when {
        response.status.isSuccess() -> return
        response.status.value >= 500 && attempt < 2 -> {
          attempt++
          logger.warn { "proxy notify 5xx (attempt $attempt): ${response.status.value}" }
        }
        else -> {
          val body = response.bodyAsText()
          logger.warn { "proxy notify failed: HTTP ${response.status.value}: ${body.take(200)}" }
          return
        }
      }
    }
  }

  companion object {
    fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
      install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 15_000
      }
      expectSuccess = false
    }
  }
}
