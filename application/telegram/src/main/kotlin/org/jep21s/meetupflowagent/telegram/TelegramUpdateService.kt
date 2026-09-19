package org.jep21s.meetupflowagent.telegram

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jep21s.meetupflowagent.db.FlowRepository
import org.jep21s.meetupflowagent.db.HumanRequestRepository
import org.jep21s.meetupflowagent.db.InboxRepository
import org.jep21s.meetupflowagent.db.UsersRepository
import org.jep21s.meetupflowagent.flow.AgentFlowService
import org.jep21s.meetupflowagent.flow.FlowStatus
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.jep21s.meetupflowagent.telegram.db.TelegramQuestionRepository
import org.jep21s.meetupflowagent.telegram.integration.TelegramProxyClient
import org.koin.core.annotation.Named
import org.koin.core.annotation.Singleton
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import java.util.UUID

private val logger = KotlinLogging.logger { }

private const val CALLBACK_PREFIX = "hitl:"

/**
 * Мозг входящего Telegram-потока (раньше жил в telegram-proxy): принимает сырой
 * JSON апдейта от прокси и решает, что с ним делать.
 *
 * 1. callbackQuery "hitl:<flowId>:<idx>" — клик по кнопке HITL-вопроса →
 *    ответ опцией в human_requests + резюм флоу (первый побеждает);
 * 2. reply на заданный вопрос (telegram_questions) — свободный текст как ответ;
 *    автор ответа (оба пути) проходит allowlist — активные `users`;
 * 3. команды `/...` — локальные (приветствие /start), агенту не идут;
 * 4. message/channelPost из источника (telegram.source.chat-id + topic-id) —
 *    сырой passthrough: ВСЯ DTO Update как text в inbox (idempotencyKey
 *    "tg-<updateId>") + флоу PROCESSING — дальше работает meetup-info-extractor;
 *    не-текстовые сообщения (медиа с caption и т.п.) НЕ отсеиваются — текст
 *    в любом поле ищет модель;
 * 5. прочее (чужие чаты/топики) — игнор.
 */
@Singleton
class TelegramUpdateService(
  private val inboxRepository: InboxRepository,
  private val flowRepository: FlowRepository,
  private val humanRequestRepository: HumanRequestRepository,
  private val questionRepository: TelegramQuestionRepository,
  private val usersRepository: UsersRepository,
  private val proxyClient: TelegramProxyClient,
  private val flowService: AgentFlowService,
  @Named("applicationCoroutineScope") private val scope: CoroutineScope,
) {

  private val sourceChatId = ConfigLoader.getProperty("telegram.source.chat-id").trim().toLongOrNull()
  private val sourceTopicId = ConfigLoader.getProperty("telegram.source.topic-id").trim().toLongOrNull()

  suspend fun handle(rawUpdateJson: String) {
    val update = try {
      jacksonMapper.readValue(rawUpdateJson, Update::class.java)
    } catch (e: Exception) {
      logger.warn(e) { "cannot parse telegram update, ignored: ${rawUpdateJson.take(200)}" }
      return
    }

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
    val chatId = message.chat?.id ?: return

    // команды и HITL-ответы имеют смысл только у текстовых сообщений; медиа
    // (фото с caption и т.п.) проходит дальше в сырой passthrough — модель
    // получает весь апдейт как есть и сама находит текст в любом поле
    if (text != null && text.startsWith("/")) {
      handleCommand(chatId, text)
      return
    }

    // reply на заданный HITL-вопрос → свободный текст как ответ флоу-владельцу
    val replyTo = message.replyToMessage
    if (replyTo != null && text != null) {
      val pending = questionRepository.findAsked(chatId, replyTo.messageId.toLong())
      if (pending != null) {
        submitAnswer(pending.flowId, message.from?.id ?: 0L, text, pending.chatId, pending.messageId, callbackQueryId = null)
        return
      }
    }

    // сырой passthrough — только из заданной группы/топика; прочие чаты игнорируются
    if (!isFromSourceChatTopic(message)) {
      logger.info {
        "message outside source chat/topic — ignored: updateId=${update.updateId} " +
          "chatId=$chatId threadId=${message.messageThreadId}"
      }
      return
    }
    val result = inboxRepository.insertIfAbsent(
      idempotencyKey = "tg-${update.updateId}",
      rawText = rawUpdateJson,
      sourceMeta = null,
    )
    when (result) {
      is InboxRepository.InsertResult.Inserted -> {
        val flowId = flowRepository.create(FlowStatus.PROCESSING.name, inboxMessageId = result.id)
        inboxRepository.attachFlow(result.id, flowId)
        logger.info { "update accepted as extraction flow: updateId=${update.updateId} flowId=$flowId" }
      }
      is InboxRepository.InsertResult.Duplicate ->
        logger.info { "duplicate update ignored: updateId=${update.updateId} existingFlow=${result.existingFlowId}" }
    }
  }

  /** Клик по кнопке: callback_data "hitl:<flowId>:<index>"; опция — из telegram_questions. */
  private suspend fun handleCallback(callback: CallbackQuery) {
    val data = callback.data
    if (data == null || !data.startsWith(CALLBACK_PREFIX)) {
      logger.debug { "skip callback without hitl data: id=${callback.id}" }
      return
    }
    val parts = data.split(":")
    val flowId = parts.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    val optionIndex = parts.getOrNull(2)?.toIntOrNull()
    // MaybeInaccessibleMessage не отдаёт chat/messageId; вопросы живут ≤48ч (таймаут
    // HITL) — все актуальные вопросы обычные Message
    val cbMessage = callback.message
    if (flowId == null || optionIndex == null || cbMessage !is Message) {
      logger.warn { "malformed/inaccessible hitl callback: data=$data" }
      if (cbMessage !is Message) proxyClient.answerCallback(callback.id, "Вопрос уже неактуален")
      return
    }
    val chatId = cbMessage.chat?.id
    val messageId = cbMessage.messageId
    if (chatId == null || messageId == null) {
      logger.warn { "callback without chat/messageId: data=$data" }
      return
    }
    val pending = questionRepository.findAsked(chatId, messageId.toLong())
    if (pending == null || pending.flowId != flowId) {
      proxyClient.answerCallback(callback.id, "Вопрос уже неактуален")
      return
    }
    val answer = pending.options.getOrNull(optionIndex)
    if (answer == null) {
      proxyClient.answerCallback(callback.id, "Неизвестная опция")
      return
    }
    submitAnswer(flowId, callback.from.id, answer, chatId, messageId.toLong(), callback.id)
  }

  /**
   * Ответ человека: первый побеждает (human_requests.submitAnswer); резюм флоу —
   * в фоне на applicationCoroutineScope (цикл с LLM может быть долгим).
   * Автор проходит allowlist (активные users): чужой ответ отклоняется,
   * вопрос остаётся открытым — адресаты всё ещё могут ответить.
   * После ответа вопрос закрывается у всех адресатов (кнопки снимаются).
   */
  private suspend fun submitAnswer(
    flowId: UUID,
    responderUserId: Long,
    answer: String,
    chatId: Long,
    questionMessageId: Long,
    callbackQueryId: String?,
  ) {
    if (!usersRepository.isActiveUser(responderUserId)) {
      logger.warn { "hitl answer rejected — responder not in active users: flowId=$flowId userId=$responderUserId" }
      if (callbackQueryId != null) {
        proxyClient.answerCallback(callbackQueryId, "⛔ Не авторизован")
      } else {
        proxyClient.sendText(chatId, "⛔ Не авторизован: ответ не принят")
      }
      return
    }
    val won = humanRequestRepository.submitAnswer(flowId, responderUserId, answer)
    if (won) {
      scope.launch {
        try {
          flowService.resume(flowId, answer)
        } catch (e: Exception) {
          logger.error(e) { "flow resume failed: flowId=$flowId" }
        }
      }
    } else {
      logger.info { "hitl answer lost the race (already answered): flowId=$flowId" }
    }
    // сначала собираем незакрытые вопросы (для снятия кнопок), потом закрываем —
    // findAskedByFlow после resolveFlow вернул бы пустоту
    val askedMessages = questionRepository.findAskedByFlow(flowId)
    questionRepository.resolveFlow(flowId)
    askedMessages.forEach { asked ->
      proxyClient.removeKeyboard(asked.chatId, asked.messageId)
    }
    if (callbackQueryId != null) {
      proxyClient.answerCallback(callbackQueryId, if (won) "Ответ принят" else "Уже ответили")
    } else {
      proxyClient.sendText(chatId, if (won) "✅ Ответ принят: $answer" else "⌛ Уже ответили другим ответом")
    }
  }

  private suspend fun handleCommand(chatId: Long, text: String) {
    logger.debug { "bot command: chatId=$chatId text=${text.take(50)}" }
    if (text.startsWith("/start")) {
      proxyClient.sendText(
        chatId,
        "Привет! Я бот-прокси meetup-flow-agent: пересылаю сообщения агенту, " +
          "задаю его вопросы людям и публикую анонсы.",
      )
    }
  }

  /** Сообщение из заданного источника (группа/топик); незаданное ограничение не проверяется. */
  internal fun isFromSourceChatTopic(message: Message): Boolean {
    val chatOk = sourceChatId == null || message.chat?.id == sourceChatId
    val topicOk = sourceTopicId == null || message.messageThreadId?.toLong() == sourceTopicId
    return chatOk && topicOk
  }
}
