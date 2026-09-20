package org.jep21s.meetupflowagent.outbox

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jep21s.meetupflowagent.db.DestinationRepository
import org.jep21s.meetupflowagent.db.EventRepository
import org.jep21s.meetupflowagent.db.EventRow
import org.jep21s.meetupflowagent.db.FlowRepository
import org.jep21s.meetupflowagent.db.OutboxRepository
import org.jep21s.meetupflowagent.testsupport.PostgresTestBase
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Outbox-репозиторий (реальный Postgres): атомарная запись «событие + публикация
 * + fan-out доставок», клейм SKIP LOCKED, статусные переходы доставок, инспекция.
 */
class OutboxRepositoryIT : PostgresTestBase() {

  private val eventRepository = EventRepository(testConnectivity())
  private val flowRepository = FlowRepository(testConnectivity())
  private val outboxRepository = OutboxRepository(
    testConnectivity(),
    DestinationRepository(testConnectivity()),
    eventRepository,
  )

  private fun approvedEvent(flowId: UUID) = EventRow(
    id = UUID.randomUUID(),
    flowId = flowId,
    title = "PiterJS #61",
    description = "Доклады про Node.js",
    organizer = "PiterJS",
    city = "Санкт-Петербург",
    isFree = true,
    formats = listOf("OFFLINE"),
    address = "Пироговская наб. 21",
    venueName = "Place",
    startsAt = Instant.parse("2026-10-02T17:00:00Z"),
    registrationUrl = "https://piterjs.timepad.ru",
  )

  @Test
  fun `enqueue creates message with delivery per active destination and event atomically`(): Unit = runBlocking {
    insertDestination("gcal_main", "google_calendar", active = true)
    insertDestination("disabled_one", "telegram_proxy", active = false)
    val flowId = flowRepository.create("PROCESSING")

    val event = approvedEvent(flowId)
    val enqueued = outboxRepository.insertEventAndEnqueue(event)

    assertThat(enqueued.eventId).isEqualTo(event.id)
    assertThat(enqueued.deliveries).isEqualTo(2) // telegram_main (seed) + gcal_main

    // событие вставлено той же транзакцией
    assertThat(eventRepository.findById(event.id)).isNotNull

    val deliveries = outboxRepository.deliveriesByFlow(flowId)
    assertThat(deliveries).hasSize(2)
    assertThat(deliveries.associateBy { it.destinationName }.keys)
      .containsExactlyInAnyOrder("telegram_main", "gcal_main")
    assertThat(deliveries.single { it.destinationName == "telegram_main" }.status).isEqualTo("PENDING")

    val messages = outboxRepository.listMessages()
    assertThat(messages).hasSize(1)
    val payload = parsePublicationPayload(buildPublicationPayload(messages.single().id, event))
    assertThat(payload.deliveryDedupKey).isEqualTo(messages.single().id.toString())
    assertThat(payload.event.title).isEqualTo("PiterJS #61")
    assertThat(payload.event.registrationUrl).isEqualTo("https://piterjs.timepad.ru")
    assertThat(payload.flowId).isEqualTo(flowId.toString())
  }

  @Test
  fun `claimPending flips status to SENDING and second claim gets nothing`() = runBlocking {
    val flowId = flowRepository.create("PROCESSING")
    val enqueued = outboxRepository.insertEventAndEnqueue(approvedEvent(flowId))

    val claimed = outboxRepository.claimPending(limit = 10)
    assertThat(claimed).hasSize(1)
    assertThat(claimed.single().messageId).isEqualTo(enqueued.messageId)
    assertThat(claimed.single().destination.name).isEqualTo("telegram_main")
    assertThat(claimed.single().payload.event.title).isEqualTo("PiterJS #61")

    // статус уже SENDING → повторный клейм пуст
    assertThat(outboxRepository.claimPending(limit = 10)).isEmpty()
  }

  @Test
  fun `markRetry moves delivery back to PENDING with future next_retry_at`() = runBlocking {
    val flowId = flowRepository.create("PROCESSING")
    outboxRepository.insertEventAndEnqueue(approvedEvent(flowId))
    val claimed = outboxRepository.claimPending(limit = 10).single()

    outboxRepository.markRetry(claimed.deliveryId, attempts = 1, nextRetryAt = Instant.now().plusSeconds(60), error = "HTTP 500")

    val after = outboxRepository.deliveriesByFlow(flowId).single()
    assertThat(after.status).isEqualTo("PENDING")
    assertThat(after.attempts).isEqualTo(1)
    assertThat(after.lastError).isEqualTo("HTTP 500")
    assertThat(outboxRepository.claimPending(limit = 10)).isEmpty() // next_retry_at в будущем
  }

  @Test
  fun `resetStuck returns crashed SENDING deliveries to the queue`(): Unit = runBlocking {
    val flowId = flowRepository.create("PROCESSING")
    outboxRepository.insertEventAndEnqueue(approvedEvent(flowId))
    outboxRepository.claimPending(limit = 10) // SENDING — «краш» до markSent

    val reset = outboxRepository.resetStuck(Instant.now().plusSeconds(1))
    assertThat(reset).isEqualTo(1)
    assertThat(outboxRepository.claimPending(limit = 10)).hasSize(1)
  }

  @Test
  fun `markSent and markFailedPermanent set terminal states`(): Unit = runBlocking {
    val flowId = flowRepository.create("PROCESSING")
    outboxRepository.insertEventAndEnqueue(approvedEvent(flowId))
    val deliveryId = outboxRepository.claimPending(limit = 10).single().deliveryId

    outboxRepository.markSent(deliveryId)
    var state = outboxRepository.deliveriesByFlow(flowId).single()
    assertThat(state.status).isEqualTo("SENT")
    assertThat(state.sentAt).isNotNull
    assertThat(outboxRepository.pendingDepth()).isZero

    val flowId2 = flowRepository.create("PROCESSING")
    outboxRepository.insertEventAndEnqueue(approvedEvent(flowId2))
    val deliveryId2 = outboxRepository.claimPending(limit = 10).single().deliveryId
    outboxRepository.markFailedPermanent(deliveryId2, attempts = 6, error = "exhausted")
    state = outboxRepository.deliveriesByFlow(flowId2).single()
    assertThat(state.status).isEqualTo("FAILED_PERMANENT")
    assertThat(state.lastError).isEqualTo("exhausted")
  }

  @Test
  fun `no active destinations still enqueues message without deliveries`(): Unit = runBlocking {
    executeSql("UPDATE destinations SET is_active = false")
    val flowId = flowRepository.create("PROCESSING")

    val enqueued = outboxRepository.insertEventAndEnqueue(approvedEvent(flowId))

    assertThat(enqueued.deliveries).isZero
    assertThat(outboxRepository.deliveriesByFlow(flowId)).isEmpty()
    assertThat(outboxRepository.listMessages()).hasSize(1)
  }

  @Test
  fun `listMessages filters by status and destination`() = runBlocking {
    val flowId = flowRepository.create("PROCESSING")
    outboxRepository.insertEventAndEnqueue(approvedEvent(flowId))
    val deliveryId = outboxRepository.claimPending(limit = 10).single().deliveryId
    outboxRepository.markSent(deliveryId)

    assertThat(outboxRepository.listMessages(status = "SENT")).hasSize(1)
    assertThat(outboxRepository.listMessages(status = "PENDING")).isEmpty()
    assertThat(outboxRepository.listMessages(destinationName = "telegram_main")).hasSize(1)
    assertThat(outboxRepository.listMessages(destinationName = "nope")).isEmpty()
  }

  private fun insertDestination(name: String, type: String, active: Boolean) {
    executeSql(
      "INSERT INTO destinations (id, type, name, config, is_active) VALUES (gen_random_uuid(), '$type', '$name', '{}'::jsonb, $active)",
    )
  }

  private fun executeSql(sql: String) {
    transaction(database) { exec(sql) }
  }
}
