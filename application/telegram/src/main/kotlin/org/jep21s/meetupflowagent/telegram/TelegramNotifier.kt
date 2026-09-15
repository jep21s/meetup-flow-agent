package org.jep21s.meetupflowagent.telegram

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jep21s.meetupflowagent.notify.ProxyNotification
import org.jep21s.meetupflowagent.notify.ProxyNotifier
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.jep21s.meetupflowagent.telegram.db.TelegramQuestionRepository
import org.jep21s.meetupflowagent.telegram.integration.TelegramProxyClient
import org.jep21s.meetupflowagent.telegram.integration.TgButton
import org.koin.core.annotation.Singleton

private val logger = KotlinLogging.logger { }

/**
 * Исходящие уведомления агента → Telegram через прокси. Вся адресация здесь
 * (раньше решал telegram-proxy в /api/notify): userIds непуст → личные чаты,
 * пусто → общий канал (telegram.main.chat-id). HUMAN_INPUT_REQUIRED идёт с
 * кнопками (callback_data "hitl:<flowId>:<index>") и регистрируется в
 * telegram_questions для ответов reply-ом и снятия кнопок.
 *
 * Fire-and-forget, как прежний HttpProxyNotifier: сбой доставки логируется,
 * но не ломает флоу (для публикаций есть outbox с ретраями — TelegramOutboxTransport).
 */
@Singleton(binds = [ProxyNotifier::class])
class TelegramNotifier(
  private val proxyClient: TelegramProxyClient,
  private val questionRepository: TelegramQuestionRepository,
) : ProxyNotifier {

  private val mainChatId = ConfigLoader.getProperty("telegram.main.chat-id").trim().toLongOrNull()

  override suspend fun notify(notification: ProxyNotification) {
    if (!proxyClient.configured) {
      logger.debug { "proxy.baseUrl empty — notification skipped: ${notification.event} flowId=${notification.flowId}" }
      return
    }
    val targets = notification.userIds.ifEmpty { mainChatId?.let { listOf(it) } ?: emptyList() }
    if (targets.isEmpty()) {
      logger.warn { "no target chats for notification: event=${notification.event} flowId=${notification.flowId}" }
      return
    }

    val withButtons = notification.event == "HUMAN_INPUT_REQUIRED" && notification.options.isNotEmpty()
    var sent = 0
    targets.forEach { chatId ->
      val messageId = if (withButtons) {
        proxyClient.sendQuestion(
          chatId = chatId,
          text = notification.text,
          buttons = notification.options.mapIndexed { idx, option ->
            TgButton(option, "hitl:${notification.flowId}:$idx")
          },
        )
      } else {
        proxyClient.sendText(chatId, notification.text)
      }
      if (messageId == null) {
        // ожидаемо: юзер не жал /start → Bot API 403; едем к следующему адресату
        logger.warn { "send failed, skipping chat: chatId=$chatId event=${notification.event}" }
      } else {
        sent++
        if (withButtons) {
          questionRepository.register(notification.flowId, chatId, messageId, notification.options)
        }
      }
    }
    logger.info { "notification delivered: event=${notification.event} flowId=${notification.flowId} sent=$sent/${targets.size}" }
  }
}
