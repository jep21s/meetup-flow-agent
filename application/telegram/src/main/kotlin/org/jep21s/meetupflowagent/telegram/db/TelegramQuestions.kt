package org.jep21s.meetupflowagent.telegram.db

import com.fasterxml.jackson.databind.JsonNode
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.json.jsonb
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jep21s.meetupflowagent.db.DatabaseConnectivity
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.core.annotation.Singleton
import java.time.Instant
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

private val logger = KotlinLogging.logger { }

/**
 * Заданный ботом HITL-вопрос: сообщение (chatId, messageId) → флоу-владелец.
 * Нужен для ответов reply-ом (кнопке flowId известен из callback_data) и для
 * снятия кнопок у ВСЕХ адресатов после первого принятого ответа. Дубликат
 * опций из human_requests — для локальных чтений без джойна.
 */
@OptIn(ExperimentalUuidApi::class)
object TelegramQuestions : Table("telegram_questions") {
  val id: Column<kotlin.uuid.Uuid> = uuid("id")
  val flowId: Column<kotlin.uuid.Uuid> = uuid("flow_id")
  val chatId: Column<Long> = long("chat_id")
  val messageId: Column<Long> = long("message_id")
  val optionTexts: Column<JsonNode> = jsonbMandatory("options")
  val status: Column<String> = varchar("status", 16)
  val createdAt: Column<Instant> = timestamp("created_at")
}

data class TelegramQuestionRow(
  val flowId: UUID,
  val chatId: Long,
  val messageId: Long,
  val options: List<String>,
)

/** Хранилище заданных HITL-вопросов (Persistance pending-стора telegram-proxy v1). */
@OptIn(ExperimentalUuidApi::class)
@Singleton
class TelegramQuestionRepository(private val db: DatabaseConnectivity) {

  suspend fun register(flowId: UUID, chatId: Long, messageId: Long, options: List<String>) {
    val optionsNode: JsonNode = jacksonMapper.valueToTree(options)
    withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        TelegramQuestions.insert {
          it[TelegramQuestions.flowId] = flowId.toKotlinUuid()
          it[TelegramQuestions.chatId] = chatId
          it[TelegramQuestions.messageId] = messageId
          it[TelegramQuestions.optionTexts] = optionsNode
          it[status] = "ASKED"
        }
      }
    }
  }

  /** Незакрытый вопрос по сообщению в чате (клик по кнопке / reply на сообщение). */
  suspend fun findAsked(chatId: Long, messageId: Long): TelegramQuestionRow? =
    withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        TelegramQuestions.selectAll()
          .where {
            (TelegramQuestions.chatId eq chatId) and
                (TelegramQuestions.messageId eq messageId) and
                (TelegramQuestions.status eq "ASKED")
          }
          .singleOrNull()
          ?.toRow()
      }
    }

  /** Все незакрытые вопросы флоу — снять кнопки после первого принятого ответа. */
  suspend fun findAskedByFlow(flowId: UUID): List<TelegramQuestionRow> =
    withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        TelegramQuestions.selectAll()
          .where { (TelegramQuestions.flowId eq flowId.toKotlinUuid()) and (TelegramQuestions.status eq "ASKED") }
          .map { it.toRow() }
      }
    }

  /** Закрыть все вопросы флоу (первый ответ побеждает — гарантирует human_requests). */
  suspend fun resolveFlow(flowId: UUID) {
    val updated = withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        TelegramQuestions.update({ TelegramQuestions.flowId eq flowId.toKotlinUuid() }) {
          it[status] = "RESOLVED"
        }
      }
    }
    if (updated > 0) logger.debug { "telegram questions resolved: flowId=$flowId rows=$updated" }
  }
}

private fun ResultRow.toRow() = TelegramQuestionRow(
  flowId = this[TelegramQuestions.flowId].toJavaUuid(),
  chatId = this[TelegramQuestions.chatId],
  messageId = this[TelegramQuestions.messageId],
  options = this[TelegramQuestions.optionTexts].map { it.asText() },
)

/** jsonb-колонка с JSON-деревом (аналог приватного хелпера Tables.kt extractor'а). */
private fun Table.jsonbMandatory(name: String): Column<JsonNode> =
  jsonb<JsonNode>(name, { jacksonMapper.writeValueAsString(it) }, { jacksonMapper.readTree(it) })
