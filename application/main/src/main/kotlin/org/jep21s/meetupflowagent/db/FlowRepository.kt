package org.jep21s.meetupflowagent.db

import com.fasterxml.jackson.databind.JsonNode
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
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

/** Строка `flows` (упрощённо; полная стейт-машина — этап 5). */
data class FlowRow(
  val id: UUID,
  val status: String,
  val verdict: JsonNode?,
  val lastError: String?,
  val createdAt: Instant?,
  val updatedAt: Instant?,
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

  suspend fun create(status: String, verdict: JsonNode? = null): UUID {
    val id = UUID.randomUUID()
    withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        Flows.insert {
          it[Flows.id] = id.toKotlinUuid()
          it[Flows.status] = status
          it[Flows.verdict] = verdict
        }
      }
    }
    logger.info { "flow created: id=$id status=$status" }
    return id
  }

  suspend fun updateStatus(id: UUID, status: String, lastError: String? = null) {
    withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        Flows.update({ Flows.id eq id.toKotlinUuid() }) {
          it[Flows.status] = status
          if (lastError != null) it[Flows.lastError] = lastError
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

  private fun ResultRow.toFlowRow() = FlowRow(
    id = this[Flows.id].toJavaUuid(),
    status = this[Flows.status],
    verdict = this[Flows.verdict],
    lastError = this[Flows.lastError],
    createdAt = this[Flows.createdAt],
    updatedAt = this[Flows.updatedAt],
  )

  private fun ResultRow.toDuplicateRow() = DuplicateRow(
    id = this[Duplicates.id].toJavaUuid(),
    flowId = this[Duplicates.flowId].toJavaUuid(),
    existingEventId = this[Duplicates.existingEventId].toJavaUuid(),
    similarity = this[Duplicates.similarity],
    decidedBy = this[Duplicates.decidedBy],
  )
}
