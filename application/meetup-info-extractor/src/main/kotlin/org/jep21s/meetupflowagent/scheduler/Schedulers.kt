package org.jep21s.meetupflowagent.scheduler

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jep21s.meetupflowagent.db.FlowRepository
import org.jep21s.meetupflowagent.db.FlowStepRepository
import org.jep21s.meetupflowagent.db.InboxRepository
import org.jep21s.meetupflowagent.db.UsersRepository
import org.jep21s.meetupflowagent.flow.AgentFlowService
import org.jep21s.meetupflowagent.notify.ProxyNotification
import org.jep21s.meetupflowagent.notify.ProxyNotifier
import org.jep21s.meetupflowagent.observability.Metrics
import org.jep21s.meetupflowagent.outbox.OutboxDeliveryPoller
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.koin.core.annotation.Named
import org.koin.core.annotation.Singleton
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger { }

/** Шкала ретраев "1m,5m,15m,1h,6h" (§12); суффиксы s/m/h. */
object RetrySchedule {
  fun parse(raw: String): List<Duration> = raw.split(",").map { slot ->
    val value = slot.trim().dropLast(1).toLong()
    when (slot.trim().last()) {
      's' -> Duration.ofSeconds(value)
      'm' -> Duration.ofMinutes(value)
      'h' -> Duration.ofHours(value)
      else -> Duration.ofMinutes(value)
    }
  }

  fun nextDelay(schedule: List<Duration>, attempt: Int): Duration =
    schedule.getOrElse(attempt) { schedule.last() }
}

/**
 * Шедулеры на applicationCoroutineScope (§12): InboxPoller (клейм NEW-сообщений →
 * исполнение флоу), RetryPoller (WAITING_RETRY по расписанию → резюм),
 * HumanTimeoutPoller (REMINDER 24ч / EXPIRED 48ч), OutboxPoller (доставка
 * публикаций успешных результатов — [OutboxDeliveryPoller]). Kill-switch'и:
 * конфиг scheduler.<name>.enabled (ENV).
 */
@Singleton(createdAtStart = true)
class Schedulers(
  private val inboxRepository: InboxRepository,
  private val flowRepository: FlowRepository,
  private val flowStepRepository: FlowStepRepository,
  private val flowService: AgentFlowService,
  private val outboxPoller: OutboxDeliveryPoller,
  private val proxyNotifier: ProxyNotifier,
  private val metrics: Metrics,
  private val usersRepository: UsersRepository,
  @Named("applicationCoroutineScope") private val scope: CoroutineScope,
) {

  init {
    startInboxPoller()
    startRetryPoller()
    startHumanTimeoutPoller()
    startOutboxPoller()
  }

  private fun startInboxPoller() {
    scope.launch(Dispatchers.IO) {
      while (true) {
        try {
          if (ConfigLoader.getProperty("scheduler.inbox.enabled", "true").toBoolean()) {
            pollInboxOnce()
          }
        } catch (e: Exception) {
          logger.error(e) { "inbox poll cycle failed" }
        }
        delay(ConfigLoader.getProperty("scheduler.inbox.intervalSeconds", "5").toLong() * 1000)
      }
    }
  }

  internal suspend fun pollInboxOnce() {
    val batch = inboxRepository.claimBatch(
      ConfigLoader.getProperty("scheduler.inbox.batchSize", "10").toInt(),
    )
    if (batch.isEmpty()) return
    logger.info { "inbox claimed ${batch.size} messages" }
    val parallelism = ConfigLoader.getProperty("scheduler.inbox.parallelism", "4").toInt()
    val semaphore = Semaphore(parallelism)
    batch.forEach { inbox ->
      scope.launch(Dispatchers.IO) {
        semaphore.withPermit {
          try {
            val flowId = inbox.flowId ?: flowRepository.create("PROCESSING", inboxMessageId = inbox.id)
            flowService.executeInboxFlow(flowId, inbox.id, inbox.rawText)
            inboxRepository.markDone(inbox.id, flowId)
          } catch (e: Exception) {
            logger.error(e) { "inbox processing failed: inbox=${inbox.id}" }
          }
        }
      }
    }
  }

  private fun startRetryPoller() {
    scope.launch(Dispatchers.IO) {
      while (true) {
        try {
          if (ConfigLoader.getProperty("scheduler.retry.enabled", "true").toBoolean()) {
            pollRetryOnce()
          }
        } catch (e: Exception) {
          logger.error(e) { "retry poll cycle failed" }
        }
        delay(ConfigLoader.getProperty("scheduler.retry.intervalSeconds", "15").toLong() * 1000)
      }
    }
  }

  internal suspend fun pollRetryOnce() {
    val schedule = RetrySchedule.parse(ConfigLoader.getProperty("retry.schedule", "1m,5m,15m,1h,6h"))
    val maxAttempts = ConfigLoader.getProperty("retry.maxAttempts", "6").toInt()
    flowRepository.claimRetriableReady(Instant.now(), limit = 10).forEach { flowId ->
      val flow = flowRepository.findById(flowId) ?: return@forEach
      if ((flow.retryCount ?: 0) + 1 > maxAttempts) {
        flowRepository.markFailedPermanent(flowId, flow.lastError)
        proxyNotifier.notify(
          ProxyNotification(
            flowId = flowId,
            event = "FLOW_FAILED",
            userIds = usersRepository.activeTelegramUserIds(),
            text = "Флоу ${flowId} окончательно провален после $maxAttempts попыток: ${flow.lastError?.take(200)}",
          ),
        )
        return@forEach
      }
      val inboxId = flowRepository.findInboxMessageId(flowId)
      if (inboxId == null) {
        flowRepository.markFailedPermanent(flowId, "no inbox message to retry")
        return@forEach
      }
      val inbox = inboxRepository.findById(inboxId) ?: run {
        flowRepository.markFailedPermanent(flowId, "inbox message disappeared")
        return@forEach
      }
      logger.info { "retrying flow: id=$flowId attempt=${(flow.retryCount ?: 0) + 1}" }
      try {
        flowService.executeInboxFlow(flowId, inboxId, inbox.rawText)
        inboxRepository.markDone(inboxId, flowId)
      } catch (e: Exception) {
        logger.error(e) { "retry attempt failed: flow=$flowId" }
        flowRepository.markWaitingRetry(flowId, e.message, (flow.retryCount ?: 0) + 1, Instant.now().plus(schedule.getOrElse(flow.retryCount ?: 0) { schedule.last() }))
      }
    }
  }

  private fun startHumanTimeoutPoller() {
    scope.launch(Dispatchers.IO) {
      while (true) {
        try {
          if (ConfigLoader.getProperty("scheduler.humanTimeout.enabled", "true").toBoolean()) {
            pollHumanTimeoutOnce()
          }
        } catch (e: Exception) {
          logger.error(e) { "human timeout poll cycle failed" }
        }
        delay(ConfigLoader.getProperty("scheduler.humanTimeout.intervalSeconds", "3600").toLong() * 1000)
      }
    }
  }

  internal suspend fun pollHumanTimeoutOnce() {
    val reminderAfter = Duration.ofHours(ConfigLoader.getProperty("human.reminderHours", "24").toLong())
    val expireAfter = Duration.ofHours(ConfigLoader.getProperty("human.timeoutHours", "48").toLong())
    val now = Instant.now()
    flowRepository.findWaitingHumanOlderThan(now.minus(reminderAfter)).forEach { flowId ->
      val expired = now.minus(expireAfter)
      val askedBefore = flowRepository.findWaitingHumanOlderThan(expired)
      if (flowId in askedBefore) {
        flowRepository.expireHumanRequest(flowId)
        proxyNotifier.notify(
          ProxyNotification(
            flowId = flowId,
            event = "FLOW_FAILED",
            userIds = usersRepository.activeTelegramUserIds(),
            text = "Ответ человека не получен за ${expireAfter.toHours()}ч — флоу $flowId закрыт (EXPIRED)",
          ),
        )
      } else {
        val alreadyReminded = flowStepRepository.hasStatusFlag(flowId, "REMINDER_SENT")
        if (!alreadyReminded) {
          flowStepRepository.appendStatusFlag(flowId, "REMINDER_SENT")
          proxyNotifier.notify(
            ProxyNotification(
              flowId = flowId,
              event = "REMINDER",
              userIds = usersRepository.activeTelegramUserIds(),
              text = "Ожидается ответ по флоу $flowId (вопрос задан более ${reminderAfter.toHours()}ч назад)",
            ),
          )
        }
      }
    }
  }

  private fun startOutboxPoller() {
    scope.launch(Dispatchers.IO) {
      while (true) {
        try {
          if (ConfigLoader.getProperty("scheduler.outbox.enabled", "true").toBoolean()) {
            outboxPoller.pollOnce()
          }
        } catch (e: Exception) {
          logger.error(e) { "outbox poll cycle failed" }
        }
        delay(ConfigLoader.getProperty("scheduler.outbox.intervalSeconds", "5").toLong() * 1000)
      }
    }
  }
}
