package org.jep21s.meetupflowagent.telegramproxy.telegram

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.koin.core.annotation.Named
import org.koin.core.annotation.Singleton
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger { }

/** Заданный ботом HITL-вопрос: флоу-владелец и опции кнопок. */
data class PendingQuestion(
  val flowId: UUID,
  val options: List<String>,
  val createdAt: Instant = Instant.now(),
)

/**
 * In-memory реестр заданных HITL-вопросов: (chatId, messageId) вопроса →
 * PendingQuestion. По нему ответный callback/reply относят к флоу (первый
 * ответ побеждает — гарантирует main через submitAnswer). Обратный индекс
 * flowId → ключи нужен, чтобы после ответа снять кнопки у ВСЕХ адресатов.
 * TTL-очистка (hitl.pending.ttl-hours, default 24ч) — раз в час на scope.
 */
@Singleton
class HitlPendingStore(@Named("applicationCoroutineScope") private val scope: CoroutineScope) {

  private val byMessageKey = java.util.concurrent.ConcurrentHashMap<String, PendingQuestion>()
  private val flowKeys = java.util.concurrent.ConcurrentHashMap<UUID, MutableSet<String>>()

  init {
    scope.launch {
      while (true) {
        delay(Duration.ofHours(1).toMillis())
        try {
          sweep()
        } catch (e: Exception) {
          logger.error(e) { "hitl pending sweep failed" }
        }
      }
    }
  }

  fun register(chatId: Long, messageId: Long, pending: PendingQuestion) {
    val key = messageKey(chatId, messageId)
    byMessageKey[key] = pending
    flowKeys.computeIfAbsent(pending.flowId) { java.util.Collections.synchronizedSet(mutableSetOf()) }.add(key)
    logger.debug { "hitl pending registered: flowId=${pending.flowId} key=$key" }
  }

  fun find(chatId: Long, messageId: Long): PendingQuestion? = byMessageKey[messageKey(chatId, messageId)]

  /** Снимает все вопросы флоу (ответ получен или флоу закрыт). */
  fun removeFlow(flowId: UUID) {
    val keys = flowKeys.remove(flowId) ?: return
    keys.forEach { byMessageKey.remove(it) }
    logger.debug { "hitl pending removed: flowId=$flowId keys=${keys.size}" }
  }

  internal fun sweep() {
    val ttl = Duration.ofHours(ConfigLoader.getProperty("hitl.pending.ttl-hours", "24").toLong())
    val deadline = Instant.now().minus(ttl)
    val expired = byMessageKey.entries.filter { it.value.createdAt.isBefore(deadline) }
    expired.forEach { (key, pending) ->
      byMessageKey.remove(key)
      flowKeys[pending.flowId]?.remove(key)
    }
    if (expired.isNotEmpty()) {
      logger.info { "hitl pending sweep: removed ${expired.size} entries older than $ttl" }
    }
  }

  private fun messageKey(chatId: Long, messageId: Long) = "$chatId:$messageId"
}
