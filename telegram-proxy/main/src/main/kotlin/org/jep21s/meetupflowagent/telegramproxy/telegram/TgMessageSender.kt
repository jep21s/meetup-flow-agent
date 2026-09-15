package org.jep21s.meetupflowagent.telegramproxy.telegram

/** Кнопка под сообщением; callback_data формирует ОСНОВНОЙ сервис (модуль telegram). */
data class SendButton(val text: String, val callbackData: String)

/** Отправленное ботом сообщение: (chatId, messageId). */
data class SentTgMessage(val chatId: Long, val messageId: Long)

/**
 * Отправка в Telegram от имени бота — единственная «логика» прокси.
 * Реализация [MessengerBot] (Bot API); при выключенном боте — [DummyTgMessageSender].
 * null от send* — «не доставлено этому адресату» (403 и т.п.).
 */
interface TgMessageSender {
  suspend fun sendText(chatId: Long, text: String): SentTgMessage?

  /** Текст + inline-кнопки (HITL-вопрос; вид и callback_data задаёт основной сервис). */
  suspend fun sendButtons(chatId: Long, text: String, buttons: List<SendButton>): SentTgMessage?

  /** Тост-ответ на клик по кнопке; best-effort. */
  suspend fun answerCallback(callbackQueryId: String, text: String)

  /** Снять inline-кнопки с сообщения; best-effort. */
  suspend fun removeKeyboard(chatId: Long, messageId: Long)
}
