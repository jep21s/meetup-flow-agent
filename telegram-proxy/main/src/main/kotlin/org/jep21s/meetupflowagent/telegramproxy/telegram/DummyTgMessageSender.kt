package org.jep21s.meetupflowagent.telegramproxy.telegram

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID

private val logger = KotlinLogging.logger { }

/**
 * Заглушка вместо бота: тесты и локальный запуск при telegram.bot.enabled=false
 * (kill-switch). Ничего не отправляет, «доставку» засчитывает.
 */
class DummyTgMessageSender : TgMessageSender {

  override suspend fun sendText(chatId: Long, text: String): SentTgMessage {
    logger.warn { "telegram bot disabled, skipping sendText to chat $chatId: ${text.take(120)}" }
    return SentTgMessage(chatId, -1)
  }

  override suspend fun sendButtons(chatId: Long, text: String, buttons: List<SendButton>): SentTgMessage {
    logger.warn { "telegram bot disabled, skipping sendButtons to chat $chatId: buttons=${buttons.size}" }
    return SentTgMessage(chatId, -1)
  }

  override suspend fun answerCallback(callbackQueryId: String, text: String) {
    logger.warn { "telegram bot disabled, skipping answerCallback: $callbackQueryId" }
  }

  override suspend fun removeKeyboard(chatId: Long, messageId: Long) {
    logger.warn { "telegram bot disabled, skipping removeKeyboard: chatId=$chatId messageId=$messageId" }
  }
}
