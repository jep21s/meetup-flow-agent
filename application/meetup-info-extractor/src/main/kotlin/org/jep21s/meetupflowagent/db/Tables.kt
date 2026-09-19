package org.jep21s.meetupflowagent.db

import com.fasterxml.jackson.databind.JsonNode
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.VarCharColumnType
// array/uuid/registerColumn — member-функции Table (без импорта)
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.json.jsonb
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import kotlin.uuid.ExperimentalUuidApi

/**
 * Маппинг таблиц схемы §6 (DDL создаёт Liquibase — здесь только типы для
 * чтения/записи; `SchemaUtils.create` не используется).
 *
 * id-колонки без дефолтов: UUID генерирует приложение (репозитории).
 */
@OptIn(ExperimentalUuidApi::class)
object InboxMessages : Table("inbox_messages") {
  val id: Column<kotlin.uuid.Uuid> = uuid("id")
  val idempotencyKey: Column<String?> = varchar("idempotency_key", 128).nullable()
  val rawText: Column<String> = text("raw_text")
  val sourceMeta: Column<JsonNode?> = jsonbNullable("source_meta")
  val receivedAt: Column<java.time.Instant> = timestamp("received_at")
  val status: Column<String> = varchar("status", 16)
  val flowId: Column<kotlin.uuid.Uuid?> = uuid("flow_id").nullable()
}

@OptIn(ExperimentalUuidApi::class)
object Flows : Table("flows") {
  val id: Column<kotlin.uuid.Uuid> = uuid("id")
  val inboxMessageId: Column<kotlin.uuid.Uuid?> = uuid("inbox_message_id").nullable()
  val status: Column<String> = varchar("status", 32)
  val stateSnapshot: Column<JsonNode?> = jsonbNullable("state_snapshot")
  val traceId: Column<String?> = varchar("trace_id", 64).nullable()
  val retryCount: Column<Int> = integer("retry_count")
  val nextRetryAt: Column<java.time.Instant?> = timestamp("next_retry_at").nullable()
  val lastError: Column<String?> = text("last_error").nullable()
  val verdict: Column<JsonNode?> = jsonbNullable("verdict")
  val createdAt: Column<java.time.Instant> = timestamp("created_at")
  val updatedAt: Column<java.time.Instant> = timestamp("updated_at")
}

@OptIn(ExperimentalUuidApi::class)
object FlowSteps : Table("flow_steps") {
  val id: Column<kotlin.uuid.Uuid> = uuid("id")
  val flowId: Column<kotlin.uuid.Uuid> = uuid("flow_id")
  val seq: Column<Int> = integer("seq")
  val type: Column<String> = varchar("type", 24)
  val content: Column<JsonNode> = jsonbMandatory("content")
  val tokens: Column<Int?> = integer("tokens").nullable()
  val latencyMs: Column<Int?> = integer("latency_ms").nullable()
  val createdAt: Column<java.time.Instant> = timestamp("created_at")
}

@OptIn(ExperimentalUuidApi::class)
object Events : Table("events") {
  val id: Column<kotlin.uuid.Uuid> = uuid("id")
  val flowId: Column<kotlin.uuid.Uuid?> = uuid("flow_id").nullable()
  val title: Column<String> = varchar("title", 512)
  val description: Column<String?> = text("description").nullable()
  val organizer: Column<String?> = varchar("organizer", 256).nullable()
  val city: Column<String?> = varchar("city", 128).nullable()
  val isFree: Column<Boolean?> = bool("is_free").nullable()
  val price: Column<String?> = varchar("price", 128).nullable()
  val formats: Column<List<String>> = array("formats", VarCharColumnType(32), dimensions = 1)
  val address: Column<String?> = varchar("address", 512).nullable()
  val venueName: Column<String?> = varchar("venue_name", 256).nullable()
  val startsAt: Column<java.time.Instant> = timestamp("starts_at")
  val endsAt: Column<java.time.Instant?> = timestamp("ends_at").nullable()
  val talks: Column<JsonNode?> = jsonbNullable("talks")
  val registrationUrl: Column<String?> = varchar("registration_url", 1024).nullable()
  val sourceUrls: Column<JsonNode?> = jsonbNullable("source_urls")
  val language: Column<String?> = varchar("language", 8).nullable()
  val confidence: Column<Double?> = double("confidence").nullable()
  val raw: Column<JsonNode?> = jsonbNullable("raw")
  val embedding: Column<FloatArray?> = registerColumn("embedding", VectorColumnType(EMBEDDING_DIM)).nullable()
  val createdAt: Column<java.time.Instant> = timestamp("created_at")
  val updatedAt: Column<java.time.Instant> = timestamp("updated_at")
}

@OptIn(ExperimentalUuidApi::class)
object HumanRequests : Table("human_requests") {
  val id: Column<kotlin.uuid.Uuid> = uuid("id")
  val flowId: Column<kotlin.uuid.Uuid> = uuid("flow_id")
  val question: Column<JsonNode> = jsonbMandatory("question")
  val status: Column<String> = varchar("status", 16)
  val answers: Column<JsonNode?> = jsonbNullable("answers")
  val winningAnswer: Column<JsonNode?> = jsonbNullable("winning_answer")
  val askedAt: Column<java.time.Instant> = timestamp("asked_at")
  val answeredAt: Column<java.time.Instant?> = timestamp("answered_at").nullable()
}

@OptIn(ExperimentalUuidApi::class)
object Duplicates : Table("duplicates") {
  val id: Column<kotlin.uuid.Uuid> = uuid("id")
  val flowId: Column<kotlin.uuid.Uuid> = uuid("flow_id")
  val existingEventId: Column<kotlin.uuid.Uuid> = uuid("existing_event_id")
  val similarity: Column<Double> = double("similarity")
  val decidedBy: Column<String> = varchar("decided_by", 8)
  val createdAt: Column<java.time.Instant> = timestamp("created_at")
}

@OptIn(ExperimentalUuidApi::class)
object Users : Table("users") {
  val id: Column<kotlin.uuid.Uuid> = uuid("id")
  val telegramUserId: Column<Long> = long("telegram_user_id")
  val displayName: Column<String?> = varchar("display_name", 256).nullable()
  val role: Column<String?> = varchar("role", 32).nullable()
  val isActive: Column<Boolean> = bool("is_active")
  val createdAt: Column<java.time.Instant> = timestamp("created_at")
}

@OptIn(ExperimentalUuidApi::class)
object Destinations : Table("destinations") {
  val id: Column<kotlin.uuid.Uuid> = uuid("id")
  val type: Column<String> = varchar("type", 64)
  val name: Column<String> = varchar("name", 128)
  val config: Column<JsonNode> = jsonbMandatory("config")
  val isActive: Column<Boolean> = bool("is_active")
  val createdAt: Column<java.time.Instant> = timestamp("created_at")
  val updatedAt: Column<java.time.Instant> = timestamp("updated_at")
}

@OptIn(ExperimentalUuidApi::class)
object OutboxMessages : Table("outbox_messages") {
  val id: Column<kotlin.uuid.Uuid> = uuid("id")
  val flowId: Column<kotlin.uuid.Uuid> = uuid("flow_id")
  val eventId: Column<kotlin.uuid.Uuid> = uuid("event_id")
  val payload: Column<JsonNode> = jsonbMandatory("payload")
  val createdAt: Column<java.time.Instant> = timestamp("created_at")
}

@OptIn(ExperimentalUuidApi::class)
object OutboxDeliveries : Table("outbox_deliveries") {
  val id: Column<kotlin.uuid.Uuid> = uuid("id")
  val outboxMessageId: Column<kotlin.uuid.Uuid> = uuid("outbox_message_id")
  val destinationId: Column<kotlin.uuid.Uuid> = uuid("destination_id")
  val status: Column<String> = varchar("status", 32)
  val attempts: Column<Int> = integer("attempts")
  val nextRetryAt: Column<java.time.Instant> = timestamp("next_retry_at")
  val lastError: Column<String?> = text("last_error").nullable()
  val sentAt: Column<java.time.Instant?> = timestamp("sent_at").nullable()
  val createdAt: Column<java.time.Instant> = timestamp("created_at")
  val updatedAt: Column<java.time.Instant> = timestamp("updated_at")
}

/** jsonb-колонка с произвольным JSON-деревом (Jackson). */
private fun Table.jsonbNullable(name: String): Column<JsonNode?> =
  jsonb<JsonNode>(name, { jacksonMapper.writeValueAsString(it) }, { jacksonMapper.readTree(it) }).nullable()

/** NOT NULL jsonb-колонка с произвольным JSON-деревом (Jackson). */
private fun Table.jsonbMandatory(name: String): Column<JsonNode> =
  jsonb<JsonNode>(name, { jacksonMapper.writeValueAsString(it) }, { jacksonMapper.readTree(it) })

/** Размерность эмбеддингов Yandex text-embeddings-v2-doc (§10). */
const val EMBEDDING_DIM: Int = 768
