package org.jep21s.meetupflowagent.db

import com.fasterxml.jackson.databind.JsonNode
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.javatime.JavaInstantColumnType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.annotation.Singleton
import java.time.Instant
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

private val logger = KotlinLogging.logger { }

/** Строка `flows`. */
data class FlowRow(
  val id: UUID,
  val status: String,
  val stateSnapshot: JsonNode?,
  val verdict: JsonNode?,
  val lastError: String?,
  val createdAt: Instant?,
  val updatedAt: Instant?,
  val retryCount: Int = 0,
  val inboxMessageId: UUID? = null,
)

/** Запись о дубле: связь нового флоу с уже существующим событием. */
data class DuplicateRow(
  val id: UUID,
  val flowId: UUID,
  val existingEventId: UUID,
  val similarity: Double,
  val decidedBy: String,
)

/**
 * Репозиторий `flows` и `duplicates`. На этом этапе флоу создаётся по одному на
 * обработанное сообщение (упрощённые статусы без FlowEngine — этап 5); связи
 * flow → event и flow → existing_event (duplicates) — «хранение связей» ДЗ4.
 */
@OptIn(ExperimentalUuidApi::class)
@Singleton
class FlowRepository(private val db: DatabaseConnectivity) {

  suspend fun create(status: String, verdict: JsonNode? = null, inboxMessageId: UUID? = null): UUID {
    val id = UUID.randomUUID()
    withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        Flows.insert {
          it[Flows.id] = id.toKotlinUuid()
          it[Flows.status] = status
          it[Flows.verdict] = verdict
          it[Flows.inboxMessageId] = inboxMessageId?.toKotlinUuid()
        }
      }
    }
    logger.info { "flow created: id=$id status=$status inbox=$inboxMessageId" }
    return id
  }

  /**
   * RetryPoller: атомарно забрать просроченные WAITING_RETRY (SKIP LOCKED) и
   * перевести в PROCESSING для резюма; возвращает id флоу.
   */
  suspend fun claimRetriableReady(now: Instant, limit: Int): List<UUID> = withContext(Dispatchers.IO) {
    suspendTransaction(db.database) {
      exec(
        """UPDATE flows SET status = 'PROCESSING', updated_at = now()
           WHERE id IN (SELECT id FROM flows WHERE status = 'WAITING_RETRY' AND next_retry_at <= ? LIMIT ? FOR UPDATE SKIP LOCKED)
           RETURNING id""",
        args = listOf(JavaInstantColumnType() to now, IntegerColumnType() to limit),
      ) { rs ->
        val ids = mutableListOf<UUID>()
        while (rs.next()) ids += UUID.fromString(rs.getString("id"))
        ids
      }.orEmpty()
    }
  }

  /** Перевод флоу в WAITING_RETRY со шкалой попыток (§13). */
  suspend fun markWaitingRetry(flowId: UUID, lastError: String?, retryCount: Int, nextRetryAt: Instant) {
    withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        Flows.update({ Flows.id eq flowId.toKotlinUuid() }) {
          it[status] = "WAITING_RETRY"
          it[Flows.retryCount] = retryCount
          it[Flows.nextRetryAt] = nextRetryAt
          it[Flows.lastError] = lastError?.take(1000)
          it[updatedAt] = Instant.now()
        }
      }
    }
    logger.warn { "flow waiting retry: id=$flowId attempt=$retryCount nextRetryAt=$nextRetryAt error=${lastError?.take(120)}" }
  }

  suspend fun markFailedPermanent(flowId: UUID, lastError: String?) {
    withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        Flows.update({ Flows.id eq flowId.toKotlinUuid() }) {
          it[status] = "FAILED_PERMANENT"
          it[Flows.lastError] = lastError?.take(1000)
          it[updatedAt] = Instant.now()
        }
      }
    }
    logger.error { "flow failed permanently: id=$flowId error=${lastError?.take(200)}" }
  }

  /** Crash-recovery: PROCESSING-флоу без обновлений дольше порога (падение JVM). */
  suspend fun findStuckProcessing(olderThan: Instant, limit: Int = 50): List<UUID> = withContext(Dispatchers.IO) {
    suspendTransaction(db.database) {
      Flows.selectAll().where { (Flows.status eq "PROCESSING") and (Flows.updatedAt less olderThan) }
        .limit(limit)
        .map { it[Flows.id].toJavaUuid() }
    }
  }

  /** WAITING_HUMAN: asked_at раньше порога (для reminder/expire). */
  suspend fun findWaitingHumanOlderThan(olderThan: Instant, limit: Int = 50): List<UUID> = withContext(Dispatchers.IO) {
    suspendTransaction(db.database) {
      exec(
        """SELECT f.id FROM flows f JOIN human_requests h ON h.flow_id = f.id
           WHERE f.status = 'WAITING_HUMAN' AND h.status = 'PENDING' AND h.asked_at <= ? LIMIT ?""",
        args = listOf(JavaInstantColumnType() to olderThan, IntegerColumnType() to limit),
      ) { rs ->
        val ids = mutableListOf<UUID>()
        while (rs.next()) ids += UUID.fromString(rs.getString(1))
        ids
      }.orEmpty()
    }
  }

  suspend fun updateStatus(
    id: UUID,
    status: String,
    lastError: String? = null,
    verdict: JsonNode? = null,
  ) {
    withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        Flows.update({ Flows.id eq id.toKotlinUuid() }) {
          it[Flows.status] = status
          if (lastError != null) it[Flows.lastError] = lastError
          if (verdict != null) it[Flows.verdict] = verdict
          it[updatedAt] = Instant.now()
        }
      }
    }
    logger.info { "flow status updated: id=$id status=$status${lastError?.let { " error=$it" } ?: ""}" }
  }

  suspend fun findById(id: UUID): FlowRow? = withContext(Dispatchers.IO) {
    suspendTransaction(db.database) {
      Flows.selectAll().where { Flows.id eq id.toKotlinUuid() }.firstOrNull()?.toFlowRow()
    }
  }

  suspend fun insertDuplicate(
    flowId: UUID,
    existingEventId: UUID,
    similarity: Double,
    decidedBy: String,
  ): UUID {
    val id = UUID.randomUUID()
    withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        Duplicates.insert {
          it[Duplicates.id] = id.toKotlinUuid()
          it[Duplicates.flowId] = flowId.toKotlinUuid()
          it[Duplicates.existingEventId] = existingEventId.toKotlinUuid()
          it[Duplicates.similarity] = similarity
          it[Duplicates.decidedBy] = decidedBy
        }
      }
    }
    logger.info { "duplicate linked: flowId=$flowId existingEventId=$existingEventId similarity=$similarity decidedBy=$decidedBy" }
    return id
  }

  suspend fun findDuplicatesByFlow(flowId: UUID): List<DuplicateRow> = withContext(Dispatchers.IO) {
    suspendTransaction(db.database) {
      Duplicates.selectAll().where { Duplicates.flowId eq flowId.toKotlinUuid() }.map { it.toDuplicateRow() }
    }
  }

  suspend fun findInboxMessageId(flowId: UUID): UUID? = withContext(Dispatchers.IO) {
    suspendTransaction(db.database) {
      Flows.selectAll().where { Flows.id eq flowId.toKotlinUuid() }
        .firstOrNull()?.get(Flows.inboxMessageId)?.toJavaUuid()
    }
  }

  /** WAITING_HUMAN → EXPIRED: флоу + все PENDING-вопросы. */
  suspend fun expireHumanRequest(flowId: UUID) {
    withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        Flows.update({ Flows.id eq flowId.toKotlinUuid() }) {
          it[status] = "EXPIRED"
          it[updatedAt] = Instant.now()
        }
        exec(
          "UPDATE human_requests SET status = 'EXPIRED' WHERE flow_id = ?::uuid AND status = 'PENDING'",
          args = listOf(TextColumnType() to flowId.toString()),
        )
      }
    }
    logger.info { "human request expired: flowId=$flowId" }
  }

  private fun ResultRow.toFlowRow() = FlowRow(
    id = this[Flows.id].toJavaUuid(),
    status = this[Flows.status],
    stateSnapshot = this[Flows.stateSnapshot],
    verdict = this[Flows.verdict],
    lastError = this[Flows.lastError],
    createdAt = this[Flows.createdAt],
    updatedAt = this[Flows.updatedAt],
    retryCount = this[Flows.retryCount],
    inboxMessageId = this[Flows.inboxMessageId]?.toJavaUuid(),
  )

  private fun ResultRow.toDuplicateRow() = DuplicateRow(
    id = this[Duplicates.id].toJavaUuid(),
    flowId = this[Duplicates.flowId].toJavaUuid(),
    existingEventId = this[Duplicates.existingEventId].toJavaUuid(),
    similarity = this[Duplicates.similarity],
    decidedBy = this[Duplicates.decidedBy],
  )
}
