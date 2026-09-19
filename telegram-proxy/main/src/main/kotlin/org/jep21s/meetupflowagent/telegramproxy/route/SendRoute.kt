package org.jep21s.meetupflowagent.telegramproxy.route

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.jep21s.meetupflowagent.telegramproxy.telegram.SendButton
import org.jep21s.meetupflowagent.telegramproxy.telegram.TgMessageSender

private val logger = KotlinLogging.logger { }

/** POST /api/send: {chatId, text, buttons?} — отправка от имени бота. */
data class SendRequestDto(
  val chatId: Long,
  val text: String,
  val buttons: List<SendButtonDto> = emptyList(),
) {
  data class SendButtonDto(val text: String, val callbackData: String)
}

data class CallbackAnswerDto(val callbackQueryId: String, val text: String)

data class KeyboardRemoveDto(val chatId: Long, val messageId: Long)

/**
 * Исходящие команды основного сервиса (модуль application/telegram решает ЧТО
 * и КОМУ, прокси только отправляет): Bearer {proxy.token}.
 *
 * - POST /api/send — текст (+ опц. кнопки) → `202 {messageId}`; все попытки
 *   упали → 500 (вызывающая сторона решает, ретраить ли);
 * - POST /api/callback-answer — тост на клик по кнопке (202, best-effort);
 * - POST /api/message-keyboard-remove — снять кнопки (202, best-effort).
 */
fun Route.send(sender: TgMessageSender) {
  post("/send") {
    val request = call.receiveBody(SendRequestDto::class.java) ?: return@post
    val sent = if (request.buttons.isEmpty()) {
      sender.sendText(request.chatId, request.text)
    } else {
      sender.sendButtons(request.chatId, request.text, request.buttons.map { SendButton(it.text, it.callbackData) })
    }
    if (sent == null) {
      call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Send failed"))
    } else {
      call.respond(HttpStatusCode.Accepted, mapOf("messageId" to sent.messageId))
    }
  }

  post("/callback-answer") {
    val request = call.receiveBody(CallbackAnswerDto::class.java) ?: return@post
    sender.answerCallback(request.callbackQueryId, request.text)
    call.respond(HttpStatusCode.Accepted, mapOf("accepted" to true))
  }

  post("/message-keyboard-remove") {
    val request = call.receiveBody(KeyboardRemoveDto::class.java) ?: return@post
    sender.removeKeyboard(request.chatId, request.messageId)
    call.respond(HttpStatusCode.Accepted, mapOf("accepted" to true))
  }
}

/** Тело JSON или 400; null = уже отвечено. */
private suspend fun <T : Any> ApplicationCall.receiveBody(type: Class<T>): T? = try {
  jacksonMapper.readValue(receiveText(), type)
} catch (e: Exception) {
  respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON body: ${e.message}"))
  null
}
