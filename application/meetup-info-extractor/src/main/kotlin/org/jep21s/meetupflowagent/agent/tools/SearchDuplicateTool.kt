package org.jep21s.meetupflowagent.agent.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jep21s.meetupflowagent.db.DuplicateCandidate
import org.jep21s.meetupflowagent.db.EventRepository
import org.jep21s.meetupflowagent.llm.EmbeddingClient
import org.jep21s.meetupflowagent.llm.EmbeddingException
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.core.annotation.Singleton
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Тул `search_duplicate` (§9): гибридный поиск дублей — эмбеддинг query →
 * top-k по косинусной близости (pgvector) + WHERE-фильтры (окно дат ±3д от
 * eventDate, точный организатор). Возвращает кандидатов с similarity.
 *
 * Пустой результат — тоже success («дублей не найдено»): по SOP поиск завершён,
 * флоу продолжает извлечение. Ошибки эмбеддинга/БД — [ToolResult.Error] с кодом.
 */
@Singleton
class SearchDuplicateTool(
  private val embeddingClient: EmbeddingClient,
  private val eventRepository: EventRepository,
) : AgentTool {

  override val name = "search_duplicate"

  override val description =
    "Ищет дубликаты мероприятия в календаре агента: векторный поиск по смыслу " +
      "(косинусная близость эмбеддингов) + фильтры по датам и организатору. " +
      "Вызывай ПЕРЕД финальным ответом, когда известны название и дата события " +
      "(или их уточнённые варианты); повторяй при уточнении факторов."

  override val parametersSchema: ObjectNode = JsonNodeFactory.instance.objectNode().apply {
    put("type", "object")
    putObject("properties").apply {
      putObject("query").apply {
        put("type", "string")
        put("description", "Поисковое представление события: «название | организатор | дата | площадка»")
      }
      putObject("eventDate").apply {
        put("type", "string")
        put("description", "Дата мероприятия YYYY-MM-DD — включает окно поиска ±3 дня")
      }
      putObject("organizer").apply {
        put("type", "string")
        put("description", "Точное имя организатора для фильтра (если известно)")
      }
    }
    putArray("required").add("query")
  }

  override suspend fun execute(args: JsonNode): ToolResult {
    val query = args.path("query").asText("").trim()
    if (query.isEmpty()) {
      return ToolResult.Error("Обязательный параметр 'query' отсутствует", "INVALID_ARGS")
    }

    val eventDateRaw = args.path("eventDate").asText("").trim()
    val eventDate = if (eventDateRaw.isEmpty()) null else runCatching {
      LocalDate.parse(eventDateRaw)
    }.getOrNull()
    if (eventDateRaw.isNotEmpty() && eventDate == null) {
      return ToolResult.Error("eventDate должен быть датой в формате YYYY-MM-DD", "INVALID_ARGS")
    }

    val embedding = try {
      embeddingClient.embed(query)
    } catch (e: EmbeddingException) {
      return ToolResult.Error(
        "Сервис эмбеддингов недоступен (${e.category}): ${e.message?.take(200)}",
        "EMBEDDING_${e.category}",
      )
    }

    val windowDays = ConfigLoader.getProperty("dedup.dateWindowDays", "3").toLong()
    val dateFrom: Instant? = eventDate?.minusDays(windowDays)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()
    val dateTo: Instant? = eventDate?.plusDays(windowDays + 1)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()
    val organizer = args.path("organizer").asText("").trim().ifEmpty { null }

    val candidates = try {
      eventRepository.searchSimilar(
        embedding = embedding,
        dateFrom = dateFrom,
        dateTo = dateTo,
        organizer = organizer,
      )
    } catch (e: Exception) {
      return ToolResult.Error("База календаря недоступна: ${e.message?.take(200)}", "DB_UNAVAILABLE")
    }

    return ToolResult.Success(renderResult(candidates, windowDays))
  }

  private fun renderResult(candidates: List<DuplicateCandidate>, windowDays: Long): String {
    if (candidates.isEmpty()) {
      return "Кандидатов-дублей не найдено" +
        (if (windowDays > 0) " (окно ±$windowDays дн.)" else "") +
        ". Можно считать событие новым."
    }
    val payload = jacksonMapper.createObjectNode().apply {
      putArray("candidates").apply {
        candidates.forEach { c ->
          addObject().apply {
            put("title", c.title)
            put("startsAt", c.startsAt?.toString())
            put("organizer", c.organizer)
            put("similarity", c.similarity)
          }
        }
      }
      put(
        "hint",
        "similarity ≥ 0.92 — почти наверняка дубль повторно анонсированного мероприятия; " +
          "0.85–0.92 — похоже, но не точно; < 0.85 — скорее другое событие",
      )
    }
    return jacksonMapper.writeValueAsString(payload)
  }
}
