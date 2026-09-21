package org.jep21s.meetupflowagent.telegram.outbox

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jep21s.meetupflowagent.outbox.OutboxDeliveryException
import org.jep21s.meetupflowagent.outbox.OutboxDeliveryTask
import org.jep21s.meetupflowagent.outbox.OutboxPublicationPayload
import org.jep21s.meetupflowagent.outbox.OutboxTransport
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.jep21s.meetupflowagent.telegram.integration.TelegramProxyClient
import org.koin.core.annotation.Singleton
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val logger = KotlinLogging.logger { }

/**
 * Доставка публикации в Telegram через прокси: анонс в общий канал
 * (telegram.main.chat-id). Замена транспорта telegram-proxy v1: адресацию и
 * дедуп deliveryId (outbox at-least-once) делает этот модуль, прокси только
 * отправляет. Пустой proxy.baseUrl → доставка считается выполненной (dev).
 */
@Singleton
class TelegramOutboxTransport(
  private val proxyClient: TelegramProxyClient,
) : OutboxTransport {

  private val mainChatId = ConfigLoader.getProperty("telegram.main.chat-id").trim().toLongOrNull()

  override val type: String = "telegram_proxy"

  override suspend fun deliver(task: OutboxDeliveryTask) {
    if (!proxyClient.configured) {
      logger.debug { "proxy.baseUrl empty — delivery ${task.deliveryId} skipped (counted as sent)" }
      return
    }
    if (!acquireDeliveryId(task.deliveryId.toString())) {
      logger.info { "duplicate deliveryId ignored: ${task.deliveryId}" }
      return
    }
    val chatId = mainChatId
      ?: throw OutboxDeliveryException("telegram.main.chat-id not configured — cannot deliver ${task.deliveryId}")
    val messageId = proxyClient.sendText(chatId, formatAnnouncement(task.payload))
      ?: throw OutboxDeliveryException("proxy send failed: deliveryId=${task.deliveryId}")
    logger.info { "announcement delivered: deliveryId=${task.deliveryId} chatId=$chatId messageId=$messageId" }
  }

  /**
   * Bounded-дедуп deliveryId in-memory (как в прокси v1): after-crash дубль
   * доставится повторно — приемлемо для анонса в канал. Переживать рестарт
   * не обязан: outbox помечает SENT только после успешной доставки.
   */
  private val seenDeliveryIds = object : LinkedHashMap<String, Unit>(64, 0.75f, false) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>): Boolean = size > 10_000
  }

  private fun acquireDeliveryId(id: String): Boolean = synchronized(seenDeliveryIds) {
    if (seenDeliveryIds.containsKey(id)) {
      false
    } else {
      seenDeliveryIds[id] = Unit
      true
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
      when {
        !payload.event.registrationUrl.isNullOrBlank() -> appendLine("🔗 ${payload.event.registrationUrl}")
        payload.event.registrationNotRequired == true -> appendLine("🎟 Регистрация не требуется")
      }
      payload.event.organizer?.takeIf { it.isNotBlank() }?.let { appendLine("👤 $it") }
      payload.event.description
        ?.takeIf { it.isNotBlank() }
        ?.let { appendLine("ℹ️ ${it.trim().take(500)}") }
      if (payload.event.tags.isNotEmpty()) {
        appendLine(payload.event.tags.joinToString(" ") { "#${it.replace(' ', '_')}" })
      }
      append(
        if (payload.event.registrationNotRequired == true) "Вход бесплатный, регистрация не нужна."
        else "Вход бесплатный, участие после регистрации.",
      )
    }
  }
}
