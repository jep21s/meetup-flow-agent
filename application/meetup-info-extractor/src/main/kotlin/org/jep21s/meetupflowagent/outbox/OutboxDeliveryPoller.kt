package org.jep21s.meetupflowagent.outbox

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jep21s.meetupflowagent.db.OutboxRepository
import org.jep21s.meetupflowagent.observability.Metrics
import org.jep21s.meetupflowagent.scheduler.RetrySchedule
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.koin.core.annotation.Named
import org.koin.core.annotation.Singleton
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger { }

/**
 * Доставка outbox-публикаций (один такт = [pollOnce]): crash-recovery зависших
 * SENDING → клейм батча PENDING (SKIP LOCKED) → транспорт по destination.type;
 * неудача — ретраи по шкале outbox.schedule, после outbox.maxAttempts —
 * FAILED_PERMANENT (флоу не затрагивается). Публичный класс: цикл/kill-switch
 * держит Schedulers, логика доставки тестируется отсюда.
 */
@Singleton
class OutboxDeliveryPoller(
  private val outboxRepository: OutboxRepository,
  transports: List<OutboxTransport>,
  private val metrics: Metrics,
  @Named("applicationCoroutineScope") private val scope: CoroutineScope,
) {

  private val transportsByType: Map<String, OutboxTransport> = transports.associateBy { it.type }
  private val depthGauge = metrics.outboxDepth()

  suspend fun pollOnce() {
    val stuckMinutes = ConfigLoader.getProperty("outbox.stuckSendingMinutes", "5").toLong()
    val reset = outboxRepository.resetStuck(Instant.now().minus(Duration.ofMinutes(stuckMinutes)))
    if (reset > 0) logger.warn { "outbox crash-recovery: reset $reset stuck SENDING deliveries" }

    val schedule = RetrySchedule.parse(ConfigLoader.getProperty("outbox.schedule", "1m,5m,15m,1h,6h"))
    val maxAttempts = ConfigLoader.getProperty("outbox.maxAttempts", "6").toInt()
    val batch = outboxRepository.claimPending(
      ConfigLoader.getProperty("scheduler.outbox.batchSize", "10").toInt(),
    )
    depthGauge.set(outboxRepository.pendingDepth())
    if (batch.isEmpty()) return

    val semaphore = Semaphore(ConfigLoader.getProperty("scheduler.outbox.parallelism", "4").toInt())
    batch.forEach { task ->
      scope.launch(Dispatchers.IO) {
        semaphore.withPermit {
          val transport = transportsByType[task.destination.type]
          if (transport == null) {
            outboxRepository.markFailedPermanent(
              task.deliveryId, task.attempts + 1, "UNSUPPORTED_TRANSPORT type=${task.destination.type}",
            )
            metrics.outboxDelivery("unsupported", task.destination.name)
            return@withPermit
          }
          val startedAt = System.nanoTime()
          try {
            transport.deliver(task)
            outboxRepository.markSent(task.deliveryId)
            metrics.outboxDelivery("sent", task.destination.name)
            logger.info { "outbox delivered: delivery=${task.deliveryId} destination=${task.destination.name} flow=${task.payload.flowId}" }
          } catch (e: Exception) {
            val attempts = task.attempts + 1
            val error = e.message ?: "delivery failed"
            if (attempts >= maxAttempts) {
              outboxRepository.markFailedPermanent(task.deliveryId, attempts, error)
              metrics.outboxDelivery("failed_permanent", task.destination.name)
            } else {
              val nextRetryAt = Instant.now().plus(RetrySchedule.nextDelay(schedule, attempts - 1))
              outboxRepository.markRetry(task.deliveryId, attempts, nextRetryAt, error)
              metrics.outboxDelivery("retry", task.destination.name)
              logger.warn { "outbox delivery failed (attempt $attempts/$maxAttempts): delivery=${task.deliveryId} error=${error.take(200)}" }
            }
          } finally {
            metrics.outboxDeliveryLatency(task.destination.name, Duration.ofNanos(System.nanoTime() - startedAt))
          }
        }
      }
    }
  }
}
