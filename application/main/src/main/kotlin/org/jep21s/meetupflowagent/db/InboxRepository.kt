package org.jep21s.meetupflowagent.db

import com.fasterxml.jackson.databind.JsonNode
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.core.annotation.Singleton
import java.time.Instant
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

private val logger = KotlinLogging.logger { }

/** Строка inbox (входящее сообщение до обработки). */
data class InboxRow(
  val id: UUID,
  val idempotencyKey: String?,
  val rawText: String,
  val status: String,
  val flowId: UUID?,
)

/**
 * Inbox-паттерн (§12): точные повторы отбрасываются уникальным индексом
 * idempotency_key; поллер забирает пачки через FOR UPDATE SKIP LOCKED.
 */
@OptIn(ExperimentalUuidApi::class)
@Singleton
class InboxRepository(private val db: DatabaseConnectivity) {

  /** Вставляет сообщение; при конфликте idempotency_key возвращает id существующего. */
  suspend fun insertIfAbsent(idempotencyKey: String?, rawText: String, sourceMeta: JsonNode?): InsertResult {
    val id = UUID.randomUUID()
    val inserted = withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        exec(
          """INSERT INTO inbox_messages (id, idempotency_key, raw_text, source_meta, status)
             VALUES (?::uuid, ?, ?, ?::jsonb, 'NEW')
             ON CONFLICT (idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING
             RETURNING id""",
          args = listOf(
            TextColumnType() to id.toString(),
            VarCharColumnType(128) to idempotencyKey,
            TextColumnType() to rawText,
            TextColumnType() to sourceMeta?.let { jacksonMapper.writeValueAsString(it) },
          ),
          explicitStatementType = org.jetbrains.exposed.v1.core.statements.StatementType.SELECT,
        ) { rs -> if (rs.next()) 1 else 0 }  // RETURNING id: строка есть ⇔ вставка произошла
      }
    } ?: 0
    return if (inserted == 0) {
      val existing = findByIdempotencyKey(idempotencyKey!!)
      InsertResult.Duplicate(existing.id, existing.flowId)
    } else {
      InsertResult.Inserted(id)
    }
  }

  /**
   * Клейм пачки NEW-сообщений (батч [limit]): атомарный UPDATE … WHERE id IN
   * (SELECT … FOR UPDATE SKIP LOCKED) RETURNING — конкурентные поллеры не
   * заберут одно сообщение дважды.
   */
  suspend fun claimBatch(limit: Int): List<InboxRow> = withContext(Dispatchers.IO) {
    suspendTransaction(db.database) {
      exec(
        """UPDATE inbox_messages SET status = 'CLAIMED'
           WHERE id IN (SELECT id FROM inbox_messages WHERE status = 'NEW' LIMIT ? FOR UPDATE SKIP LOCKED)
           RETURNING id, idempotency_key, raw_text, status, flow_id""",
        args = listOf(IntegerColumnType() to limit),
        explicitStatementType = org.jetbrains.exposed.v1.core.statements.StatementType.SELECT,
      ) { rs ->
        val rows = mutableListOf<InboxRow>()
        while (rs.next()) {
          rows += InboxRow(
            id = UUID.fromString(rs.getString("id")),
            idempotencyKey = rs.getString("idempotency_key"),
            rawText = rs.getString("raw_text"),
            status = rs.getString("status"),
            flowId = rs.getString("flow_id")?.let { UUID.fromString(it) },
          )
        }
        rows
      }.orEmpty()
    }
  }

  suspend fun markDone(id: UUID, flowId: UUID?) {
    withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        InboxMessages.update({ InboxMessages.id eq id.toKotlinUuid() }) {
          it[status] = "DONE"
          if (flowId != null) it[InboxMessages.flowId] = flowId.toKotlinUuid()
        }
      }
    }
  }

  suspend fun attachFlow(id: UUID, flowId: UUID) {
    withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        InboxMessages.update({ InboxMessages.id eq id.toKotlinUuid() }) {
          it[InboxMessages.flowId] = flowId.toKotlinUuid()
        }
      }
    }
  }

  suspend fun findById(id: UUID): InboxRow? = withContext(Dispatchers.IO) {
    suspendTransaction(db.database) {
      InboxMessages.selectAll().where { InboxMessages.id eq id.toKotlinUuid() }
        .firstOrNull()?.toInboxRow()
    }
  }

  private suspend fun findByIdempotencyKey(key: String): InboxRow =
    withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        InboxMessages.selectAll().where { InboxMessages.idempotencyKey eq key }
          .firstOrNull()?.toInboxRow()
      }
    } ?: error("inbox message with idempotency key '$key' disappeared")

  private fun org.jetbrains.exposed.v1.core.ResultRow.toInboxRow() = InboxRow(
    id = this[InboxMessages.id].toJavaUuid(),
    idempotencyKey = this[InboxMessages.idempotencyKey],
    rawText = this[InboxMessages.rawText],
    status = this[InboxMessages.status],
    flowId = this[InboxMessages.flowId]?.toJavaUuid(),
  )

  sealed interface InsertResult {
    data class Inserted(val id: UUID) : InsertResult
    data class Duplicate(val existingId: UUID, val existingFlowId: UUID?) : InsertResult
  }

}
