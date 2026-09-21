package org.jep21s.meetupflowagent.notify

import java.util.UUID

/** Событие HITL-вопроса: единственное с options; доставляется только в личные чаты активных users. */
const val EVENT_HUMAN_INPUT_REQUIRED = "HUMAN_INPUT_REQUIRED"

/** Флоу завершён вердиктом NEEDS_REVIEW (данные неполны/сомнение в дубле) — уведомить людей. */
const val EVENT_FLOW_NEEDS_REVIEW = "FLOW_NEEDS_REVIEW"

/** Флоу отклонён (не СПб/платное/online-only/guardrails) — уведомить людей. */
const val EVENT_FLOW_REJECTED = "FLOW_REJECTED"

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
