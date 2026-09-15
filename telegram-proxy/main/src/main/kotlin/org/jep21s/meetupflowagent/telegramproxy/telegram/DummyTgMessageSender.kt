package org.jep21s.meetupflowagent.telegramproxy.telegram

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID

private val logger = KotlinLogging.logger { }

/**
 * Заглушка вместо бота: тесты и локальный запуск при telegram.bot.enabled=false
 * (kill-switch §6.1 плана). Ничего не отправляет, «доставку» засчитывает —
 * /api/notify отвечает 202, локально можно гонять весь флоу без Telegram.
 */
class DummyTgMessageSender : TgMessageSender {

  override suspend fun sendText(chatId: Long, text: String): SentTgMessage {
    logger.warn { "telegram bot disabled, skipping sendText to chat $chatId: ${text.take(120)}" }
    return SentTgMessage(chatId, -1)
  }

  override suspend fun sendQuestion(
    chatId: Long,
    flowId: UUID,
    text: String,
    options: List<String>,
  ): SentTgMessage {
    logger.warn { "telegram bot disabled, skipping sendQuestion to chat $chatId: flowId=$flowId options=$options" }
    return SentTgMessage(chatId, -1)
  }

  override suspend fun answerCallback(callbackQueryId: String, text: String) {
    logger.warn { "telegram bot disabled, skipping answerCallback: $callbackQueryId" }
  }

  override suspend fun editQuestionAnswered(chatId: Long, messageId: Long) {
    logger.warn { "telegram bot disabled, skipping editQuestionAnswered: chatId=$chatId messageId=$messageId" }
  }
}
