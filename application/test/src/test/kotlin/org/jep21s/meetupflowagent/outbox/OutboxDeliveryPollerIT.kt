package org.jep21s.meetupflowagent.outbox

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.jep21s.meetupflowagent.db.DestinationRepository
import org.jep21s.meetupflowagent.db.EventRepository
import org.jep21s.meetupflowagent.db.EventRow
import org.jep21s.meetupflowagent.db.FlowRepository
import org.jep21s.meetupflowagent.db.OutboxRepository
import org.jep21s.meetupflowagent.observability.Metrics
import org.jep21s.meetupflowagent.testsupport.PostgresTestBase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Такт доставки outbox (реальный Postgres, фейковые транспорты): успех → SENT,
 * неудача → PENDING с расписанием, исчерпание попыток и неизвестный транспорт →
 * FAILED_PERMANENT, метрики по исходам.
 */
class OutboxDeliveryPollerIT : PostgresTestBase() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val metrics = Metrics.inMemory()
  private val eventRepository = EventRepository(testConnectivity())
  private val flowRepository = FlowRepository(testConnectivity())
  private val outboxRepository = OutboxRepository(
    testConnectivity(),
    DestinationRepository(testConnectivity()),
    eventRepository,
  )

  /** Транспорт с переключаемым исходом; telegram_proxy, чтобы забирать сеянное назначение. */
  private class FlakyTransport(val failTimes: Int) : OutboxTransport {
    override val type = "telegram_proxy"
    val calls = AtomicInteger(0)
    override suspend fun deliver(task: OutboxDeliveryTask) {
      if (calls.incrementAndGet() <= failTimes) throw OutboxDeliveryException("HTTP 503")
    }
  }

  @AfterEach
  fun tearDown() {
    scope.cancel()
    System.clearProperty("outbox.maxAttempts")
    System.clearProperty("outbox.schedule")
    System.clearProperty("outbox.stuckSendingMinutes")
  }

  private suspend fun enqueuePublication(flowId: UUID): UUID {
    val event = EventRow(
      id = UUID.randomUUID(),
      flowId = flowId,
      title = "SPb Go #20",
      startsAt = Instant.parse("2026-10-15T16:00:00Z"),
      city = "Санкт-Петербург",
    )
    return outboxRepository.insertEventAndEnqueue(event).messageId
  }

  private fun poller(transport: OutboxTransport): OutboxDeliveryPoller =
    OutboxDeliveryPoller(outboxRepository, listOf(transport), metrics, scope)

  @Test
  fun `successful delivery marks SENT and counts metric`() = runBlocking {
    val flowId = flowRepository.create("PROCESSING")
    enqueuePublication(flowId)
    val transport = FlakyTransport(failTimes = 0)

    poller(transport).pollOnce()

    // доставка асинхронна на scope — ждём SENT
    awaitDelivery(flowId) { it.status == "SENT" }
    assertThat(transport.calls.get()).isEqualTo(1)
    assertThat(counter("sent")).isEqualTo(1.0)
    assertThat(counter("retry")).isEqualTo(0.0)
  }

  @Test
  fun `failing delivery goes back to PENDING with attempts and future retry`() = runBlocking {
    System.setProperty("outbox.schedule", "1m")
    val flowId = flowRepository.create("PROCESSING")
    enqueuePublication(flowId)

    poller(FlakyTransport(failTimes = 1)).pollOnce()
    awaitDelivery(flowId) { it.status == "PENDING" }

    val delivery = outboxRepository.deliveriesByFlow(flowId).single()
    assertThat(delivery.attempts).isEqualTo(1)
    assertThat(delivery.lastError).isEqualTo("HTTP 503")
    assertThat(delivery.nextRetryAt).isAfter(Instant.now())
    assertThat(counter("retry")).isEqualTo(1.0)
  }

  @Test
  fun `exhausted attempts mark delivery FAILED_PERMANENT`() = runBlocking {
    System.setProperty("outbox.maxAttempts", "2")
    System.setProperty("outbox.schedule", "1s")
    val flowId = flowRepository.create("PROCESSING")
    enqueuePublication(flowId)
    val poller = poller(FlakyTransport(failTimes = 10))

    poller.pollOnce()
    awaitDelivery(flowId) { it.status == "PENDING" }

    // next_retry_at через 1s — ждём и забираем вторую (последнюю) попытку
    Thread.sleep(1_500)
    poller.pollOnce()
    awaitDelivery(flowId) { it.status == "FAILED_PERMANENT" }

    val delivery = outboxRepository.deliveriesByFlow(flowId).single()
    assertThat(delivery.attempts).isEqualTo(2)
    assertThat(delivery.lastError).isEqualTo("HTTP 503")
    assertThat(counter("failed_permanent")).isEqualTo(1.0)
  }

  @Test
  fun `unknown transport type fails permanently with UNSUPPORTED_TRANSPORT`() = runBlocking {
    executeSql("UPDATE destinations SET type = 'google_calendar' WHERE name = 'telegram_main'")
    val flowId = flowRepository.create("PROCESSING")
    enqueuePublication(flowId)

    poller(FlakyTransport(failTimes = 0)).pollOnce() // type ≠ telegram_proxy → транспорта нет

    awaitDelivery(flowId) { it.status == "FAILED_PERMANENT" }
    val delivery = outboxRepository.deliveriesByFlow(flowId).single()
    assertThat(delivery.lastError).contains("UNSUPPORTED_TRANSPORT")
    assertThat(delivery.lastError).contains("google_calendar")
    assertThat(counter("unsupported")).isEqualTo(1.0)
  }

  private fun awaitDelivery(flowId: UUID, condition: (org.jep21s.meetupflowagent.db.DeliveryStateRow) -> Boolean) {
    val deadline = System.currentTimeMillis() + 10_000
    while (System.currentTimeMillis() < deadline) {
      val delivery = runBlocking { outboxRepository.deliveriesByFlow(flowId).singleOrNull() }
      if (delivery != null && condition(delivery)) return
      Thread.sleep(100)
    }
    error("delivery did not reach expected state in 10s: " + runBlocking { outboxRepository.deliveriesByFlow(flowId) })
  }

  private fun counter(status: String): Double =
    metrics.registryForTests().find("meetup_outbox_deliveries_total").tag("status", status).counter()?.count() ?: 0.0

  private fun executeSql(sql: String) {
    org.jetbrains.exposed.v1.jdbc.transactions.transaction(database) { exec(sql) }
  }
}
