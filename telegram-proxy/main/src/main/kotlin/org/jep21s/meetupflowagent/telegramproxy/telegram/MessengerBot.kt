package org.jep21s.meetupflowagent.telegramproxy.telegram

import io.github.oshai.kotlinlogging.KotlinLogging
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import java.util.UUID

private val logger = KotlinLogging.logger { }

/**
 * Long-polling бот (registerBot в init — синглтон Koin создаётся при старте;
 * telegram.bot.enabled=false → этот класс вообще не инстанцируется, см.
 * TelegramProxyBeanConfig). Plain text без parse mode: анонсы с URL/эмодзи
 * не должны ломаться о Markdown.
 */
class MessengerBot(
  private val botToken: String,
  private val botUsername: String,
) : TelegramLongPollingBot(botToken), TgMessageSender {

  init {
    logger.info { "registering tg bot: username=$botUsername" }
    TelegramBotsApi(DefaultBotSession::class.java).registerBot(this)
  }

  override fun getBotToken(): String = botToken

  override fun getBotUsername(): String = botUsername

  override fun onUpdateReceived(update: Update) {
    UpdateEventRelay.accept(update)
  }

  override suspend fun sendText(chatId: Long, text: String): SentTgMessage? =
    executeSend(chatId, text, null)

  override suspend fun sendQuestion(chatId: Long, flowId: UUID, text: String, options: List<String>): SentTgMessage? {
    // одна опция = одна строка кнопок; callback_data "hitl:<uuid>:<idx>" ≤ 44 байт < 64
    val keyboard = InlineKeyboardMarkup().apply {
      keyboard = options.mapIndexed { idx, option ->
        listOf(
          InlineKeyboardButton(option).apply { callbackData = "hitl:$flowId:$idx" }
        )
      }
    }
    return executeSend(chatId, text, keyboard)
  }

  override suspend fun answerCallback(callbackQueryId: String, text: String) {
    try {
      execute(
        AnswerCallbackQuery().apply {
          setCallbackQueryId(callbackQueryId)
          setText(text)
        }
      )
    } catch (e: Exception) {
      logger.warn(e) { "answerCallback failed: id=$callbackQueryId" }
    }
  }

  override suspend fun editQuestionAnswered(chatId: Long, messageId: Long) {
    try {
      execute(
        EditMessageReplyMarkup().apply {
          this.chatId = chatId.toString()
          this.messageId = messageId.toInt()
        }
      )
    } catch (e: Exception) {
      logger.warn(e) { "editQuestionAnswered failed: chatId=$chatId messageId=$messageId" }
    }
  }

  private suspend fun executeSend(chatId: Long, text: String, keyboard: InlineKeyboardMarkup?): SentTgMessage? =
    try {
      val message: Message = execute(
        org.telegram.telegrambots.meta.api.methods.send.SendMessage(chatId.toString(), text).apply {
          replyMarkup = keyboard
        }
      )
      logger.debug { "sent to chat $chatId: messageId=${message.messageId}" }
      SentTgMessage(chatId, message.messageId.toLong())
    } catch (e: TelegramApiException) {
      logger.warn(e) { "send to chat $chatId failed: ${e.message}" }
      null
    }
}
