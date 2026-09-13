package org.jep21s.meetupflowagent.db

import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.javatime.JavaInstantColumnType
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.core.annotation.Singleton
import java.time.Instant
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/** Строка календаря событий (§6); id генерируется приложением. */
data class EventRow(
  val id: UUID = UUID.randomUUID(),
  val flowId: UUID? = null,
  val title: String,
  val description: String? = null,
  val organizer: String? = null,
  val city: String? = null,
  val isFree: Boolean? = null,
  val price: String? = null,
  val formats: List<String> = emptyList(),
  val address: String? = null,
  val venueName: String? = null,
  val startsAt: Instant,
  val endsAt: Instant? = null,
  val talks: JsonNode? = null,
  val registrationUrl: String? = null,
  val sourceUrls: JsonNode? = null,
  val language: String? = null,
  val confidence: Double? = null,
  val raw: JsonNode? = null,
  val embedding: FloatArray? = null,
)

/** Кандидат на дубль из векторного поиска. */
data class DuplicateCandidate(
  val eventId: UUID,
  val title: String?,
  val startsAt: Instant?,
  val organizer: String?,
  val similarity: Double,
)

/**
 * Репозиторий `events`: вставка извлечённых событий + гибридный поиск дублей
 * (смысловая близость pgvector + WHERE-фильтры по дате/организатору — §9).
 */
@OptIn(ExperimentalUuidApi::class)
@Singleton
class EventRepository(private val db: DatabaseConnectivity) {

  suspend fun insert(event: EventRow): UUID {
    withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        Events.insert {
          it[id] = event.id.toKotlinUuid()
          it[flowId] = event.flowId?.toKotlinUuid()
          it[title] = event.title
          it[description] = event.description
          it[organizer] = event.organizer
          it[city] = event.city
          it[isFree] = event.isFree
          it[price] = event.price
          it[formats] = event.formats
          it[address] = event.address
          it[venueName] = event.venueName
          it[startsAt] = event.startsAt
          it[endsAt] = event.endsAt
          it[talks] = event.talks
          it[registrationUrl] = event.registrationUrl
          it[sourceUrls] = event.sourceUrls
          it[language] = event.language
          it[confidence] = event.confidence
          it[raw] = event.raw
          it[embedding] = event.embedding
        }
      }
    }
    return event.id
  }

  /** Календарь §11: окно по starts_at (по умолчанию 30 дней вперёд от now). */
  suspend fun findInRange(from: Instant?, to: Instant?, limit: Int = 100): List<EventRow> =
    withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        var query = Events.selectAll()
        if (from != null) query = query.andWhere { Events.startsAt greaterEq from }
        if (to != null) query = query.andWhere { Events.startsAt lessEq to }
        query.orderBy(Events.startsAt to org.jetbrains.exposed.v1.core.SortOrder.ASC)
          .limit(limit)
          .map { it.toEventRow() }
      }
    }

  suspend fun findById(id: UUID): EventRow? = withContext(Dispatchers.IO) {
    suspendTransaction(db.database) {
      Events.selectAll().where { Events.id eq id.toKotlinUuid() }.firstOrNull()?.toEventRow()
    }
  }

  /**
   * Топ-k ближайших по косинусной близости событий с факультативными фильтрами
   * (окно дат для дубль-чека, точный организатор). Сырой SQL: `<=>` — оператор
   * pgvector, Exposed-DSL его не знает.
   */
  suspend fun searchSimilar(
    embedding: FloatArray,
    dateFrom: Instant? = null,
    dateTo: Instant? = null,
    organizer: String? = null,
    limit: Int = DEFAULT_LIMIT,
  ): List<DuplicateCandidate> = withContext(Dispatchers.IO) {
    suspendTransaction(db.database) {
      val args = mutableListOf<Pair<IColumnType<*>, Any?>>()
      // значения-векторы передаются FloatArray: notNullValueToDB сам превратит в "[a,b,…]"
      args += VectorColumnType(embedding.size) to embedding
      val sql = buildString {
        append("SELECT id, title, starts_at, organizer, 1 - (embedding <=> ?::vector) AS similarity ")
        append("FROM events WHERE embedding IS NOT NULL")
        if (dateFrom != null && dateTo != null) {
          append(" AND starts_at BETWEEN ? AND ?")
          args += JavaInstantColumnType() to dateFrom
          args += JavaInstantColumnType() to dateTo
        }
        if (organizer != null) {
          append(" AND organizer = ?")
          args += TextColumnType() to organizer
        }
        append(" ORDER BY embedding <=> ?::vector")
        args += VectorColumnType(embedding.size) to embedding
        append(" LIMIT ?")
        args += IntegerColumnType() to limit
      }

      exec(sql, args) { rs ->
        val result = mutableListOf<DuplicateCandidate>()
        while (rs.next()) {
          result += DuplicateCandidate(
            eventId = UUID.fromString(rs.getString("id")),
            title = rs.getString("title"),
            startsAt = rs.getTimestamp("starts_at")?.toInstant(),
            organizer = rs.getString("organizer"),
            similarity = rs.getDouble("similarity"),
          )
        }
        result
      }.orEmpty()
    }
  }

  private fun ResultRow.toEventRow() = EventRow(
    id = this[Events.id].toJavaUuid(),
    flowId = this[Events.flowId]?.toJavaUuid(),
    title = this[Events.title],
    description = this[Events.description],
    organizer = this[Events.organizer],
    city = this[Events.city],
    isFree = this[Events.isFree],
    price = this[Events.price],
    formats = this[Events.formats],
    address = this[Events.address],
    venueName = this[Events.venueName],
    startsAt = this[Events.startsAt],
    endsAt = this[Events.endsAt],
    talks = this[Events.talks],
    registrationUrl = this[Events.registrationUrl],
    sourceUrls = this[Events.sourceUrls],
    language = this[Events.language],
    confidence = this[Events.confidence],
    raw = this[Events.raw],
    embedding = this[Events.embedding],
  )

  companion object {
    const val DEFAULT_LIMIT = 5
  }
}
