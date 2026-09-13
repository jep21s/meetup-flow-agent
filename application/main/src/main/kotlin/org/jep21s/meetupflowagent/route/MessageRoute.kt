package org.jep21s.meetupflowagent.route

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeString
import kotlinx.coroutines.CancellationException
import org.jep21s.meetupflowagent.agent.AgentReply
import org.jep21s.meetupflowagent.agent.AgentStreamEvent
import org.jep21s.meetupflowagent.agent.SyncAgentService
import org.jep21s.meetupflowagent.db.EventPersister
import org.jep21s.meetupflowagent.db.PersistOutcome
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.mp.KoinPlatform

private val logger = KotlinLogging.logger { }

data class MessageRequestDto(val text: String)

data class ToolCallDto(val name: String, val args: String, val ok: Boolean, val errorCode: String? = null)

/** Результат записи ответа агента в память (ДЗ4): событие/дубль/пропуск. */
data class MemoryDto(
  val type: String,
  val eventId: String? = null,
  val flowId: String? = null,
  val existingEventId: String? = null,
  val existingTitle: String? = null,
  val similarity: Double? = null,
  val reason: String? = null,
)

data class AgentReplyDto(
  val reply: String,
  val toolCalls: List<ToolCallDto>,
  val iterations: Int,
  val limitReached: Boolean,
  val memory: MemoryDto? = null,
)

/**
 * POST /api/messages — синхронный запуск агента (этап 2): {text} → финальный ответ
 * агента + журнал вызовов тулов. Асинхронный inbox-паттерн (202 + flowId) — этап project.
 *
 * ДЗ3: с `Accept: text/event-stream` тот же POST стримит ход прогона SSE-событиями
 * (reasoning_delta/content_delta/tool_call/tool_result/final/persisted/error). Сессия
 * живёт в рамках запроса; replay-SSE по id — этап project.
 *
 * ДЗ4: после финального ответа агентского прогона ответ записывается в память
 * (календарь событий с эмбеддингами; дубль → связь с существующим событием);
 * результат отражается в `memory` (JSON) / событии `persisted` (SSE).
 *
 * Сервисы резолвятся лениво из Koin внутри handler'а: restModule() используется
 * тестами без поднятого Koin (например, RootRouteTest).
 */
fun Route.messages() {
  post("/messages") {
    val raw = call.receiveText()
    val request = try {
      jacksonMapper.readValue(raw, MessageRequestDto::class.java)
    } catch (e: Exception) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON body: ${e.message}"))
      return@post
    }

    val text = request.text.trim()
    when {
      text.isEmpty() -> call.respond(
        HttpStatusCode.BadRequest,
        mapOf("error" to "Field 'text' must not be empty"),
      ).let { return@post }

      text.length > MAX_TEXT_LENGTH -> call.respond(
        HttpStatusCode.BadRequest,
        mapOf("error" to "Field 'text' must not exceed $MAX_TEXT_LENGTH characters"),
      ).let { return@post }
    }

    val agentService: SyncAgentService = KoinPlatform.getKoin().get(SyncAgentService::class)
    val persister: EventPersister = KoinPlatform.getKoin().get(EventPersister::class)
    if (call.wantsEventStream()) {
      call.respondAgentStream(agentService, persister, text)
    } else {
      val reply = agentService.process(text)
      val outcome = persister.persist(reply.reply)
      call.respond(reply.toDto(outcome))
    }
  }
}

private fun ApplicationCall.wantsEventStream(): Boolean =
  request.headers["Accept"]?.contains(ContentType.Text.EventStream.toString()) == true

/**
 * Стримит ход агентского прогона как SSE. Ошибки после начала стрима (статус уже
 * отправлен) превращаются в завершающее событие `error`, а не в HTTP 5xx.
 * После `final` — событие `persisted` (результат записи в память).
 */
private suspend fun ApplicationCall.respondAgentStream(
  agentService: SyncAgentService,
  persister: EventPersister,
  text: String,
) {
  respondBytesWriter(contentType = ContentType.Text.EventStream) {
    try {
      val reply = agentService.processStream(text) { event ->
        writeSseEvent(event)
        flush()
      }
      val outcome = persister.persist(reply.reply)
      writeSseEvent(AgentStreamEvent.Persisted(outcome))
      flush()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logger.error(e) { "agent stream failed: ${e::class.simpleName}" }
      runCatching {
        writeSseEvent(AgentStreamEvent.Failure(e.message ?: e::class.simpleName ?: "error"))
        flush()
      }
    }
  }
}

private suspend fun ByteWriteChannel.writeSseEvent(event: AgentStreamEvent) {
  writeString("event: ${event.sseName()}\n")
  writeString("data: ${jacksonMapper.writeValueAsString(event.ssePayload())}\n\n")
}

private fun AgentStreamEvent.sseName(): String = when (this) {
  is AgentStreamEvent.ReasoningDelta -> "reasoning_delta"
  is AgentStreamEvent.ContentDelta -> "content_delta"
  is AgentStreamEvent.ToolCall -> "tool_call"
  is AgentStreamEvent.ToolResult -> "tool_result"
  is AgentStreamEvent.Final -> "final"
  is AgentStreamEvent.Persisted -> "persisted"
  is AgentStreamEvent.Failure -> "error"
}

private fun AgentStreamEvent.ssePayload(): Any = when (this) {
  is AgentStreamEvent.ReasoningDelta -> mapOf("text" to text)
  is AgentStreamEvent.ContentDelta -> mapOf("text" to text)
  is AgentStreamEvent.ToolCall -> mapOf("name" to name, "arguments" to arguments)
  is AgentStreamEvent.ToolResult -> mapOf("ok" to ok, "text" to text, "code" to errorCode)
  is AgentStreamEvent.Final -> reply.toDto()
  is AgentStreamEvent.Persisted -> outcome.toDto()
  is AgentStreamEvent.Failure -> mapOf("message" to message)
}

private fun AgentReply.toDto(memoryOutcome: PersistOutcome? = null) = AgentReplyDto(
  reply = reply,
  toolCalls = toolCalls.map { ToolCallDto(it.name, it.args, it.ok, it.errorCode) },
  iterations = iterations,
  limitReached = limitReached,
  memory = memoryOutcome?.toDto(),
)

private fun PersistOutcome.toDto(): MemoryDto = when (this) {
  is PersistOutcome.Saved -> MemoryDto(
    type = "SAVED",
    eventId = eventId.toString(),
    flowId = flowId.toString(),
  )

  is PersistOutcome.Duplicate -> MemoryDto(
    type = "DUPLICATE",
    existingEventId = existingEventId.toString(),
    existingTitle = existingTitle,
    similarity = similarity,
  )

  is PersistOutcome.Skipped -> MemoryDto(type = "SKIPPED", reason = reason)
}

private const val MAX_TEXT_LENGTH = 10_000
