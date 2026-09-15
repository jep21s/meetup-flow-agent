package org.jep21s.meetupflowagent.telegramproxy.route

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.jep21s.meetupflowagent.telegramproxy.telegram.HitlPendingStore
import org.jep21s.meetupflowagent.telegramproxy.telegram.PendingQuestion
import org.jep21s.meetupflowagent.telegramproxy.telegram.TgMessageSender
import java.util.UUID

private val logger = KotlinLogging.logger { }

/** Тело POST /api/notify — контракт HttpProxyNotifier/TelegramProxyTransport main. */
data class NotifyRequestDto(
  val flowId: UUID,
  val event: String,
  val userIds: List<Long> = emptyList(),
  val text: String,
  val options: List<String> = emptyList(),
  val deliveryId: String? = null,
)

/**
 * POST /api/notify — исходящие уведомления агента → Bot API (§2.2 плана).
 * Адресация: userIds непуст → личные чаты; пусто → общий канал [mainChatId].
 * HUMAN_INPUT_REQUIRED с options → вопрос с inline-кнопками + регистрация
 * pending (chatId,messageId)→flowId для ответов кнопкой/reply.
 *
 * Ответ: 202 всегда, КРОМЕ пустого списка целей или 100% неудачных отправок —
 * тогда 500 (HttpProxyNotifier ретраит 5xx; outbox повторит по своей шкале).
 */
fun Route.notify(sender: TgMessageSender, pendingStore: HitlPendingStore, mainChatId: Long?) {
  post("/notify") {
    val raw = call.receiveText()
    val request = try {
      jacksonMapper.readValue(raw, NotifyRequestDto::class.java)
    } catch (e: Exception) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON body: ${e.message}"))
      return@post
    }

    // outbox шлёт EVENT_PUBLISHED at-least-once (deliveryId) — глушим повторы
    if (request.deliveryId != null && !acquireDeliveryId(request.deliveryId)) {
      logger.info { "duplicate deliveryId ignored: ${request.deliveryId}" }
      call.respond(HttpStatusCode.Accepted, mapOf("duplicate" to true))
      return@post
    }

    val targets = request.userIds.ifEmpty { mainChatId?.let { listOf(it) } ?: emptyList() }
    if (targets.isEmpty()) {
      logger.warn { "no target chats for notification: event=${request.event} flowId=${request.flowId}" }
      call.respond(
        HttpStatusCode.InternalServerError,
        mapOf("error" to "No target chats: userIds empty and telegram.main.chat-id not configured"),
      )
      return@post
    }

    val withButtons = request.event == "HUMAN_INPUT_REQUIRED" && request.options.isNotEmpty()
    var sent = 0
    targets.forEach { chatId ->
      val sentMessage = if (withButtons) {
        sender.sendQuestion(chatId, request.flowId, request.text, request.options)
      } else {
        sender.sendText(chatId, request.text)
      }
      if (sentMessage == null) {
        // ожидаемо: юзер не жал /start → Bot API 403; едем к следующему адресату
        logger.warn { "send failed, skipping chat: chatId=$chatId event=${request.event}" }
      } else {
        sent++
        if (withButtons) {
          pendingStore.register(chatId, sentMessage.messageId, PendingQuestion(request.flowId, request.options))
        }
      }
    }

    if (sent == 0) {
      call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "All sends failed", "targets" to targets.size))
      return@post
    }
    logger.info { "notification delivered: event=${request.event} flowId=${request.flowId} sent=$sent/${targets.size}" }
    call.respond(HttpStatusCode.Accepted, mapOf("sent" to sent, "targets" to targets.size))
  }
}

/**
 * Bounded-дедуп deliveryId: LinkedHashMap с eviction старейших (cap 10k —
 * с запасом на сутки уведомлений outbox, переживает рестарт прокси дёшево:
 * после рестарта дубль доставится повторно, main-клиенты идемпотентны).
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
