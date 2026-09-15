package org.jep21s.meetupflowagent.db

import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
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

/** Открытый вопрос флоу человеку (human_requests). */
data class HumanRequestRow(
  val id: UUID,
  val flowId: UUID,
  val question: JsonNode,
  val status: String,
  val answers: JsonNode?,
  val winningAnswer: JsonNode?,
)

/**
 * HITL-хранилище: вопрос + накопленные ответы. Первый ответ побеждает
 * (winning_answer); опоздавшие остаются в answers.
 */
@OptIn(ExperimentalUuidApi::class)
@Singleton
class HumanRequestRepository(private val db: DatabaseConnectivity) {

  suspend fun create(flowId: UUID, question: JsonNode): UUID {
    val id = UUID.randomUUID()
    withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        // новый ask_human закрывает предыдущие PENDING-вопросы флоу (§9)
        HumanRequests.update({ (HumanRequests.flowId eq flowId.toKotlinUuid()) and (HumanRequests.status eq "PENDING") }) {
          it[status] = "EXPIRED"
        }
        HumanRequests.insert {
          it[HumanRequests.id] = id.toKotlinUuid()
          it[HumanRequests.flowId] = flowId.toKotlinUuid()
          it[HumanRequests.question] = question
        }
      }
    }
    return id
  }

  /** Ответ человека; true если он первый (победил), false — уже ответлено. */
  suspend fun submitAnswer(flowId: UUID, responderUserId: Long, answer: String): Boolean =
    withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        val pending = HumanRequests.selectAll()
          .where { (HumanRequests.flowId eq flowId.toKotlinUuid()) and (HumanRequests.status eq "PENDING") }
          .firstOrNull() ?: return@suspendTransaction false
        val answersArray = (pending[HumanRequests.answers] as? com.fasterxml.jackson.databind.node.ArrayNode)
          ?.deepCopy() ?: jacksonMapper.createArrayNode()
        answersArray.add(
          jacksonMapper.createObjectNode().apply {
            put("responderUserId", responderUserId)
            put("answer", answer.take(4000))
            put("at", Instant.now().toString())
          },
        )
        val winning = jacksonMapper.createObjectNode().apply {
          put("responderUserId", responderUserId)
          put("answer", answer.take(4000))
          put("at", Instant.now().toString())
        }
        HumanRequests.update({ HumanRequests.id eq pending[HumanRequests.id] }) {
          it[status] = "ANSWERED"
          it[HumanRequests.answers] = answersArray
          it[HumanRequests.winningAnswer] = winning
          it[answeredAt] = Instant.now()
        }
        true
      }
    }

  suspend fun findAnswered(flowId: UUID): HumanRequestRow? = withContext(Dispatchers.IO) {
    suspendTransaction(db.database) {
      HumanRequests.selectAll()
        .where { (HumanRequests.flowId eq flowId.toKotlinUuid()) and (HumanRequests.status eq "ANSWERED") }
        .firstOrNull()?.toRow()
    }
  }

  private fun org.jetbrains.exposed.v1.core.ResultRow.toRow() = HumanRequestRow(
    id = this[HumanRequests.id].toJavaUuid(),
    flowId = this[HumanRequests.flowId].toJavaUuid(),
    question = this[HumanRequests.question],
    status = this[HumanRequests.status],
    answers = this[HumanRequests.answers],
    winningAnswer = this[HumanRequests.winningAnswer],
  )
}
