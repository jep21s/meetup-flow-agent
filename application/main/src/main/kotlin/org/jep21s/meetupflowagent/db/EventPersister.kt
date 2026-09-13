package org.jep21s.meetupflowagent.db

import com.fasterxml.jackson.databind.JsonNode
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jep21s.meetupflowagent.llm.EmbeddingClient
import org.jep21s.meetupflowagent.llm.EmbeddingException
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.core.annotation.Singleton
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

private val logger = KotlinLogging.logger { }

/** Результат записи ответа агента в память (календарь событий). */
sealed interface PersistOutcome {
  data class Saved(val eventId: UUID, val flowId: UUID) : PersistOutcome
  data class Duplicate(val existingEventId: UUID, val existingTitle: String?, val similarity: Double) : PersistOutcome
  data class Skipped(val reason: String) : PersistOutcome
}

/** Запоминающий слой: финальный JSON агента → БД (событие/дубль/пропуск). */
interface EventPersister {
  suspend fun persist(agentReply: String): PersistOutcome
}

/**
 * Best-effort персист извлечённого события (строгая валидация контракта — этап 5):
 * финальный JSON агента → flow → дубль-чек (embedding + pgvector ±окно дат) →
 * insert события ИЛИ запись дубликата со связью на существующее событие.
 *
 * Шаги не в одной транзакции (идемпотентность/резюм — этап 5-6); ошибка любого
 * шага не роняет ответ пользователю — возвращается Skipped с причиной.
 */
@Singleton(binds = [EventPersister::class])
class DbEventPersister(
  private val embeddingClient: EmbeddingClient,
  private val eventRepository: EventRepository,
  private val flowRepository: FlowRepository,
) : EventPersister {

  override suspend fun persist(agentReply: String): PersistOutcome {
    val root: JsonNode = try {
      jacksonMapper.readTree(agentReply)
    } catch (e: Exception) {
      return PersistOutcome.Skipped("ответ агента не является JSON (${e.message?.take(120)})")
    }
    if (!root.isObject) {
      return PersistOutcome.Skipped("ответ агента не JSON-объект: ${root.nodeType}")
    }

    val verdict = root.path("verdict").takeIf { it.isObject }
    val verdictStatus = root.path("verdict").path("status").asText("").trim().uppercase()
    val flowId = flowRepository.create(status = "PROCESSING", verdict = verdict)

    if (verdictStatus != "APPROVED") {
      flowRepository.updateStatus(flowId, "REJECTED")
      return PersistOutcome.Skipped("verdict=${verdictStatus.ifEmpty { "отсутствует" }} — событие не создаётся")
    }

    val title = root.path("title").asText("").trim()
    val startsAt = parseInstant(root.path("startsAt").asText("").trim())
    if (title.isEmpty() || startsAt == null) {
      flowRepository.updateStatus(flowId, "REJECTED", lastError = "нет обязательных полей: title/startsAt")
      return PersistOutcome.Skipped("нет обязательных полей (title, startsAt) — событие не восстановимо")
    }

    val organizer = root.textOrNull("organizer")
    val embeddingText = embeddingText(title, organizer, startsAt, root.textOrNull("venueName"))
    val embedding = try {
      embeddingClient.embed(embeddingText)
    } catch (e: EmbeddingException) {
      flowRepository.updateStatus(flowId, "WAITING_RETRY", lastError = "embedding unavailable: ${e.category}")
      return PersistOutcome.Skipped("эмбеддинг недоступен (${e.category}) — повтор будет позже")
    }

    val windowDays = ConfigLoader.getProperty("dedup.dateWindowDays", "3").toLong()
    val candidates = eventRepository.searchSimilar(
      embedding = embedding,
      dateFrom = startsAt.minusSeconds(windowDays * 24 * 3600),
      dateTo = startsAt.plusSeconds(windowDays * 24 * 3600),
    )
    val top = candidates.firstOrNull()
    val thresholdHigh = ConfigLoader.getProperty("dedup.thresholdHigh", "0.92").toDouble()
    if (top != null && top.similarity >= thresholdHigh) {
      flowRepository.insertDuplicate(flowId, top.eventId, top.similarity, decidedBy = "AGENT")
      flowRepository.updateStatus(flowId, "DUPLICATE")
      logger.info { "duplicate detected: flowId=$flowId existingEventId=${top.eventId} similarity=${top.similarity}" }
      return PersistOutcome.Duplicate(top.eventId, top.title, top.similarity)
    }

    val eventId = eventRepository.insert(toEventRow(root, title, startsAt, flowId, embedding))
    flowRepository.updateStatus(flowId, "COMPLETED")
    logger.info { "event persisted: flowId=$flowId eventId=$eventId title=\"$title\"" }
    return PersistOutcome.Saved(eventId, flowId)
  }

  /** Нормализованное представление для эмбеддинга (§8.5): title | organizer | дата | venue. */
  private fun embeddingText(title: String, organizer: String?, startsAt: Instant, venue: String?): String =
    listOfNotNull(title, organizer, startsAt.atZone(ZoneOffset.UTC).toLocalDate().toString(), venue)
      .filter { it.isNotBlank() }
      .joinToString(" | ")

  private fun toEventRow(root: JsonNode, title: String, startsAt: Instant, flowId: UUID, embedding: FloatArray) =
    EventRow(
      flowId = flowId,
      title = title,
      description = root.textOrNull("description"),
      organizer = root.textOrNull("organizer"),
      city = root.textOrNull("city"),
      isFree = root.path("isFree").let { if (it.isBoolean) it.asBoolean() else null },
      price = root.textOrNull("price"),
      formats = root.path("formats").mapNotNull { if (it.isTextual) it.asText() else null },
      address = root.textOrNull("address"),
      venueName = root.textOrNull("venueName"),
      startsAt = startsAt,
      endsAt = parseInstant(root.path("endsAt").asText("").trim()),
      talks = root.path("talks").takeIf { it.isArray && it.size() > 0 },
      registrationUrl = root.textOrNull("registrationUrl"),
      sourceUrls = root.path("sourceUrls").takeIf { it.isArray && it.size() > 0 },
      language = root.textOrNull("language"),
      confidence = root.path("confidence").let { if (it.isNumber) it.asDouble() else null },
      raw = root,
      embedding = embedding,
    )

  /** ISO-instant с офсетом или Z («2026-10-02T18:30+03:00»). */
  private fun parseInstant(raw: String): Instant? {
    if (raw.isEmpty()) return null
    return runCatching { Instant.parse(raw) }.getOrElse {
      runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
    }
  }

  private fun JsonNode.textOrNull(name: String): String? =
    path(name).takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()
}
