package org.jep21s.meetupflowagent.telegramproxy.telegram

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.jep21s.meetupflowagent.telegramproxy.integration.AgentCallResult
import org.jep21s.meetupflowagent.telegramproxy.integration.MeetupFlowAgent
import org.koin.core.annotation.Named
import org.koin.core.annotation.Singleton
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger { }

private const val CALLBACK_PREFIX = "hitl:"

/**
 * Входящий поток Telegram (§5 плана): коллектор [UpdateEventRelay] на scope.
 * Порядок обработки апдейта:
 * 1. callbackQuery — клик по кнопке HITL-вопроса → submitHumanResponse(опция);
 * 2. reply на pending-вопрос → submitHumanResponse(текст ответа);
 * 3. message/channelPost: команды `/...` — локальные (приветствие /start),
 *    прочее — сырой passthrough в агента: text = JSON всей DTO Update;
 * 4. остальные типы апдейтов — debug-лог, skip.
 */
@Singleton(createdAtStart = true)
class IncomingTgMessageHandler(
  private val agentClient: MeetupFlowAgent,
  private val sender: TgMessageSender,
  private val pendingStore: HitlPendingStore,
  @Named("applicationCoroutineScope") private val scope: CoroutineScope,
) {

  init {
    scope.launch {
      UpdateEventRelay.getUpdateEventFlow().collect { update ->
        try {
          handle(update)
        } catch (e: Exception) {
          logger.error(e) { "update handling failed: updateId=${update.updateId}" }
        }
      }
    }
  }

  internal suspend fun handle(update: Update) {
    val callback = update.callbackQuery
    if (callback != null) {
      handleCallback(callback)
      return
    }

    val message: Message? = update.message ?: update.channelPost
    if (message == null) {
      logger.debug { "skip update without message/channelPost: updateId=${update.updateId}" }
      return
    }
    val text = message.text
    if (text.isNullOrBlank()) {
      logger.debug { "skip non-text update: updateId=${update.updateId}" }
      return
    }
    val chatId = message.chat?.id ?: return

    // команды бота — локальные, агенту не нужны
    if (text.startsWith("/")) {
      handleCommand(chatId, text)
      return
    }

    // reply на заданный HITL-вопрос → ответ человека флоу-владельцу
    val replyTo = message.replyToMessage
    if (replyTo != null) {
      val pending = pendingStore.find(chatId, replyTo.messageId.toLong())
      if (pending != null) {
        submitAnswer(
          flowId = pending.flowId,
          responderUserId = message.from?.id ?: 0L,
          answer = text,
          chatId = chatId,
          questionMessageId = replyTo.messageId.toLong(),
          callbackQueryId = null,
        )
        return
      }
    }

    // сырой passthrough: прокси НЕ разбирает содержимое — всю DTO Update как text
    val result = agentClient.postMessage(
      idempotencyKey = "tg-${update.updateId}",
      text = jacksonMapper.writeValueAsString(update),
      meta = buildMeta(message),
    )
    when (result) {
      is AgentCallResult.Failure ->
        logger.warn { "passthrough failed: updateId=${update.updateId} chatId=$chatId" }
      else -> logger.debug { "passthrough done: updateId=${update.updateId} result=$result" }
    }
  }

  private suspend fun handleCommand(chatId: Long, text: String) {
    logger.debug { "bot command: chatId=$chatId text=${text.take(50)}" }
    if (text.startsWith("/start")) {
      sender.sendText(
        chatId,
        "Привет! Я бот-прокси meetup-flow-agent: пересылаю сообщения агенту, " +
          "задаю его вопросы людям и публикую анонсы. Просто напиши мне что-нибудь.",
      )
    }
  }

  private suspend fun handleCallback(callback: CallbackQuery) {
    val data = callback.data
    if (data == null || !data.startsWith(CALLBACK_PREFIX)) {
      logger.debug { "skip callback without hitl data: id=${callback.id}" }
      return
    }
    // формат "hitl:<flowId>:<index>"
    val parts = data.split(":")
    val flowId = parts.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    val optionIndex = parts.getOrNull(2)?.toIntOrNull()
    // MaybeInaccessibleMessage не отдаёт chat/messageId; pending живёт ≤24ч,
    // все актуальные вопросы — обычные Message (недоступные = уже неактуальны)
    val cbMessage = callback.message
    if (flowId == null || optionIndex == null || cbMessage !is Message) {
      logger.warn { "malformed/inaccessible hitl callback: data=$data" }
      if (cbMessage !is Message) sender.answerCallback(callback.id, "Вопрос уже неактуален")
      return
    }
    val chatId = cbMessage.chat?.id
    val messageId = cbMessage.messageId
    if (chatId == null || messageId == null) {
      logger.warn { "callback without chat/messageId: data=$data" }
      return
    }
    val pending = pendingStore.find(chatId, messageId.toLong())
    if (pending == null || pending.flowId != flowId) {
      sender.answerCallback(callback.id, "Вопрос уже неактуален")
      return
    }
    val answer = pending.options.getOrNull(optionIndex)
    if (answer == null) {
      sender.answerCallback(callback.id, "Неизвестная опция")
      return
    }
    submitAnswer(flowId, callback.from.id, answer, chatId, messageId.toLong(), callback.id)
  }

  /**
   * Отправка ответа агенту: Accepted/Duplicate → pending снят по flowId у всех
   * адресатов (первый ответ побеждает), кнопки убраны, юзеру — подтверждение.
   */
  private suspend fun submitAnswer(
    flowId: UUID,
    responderUserId: Long,
    answer: String,
    chatId: Long,
    questionMessageId: Long,
    callbackQueryId: String?,
  ) {
    when (agentClient.submitHumanResponse(flowId, responderUserId, answer)) {
      is AgentCallResult.Accepted, is AgentCallResult.Duplicate -> {
        pendingStore.removeFlow(flowId)
        sender.editQuestionAnswered(chatId, questionMessageId)
        if (callbackQueryId != null) {
          sender.answerCallback(callbackQueryId, "Ответ принят")
        } else {
          sender.sendText(chatId, "✅ Ответ принят: $answer")
        }
      }
      is AgentCallResult.Failure ->
        callbackQueryId?.let { sender.answerCallback(it, "Ошибка отправки, попробуйте позже") }
    }
  }

  private fun buildMeta(message: Message): Map<String, Any?> = mapOf(
    "authorUsername" to (message.from?.userName ?: message.from?.id?.toString() ?: "unknown"),
    "chatTitle" to (message.chat?.title ?: message.chat?.type ?: "unknown"),
    "receivedAt" to Instant.ofEpochSecond((message.date ?: 0).toLong()).toString(),
  )
}
