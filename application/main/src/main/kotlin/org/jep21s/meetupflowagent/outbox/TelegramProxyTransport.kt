package org.jep21s.meetupflowagent.outbox

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import org.jep21s.meetupflowagent.notify.HttpProxyNotifier
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.core.annotation.Singleton
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val logger = KotlinLogging.logger { }

/**
 * Доставка публикации в Telegram через прокси: существующий контракт
 * POST {proxy.baseUrl}/api/notify + Bearer {proxy.token}, event = EVENT_PUBLISHED,
 * userIds = [] — анонс адресован общему каналу, а не персонально.
 * Секреты (token) — из ENV/конфига, destinations.config здесь не используется.
 * Пустой proxy.baseUrl → доставка считается выполненной (dev-режим без прокси).
 */
@Singleton
class TelegramProxyTransport(
  private val httpClient: HttpClient = HttpProxyNotifier.defaultHttpClient(),
) : OutboxTransport {

  override val type: String = "telegram_proxy"

  override suspend fun deliver(task: OutboxDeliveryTask) {
    val baseUrl = ConfigLoader.getProperty("proxy.baseUrl").trim().trimEnd('/')
    if (baseUrl.isEmpty()) {
      logger.debug { "proxy.baseUrl empty — delivery ${task.deliveryId} skipped (counted as sent)" }
      return
    }
    val token = ConfigLoader.getProperty("proxy.token")
    val body = jacksonMapper.writeValueAsString(
      mapOf(
        "flowId" to task.payload.flowId,
        "event" to "EVENT_PUBLISHED",
        "userIds" to emptyList<Long>(),
        "text" to formatAnnouncement(task.payload),
        "options" to emptyList<String>(),
        "deliveryId" to task.deliveryId.toString(),
      ),
    )
    val response = try {
      httpClient.post("$baseUrl/api/notify") {
        if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(body)
      }
    } catch (e: Exception) {
      throw OutboxDeliveryException("proxy network failure: ${e.message}", e)
    }
    if (!response.status.isSuccess()) {
      throw OutboxDeliveryException(
        "proxy responded HTTP ${response.status.value}: ${response.bodyAsText().take(200)}",
      )
    }
  }

  companion object {
    private val MOSCOW = ZoneId.of("Europe/Moscow")
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale("ru"))

    /** Текст анонса для канала: место/время/ссылка — то, что читатель проверяет первым. */
    fun formatAnnouncement(payload: OutboxPublicationPayload): String = buildString {
      appendLine("📣 ${payload.event.title}")
      appendLine("📅 ${DATE_FORMAT.format(payload.event.startsAt.atZone(MOSCOW))} МСК")
      val place = listOfNotNull(payload.event.venueName, payload.event.address)
        .filter { it.isNotBlank() }
        .joinToString(", ")
      if (place.isNotBlank()) appendLine("📍 $place")
      payload.event.registrationUrl?.takeIf { it.isNotBlank() }?.let { appendLine("🔗 $it") }
      payload.event.organizer?.takeIf { it.isNotBlank() }?.let { appendLine("👤 $it") }
      payload.event.description
        ?.takeIf { it.isNotBlank() }
        ?.let { appendLine("ℹ️ ${it.trim().take(500)}") }
      if (payload.event.tags.isNotEmpty()) {
        appendLine(payload.event.tags.joinToString(" ") { "#${it.replace(' ', '_')}" })
      }
      append("Вход бесплатный, участие после регистрации.")
    }
  }
}
