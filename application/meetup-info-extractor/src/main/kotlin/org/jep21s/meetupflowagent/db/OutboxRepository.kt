package org.jep21s.meetupflowagent.db

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.javatime.JavaInstantColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jep21s.meetupflowagent.outbox.OutboxDeliveryTask
import org.jep21s.meetupflowagent.outbox.buildPublicationPayload
import org.jep21s.meetupflowagent.outbox.parsePublicationPayload
import org.koin.core.annotation.Singleton
import java.time.Instant
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

private val logger = KotlinLogging.logger { }

/** Созданная публикация: сообщение outbox + fan-out доставок. */
data class OutboxEnqueued(
  val messageId: UUID,
  val eventId: UUID,
  val deliveries: Int,
)

/** Состояние одной доставки (для инспекции через API). */
data class DeliveryStateRow(
  val id: UUID,
  val outboxMessageId: UUID,
  val destinationId: UUID,
  val destinationName: String,
  val destinationType: String,
  val status: String,
  val attempts: Int,
  val nextRetryAt: Instant,
  val lastError: String?,
  val sentAt: Instant?,
)

/** Сообщение outbox с доставками (GET /internal/outbox). */
data class OutboxMessageState(
  val id: UUID,
  val flowId: UUID,
  val eventId: UUID,
  val createdAt: Instant,
  val deliveries: List<DeliveryStateRow>,
)

/**
 * Outbox-паттерн для публикаций успешного результата (APPROVED-анонс):
 * запись атомарна с вставкой события, доставка — отдельным поллером со своей
 * шкалой ретраев; состояние доставки живёт отдельно от сообщения (одна публикация
 * → N назначений, связь один-ко-многим).
 */
@OptIn(ExperimentalUuidApi::class)
@Singleton
class OutboxRepository(
  private val db: DatabaseConnectivity,
  private val destinationRepository: DestinationRepository,
  private val eventRepository: EventRepository,
) {

  /**
   * Атомарно (одна транзакция): событие в календарь + сообщение outbox + доставки
   * PENDING на каждое активное назначение. Не бывает опубликованного события без
   * задания на доставку. Активных назначений ноль → сообщение без доставок (аудит).
   */
  suspend fun insertEventAndEnqueue(event: EventRow): OutboxEnqueued = withContext(Dispatchers.IO) {
    suspendTransaction(db.database) {
      val flowId = checkNotNull(event.flowId) { "outbox publication requires event.flowId" }
      eventRepository.insertEventInTransaction(event)
      val destinations = Destinations.selectAll().where { Destinations.isActive eq true }
        .map { DestinationRow(it[Destinations.id].toJavaUuid(), it[Destinations.type], it[Destinations.name], it[Destinations.config], it[Destinations.isActive]) }
      val messageId = UUID.randomUUID()
      OutboxMessages.insert {
        it[id] = messageId.toKotlinUuid()
        it[OutboxMessages.flowId] = flowId.toKotlinUuid()
        it[OutboxMessages.eventId] = event.id.toKotlinUuid()
        it[OutboxMessages.payload] = buildPublicationPayload(messageId, event)
      }
      for (destination in destinations) {
        OutboxDeliveries.insert {
          it[id] = UUID.randomUUID().toKotlinUuid()
          it[outboxMessageId] = messageId.toKotlinUuid()
          it[destinationId] = destination.id.toKotlinUuid()
          it[status] = "PENDING"
        }
      }
      OutboxEnqueued(messageId, event.id, destinations.size)
    }
  }

  /**
   * Клейм пачки доставок (батч [limit]): атомарный UPDATE … FOR UPDATE SKIP LOCKED
   * RETURNING → SENDING; вторым запросом в той же транзакции подтягиваются payload
   * и параметры назначения. Конкурентные поллеры не заберут доставку дважды.
   */
  suspend fun claimPending(limit: Int, now: Instant = Instant.now()): List<OutboxDeliveryTask> =
    withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        val claimed = exec(
          """UPDATE outbox_deliveries SET status = 'SENDING', updated_at = now()
             WHERE id IN (SELECT id FROM outbox_deliveries
                          WHERE status = 'PENDING' AND next_retry_at <= ? LIMIT ? FOR UPDATE SKIP LOCKED)
             RETURNING id, outbox_message_id, destination_id, attempts""",
          args = listOf(JavaInstantColumnType() to now, IntegerColumnType() to limit),
          explicitStatementType = org.jetbrains.exposed.v1.core.statements.StatementType.SELECT,
        ) { rs ->
          val rows = mutableListOf<Triple<UUID, UUID, UUID>>() // deliveryId, messageId, destinationId
          val attemptsById = mutableMapOf<UUID, Int>()
          while (rs.next()) {
            val deliveryId = UUID.fromString(rs.getString("id"))
            rows += Triple(deliveryId, UUID.fromString(rs.getString("outbox_message_id")), UUID.fromString(rs.getString("destination_id")))
            attemptsById[deliveryId] = rs.getInt("attempts")
          }
          rows to attemptsById
        } ?: (mutableListOf<Triple<UUID, UUID, UUID>>() to mutableMapOf<UUID, Int>())
        if (claimed.first.isEmpty()) return@suspendTransaction emptyList()

        val tasks = mutableListOf<OutboxDeliveryTask>()
        for ((deliveryId, messageId, destinationId) in claimed.first) {
          val message = OutboxMessages.selectAll().where { OutboxMessages.id eq messageId.toKotlinUuid() }
            .firstOrNull() ?: continue
          val destination = Destinations.selectAll().where { Destinations.id eq destinationId.toKotlinUuid() }
            .firstOrNull() ?: continue
          tasks += OutboxDeliveryTask(
            deliveryId = deliveryId,
            messageId = messageId,
            attempts = claimed.second[deliveryId] ?: 0,
            destination = DestinationRow(
              id = destination[Destinations.id].toJavaUuid(),
              type = destination[Destinations.type],
              name = destination[Destinations.name],
              config = destination[Destinations.config],
              isActive = destination[Destinations.isActive],
            ),
            payload = parsePublicationPayload(message[OutboxMessages.payload]),
          )
        }
        tasks
      }
    }

  suspend fun markSent(deliveryId: UUID) {
    withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        OutboxDeliveries.update({ OutboxDeliveries.id eq deliveryId.toKotlinUuid() }) {
          it[status] = "SENT"
          it[sentAt] = Instant.now()
          it[lastError] = null
        }
      }
    }
  }

  /** Повторимая неудача: обратно в PENDING с расписанным next_retry_at. */
  suspend fun markRetry(deliveryId: UUID, attempts: Int, nextRetryAt: Instant, error: String) {
    withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        OutboxDeliveries.update({ OutboxDeliveries.id eq deliveryId.toKotlinUuid() }) {
          it[status] = "PENDING"
          it[OutboxDeliveries.attempts] = attempts
          it[OutboxDeliveries.nextRetryAt] = nextRetryAt
          it[lastError] = error.take(500)
        }
      }
    }
  }

  /** Исчерпаны попытки: доставка перманентно не удалась, флоу не затрагивается. */
  suspend fun markFailedPermanent(deliveryId: UUID, attempts: Int, error: String) {
    withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        OutboxDeliveries.update({ OutboxDeliveries.id eq deliveryId.toKotlinUuid() }) {
          it[status] = "FAILED_PERMANENT"
          it[OutboxDeliveries.attempts] = attempts
          it[lastError] = error.take(500)
        }
      }
    }
    logger.error { "outbox delivery failed permanently: deliveryId=$deliveryId error=${error.take(200)}" }
  }

  /** Crash-recovery: зависшие SENDING (краш между клеймом и отметкой) → PENDING. */
  suspend fun resetStuck(olderThan: Instant): Int = withContext(Dispatchers.IO) {
    suspendTransaction(db.database) {
      OutboxDeliveries.update({
        (OutboxDeliveries.status eq "SENDING") and (OutboxDeliveries.updatedAt lessEq olderThan)
      }) {
        it[status] = "PENDING"
      }
    }
  }

  /** Доставки публикации флоу (блок deliveries в GET /api/flows/{id}). */
  suspend fun deliveriesByFlow(flowId: UUID): List<DeliveryStateRow> = withContext(Dispatchers.IO) {
    suspendTransaction(db.database) {
      exec(
        """SELECT d.id, d.outbox_message_id, d.destination_id, dest.name, dest.type,
                  d.status, d.attempts, d.next_retry_at, d.last_error, d.sent_at
           FROM outbox_deliveries d
           JOIN outbox_messages m ON m.id = d.outbox_message_id
           JOIN destinations dest ON dest.id = d.destination_id
           WHERE m.flow_id = ?::uuid
           ORDER BY d.created_at""",
        args = listOf(TextColumnType() to flowId.toString()),
        explicitStatementType = org.jetbrains.exposed.v1.core.statements.StatementType.SELECT,
      ) { rs ->
        val rows = mutableListOf<DeliveryStateRow>()
        while (rs.next()) {
          rows += DeliveryStateRow(
            id = UUID.fromString(rs.getString("id")),
            outboxMessageId = UUID.fromString(rs.getString("outbox_message_id")),
            destinationId = UUID.fromString(rs.getString("destination_id")),
            destinationName = rs.getString("name"),
            destinationType = rs.getString("type"),
            status = rs.getString("status"),
            attempts = rs.getInt("attempts"),
            nextRetryAt = rs.getTimestamp("next_retry_at").toInstant(),
            lastError = rs.getString("last_error"),
            sentAt = rs.getTimestamp("sent_at")?.toInstant(),
          )
        }
        rows
      }.orEmpty()
    }
  }

  /** Инспекция: сообщения + доставки, факультативные фильтры по статусу/назначению. */
  suspend fun listMessages(
    status: String? = null,
    destinationName: String? = null,
    limit: Int = 100,
  ): List<OutboxMessageState> = withContext(Dispatchers.IO) {
    suspendTransaction(db.database) {
      val args = mutableListOf<Pair<org.jetbrains.exposed.v1.core.IColumnType<*>, Any?>>()
      val sql = buildString {
        append(
          """SELECT m.id, m.flow_id, m.event_id, m.created_at,
                    d.id AS delivery_id, d.destination_id, dest.name, dest.type,
                    d.status, d.attempts, d.next_retry_at, d.last_error, d.sent_at
             FROM outbox_messages m
             LEFT JOIN outbox_deliveries d ON d.outbox_message_id = m.id
             LEFT JOIN destinations dest ON dest.id = d.destination_id""",
        )
        var where = ""
        if (status != null) {
          where = " WHERE d.status = ?"
          args += TextColumnType() to status
        }
        if (destinationName != null) {
          where += if (where.isEmpty()) " WHERE dest.name = ?" else " AND dest.name = ?"
          args += TextColumnType() to destinationName
        }
        append(where)
        append(" ORDER BY m.created_at DESC LIMIT ?")
        args += IntegerColumnType() to limit
      }
      val byMessage = linkedMapOf<UUID, Pair<OutboxMessageState, MutableList<DeliveryStateRow>>>()
      exec(sql, args, explicitStatementType = org.jetbrains.exposed.v1.core.statements.StatementType.SELECT) { rs ->
        while (rs.next()) {
          val messageId = UUID.fromString(rs.getString("id"))
          val entry = byMessage.getOrPut(messageId) {
            OutboxMessageState(
              id = messageId,
              flowId = UUID.fromString(rs.getString("flow_id")),
              eventId = UUID.fromString(rs.getString("event_id")),
              createdAt = rs.getTimestamp("created_at").toInstant(),
              deliveries = emptyList(),
            ) to mutableListOf()
          }
          if (rs.getString("delivery_id") != null) {
            entry.second += DeliveryStateRow(
              id = UUID.fromString(rs.getString("delivery_id")),
              outboxMessageId = messageId,
              destinationId = UUID.fromString(rs.getString("destination_id")),
              destinationName = rs.getString("name") ?: "?",
              destinationType = rs.getString("type") ?: "?",
              status = rs.getString("status") ?: "?",
              attempts = rs.getInt("attempts"),
              nextRetryAt = rs.getTimestamp("next_retry_at")?.toInstant() ?: Instant.EPOCH,
              lastError = rs.getString("last_error"),
              sentAt = rs.getTimestamp("sent_at")?.toInstant(),
            )
          }
        }
      }
      byMessage.values.map { (message, deliveries) -> message.copy(deliveries = deliveries.toList()) }
    }
  }

  /** Глубина outbox (gauge): PENDING-доставки, ожидающие отправки. */
  suspend fun pendingDepth(): Long = withContext(Dispatchers.IO) {
    suspendTransaction(db.database) {
      OutboxDeliveries.selectAll().where { OutboxDeliveries.status eq "PENDING" }.count()
    }
  }
}
