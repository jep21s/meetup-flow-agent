package org.jep21s.meetupflowagent.db

import com.fasterxml.jackson.databind.JsonNode
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
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

/** Типы шагов флоу (flow_steps.type, §6) — полная история агента, включая CoT. */
enum class FlowStepType {
  GUARDRAILS, REASON, ACTION, OBSERVATION, FINAL, ERROR,
  HUMAN_ASK, HUMAN_ANSWER, RESUME, STATUS,
}

/** Строка истории шагов (для GET /api/flows/{id}). */
data class FlowStepRow(
  val seq: Int,
  val type: FlowStepType,
  val content: JsonNode,
  val tokens: Int?,
  val latencyMs: Int?,
  val createdAt: Instant?,
)

/**
 * История флоу: каждый шаг цикла (REASON/ACTION/OBSERVATION/FINAL/ERROR) — строка
 * flow_steps со сквозным seq; в той же транзакции обновляется state_snapshot
 * (заготовка резюма, §13 «шаги атомарны»; replay-SSE по seq — этап project).
 */
@OptIn(ExperimentalUuidApi::class)
@Singleton
class FlowStepRepository(private val db: DatabaseConnectivity) {

  suspend fun appendStep(
    flowId: UUID,
    type: FlowStepType,
    content: JsonNode,
    tokens: Int? = null,
    latencyMs: Long? = null,
    snapshot: JsonNode? = null,
  ): Int = withContext(Dispatchers.IO) {
    suspendTransaction(db.database) {
      val nextSeq = (FlowSteps.selectAll()
        .where { FlowSteps.flowId eq flowId.toKotlinUuid() }
        .maxOfOrNull { it[FlowSteps.seq] } ?: 0) + 1
      FlowSteps.insert {
        it[id] = UUID.randomUUID().toKotlinUuid()
        it[FlowSteps.flowId] = flowId.toKotlinUuid()
        it[seq] = nextSeq
        it[FlowSteps.type] = type.name
        it[FlowSteps.content] = content
        it[FlowSteps.tokens] = tokens
        it[FlowSteps.latencyMs] = latencyMs?.toInt()
      }
      if (snapshot != null) {
        Flows.update({ Flows.id eq flowId.toKotlinUuid() }) {
          it[stateSnapshot] = snapshot
          it[updatedAt] = Instant.now()
        }
      }
      nextSeq
    }
  }
  suspend fun stepsByFlow(flowId: UUID): List<FlowStepRow> = withContext(Dispatchers.IO) {
    suspendTransaction(db.database) {
      FlowSteps.selectAll()
        .where { FlowSteps.flowId eq flowId.toKotlinUuid() }
        .orderBy(FlowSteps.seq to SortOrder.ASC)
        .map { it.toStepRow() }
    }
  }

  private fun ResultRow.toStepRow() = FlowStepRow(
    seq = this[FlowSteps.seq],
    type = FlowStepType.entries.firstOrNull { it.name == this[FlowSteps.type] } ?: FlowStepType.STATUS,
    content = this[FlowSteps.content],
    tokens = this[FlowSteps.tokens],
    latencyMs = this[FlowSteps.latencyMs],
    createdAt = this[FlowSteps.createdAt],
  )
}
