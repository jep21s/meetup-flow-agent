package org.jep21s.meetupflowagent.notify

import java.util.UUID

/** Уведомление telegram-слою (§11): событие флоу + текст + адресаты. */
data class ProxyNotification(
  val flowId: UUID,
  val event: String,
  val userIds: List<Long>,
  val text: String,
  val options: List<String> = emptyList(),
)

/**
 * Отправка уведомлений в Telegram. Реализация живёт в модуле application/telegram
 * (TelegramNotifier: адресация + HTTP-прокси); extractor знает только контракт —
 * пустой/несконфигурированный прокси трактуется реализацией как noop (dev).
 */
interface ProxyNotifier {
  suspend fun notify(notification: ProxyNotification)
}
