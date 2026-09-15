package org.jep21s.meetupflowagent.telegramproxy.telegram

import java.util.UUID

/** Отправленное ботом сообщение — (chatId, messageId) Enough для HITL-pending. */
data class SentTgMessage(val chatId: Long, val messageId: Long)

/**
 * Отправка в Telegram от имени бота. Реализация [MessengerBot] (Bot API),
 * в тестах и при выключенном боте — [DummyTgMessageSender].
 * null от send* — «не доставлено этому адресату» (403 и т.п.), остальные — едут дальше.
 */
interface TgMessageSender {
  suspend fun sendText(chatId: Long, text: String): SentTgMessage?

  /** HITL-вопрос: текст + inline-кнопки по опциям (callback_data "hitl:<flowId>:<index>"). */
  suspend fun sendQuestion(chatId: Long, flowId: UUID, text: String, options: List<String>): SentTgMessage?

  /** Тост-ответ на клик по кнопке; best-effort. */
  suspend fun answerCallback(callbackQueryId: String, text: String)

  /** Снять кнопки с отвеченного вопроса (защита от повторных кликов); best-effort. */
  suspend fun editQuestionAnswered(chatId: Long, messageId: Long)
}
