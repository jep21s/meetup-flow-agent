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
import org.jep21s.meetupflowagent.agent.AgentStreamEvent
import org.jep21s.meetupflowagent.flow.AgentFlowService
import org.jep21s.meetupflowagent.flow.FlowResult
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.mp.KoinPlatform

private val logger = KotlinLogging.logger { }

data class MessageRequestDto(val text: String)

data class ToolCallDto(val name: String, val args: String, val ok: Boolean, val errorCode: String? = null)

/** Ответ POST /api/messages (этап 5): итог управляемого флоу. */
data class FlowResultDto(
  val flowId: String,
  val status: String,
  val verdict: VerdictDto,
  val eventId: String? = null,
  val duplicateOf: String? = null,
  val similarity: Double? = null,
  val reply: String? = null,
  val toolCalls: List<ToolCallDto> = emptyList(),
  val iterations: Int,
  val limitReached: Boolean,
)

data class VerdictDto(val status: String, val reasons: List<String>)

/**
 * POST /api/messages — запуск управляемого флоу (этап 5): создать flow →
 * прогнать 5-шаговый сценарий (цикл с тулами → финальный JSON → пост-валидация
 * → вердикт/статус) → `200 {flowId, status, verdict, eventId?, …}`.
 * Синхронно; inbox-паттерн (202) — этап project.
 *
 * ДЗ3-совместимость: с `Accept: text/event-stream` тот же POST стримит ход
 * флоу (reasoning_delta/content_delta/tool_call/tool_result/final/error);
 * событие `final` несёт тот же FlowResult.
 *
 * Сервис резолвится лениво из Koin внутри handler'а: restModule() используется
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

    val flowService: AgentFlowService = KoinPlatform.getKoin().get(AgentFlowService::class)
    if (call.wantsEventStream()) {
      call.respondAgentStream(flowService, text)
    } else {
      val result = flowService.run(text)
      call.respond(result.toDto())
    }
  }
}

private fun ApplicationCall.wantsEventStream(): Boolean =
  request.headers["Accept"]?.contains(ContentType.Text.EventStream.toString()) == true

/**
 * Стримит ход флоу как SSE. Ошибки после начала стрима (статус уже отправлен)
 * превращаются в завершающее событие `error`, а не в HTTP 5xx.
 */
private suspend fun ApplicationCall.respondAgentStream(flowService: AgentFlowService, text: String) {
  respondBytesWriter(contentType = ContentType.Text.EventStream) {
    try {
      flowService.run(text) { event ->
        writeSseEvent(event)
        flush()
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logger.error(e) { "agent flow stream failed: ${e::class.simpleName}" }
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
  is AgentStreamEvent.Failure -> "error"
}

private fun AgentStreamEvent.ssePayload(): Any = when (this) {
  is AgentStreamEvent.ReasoningDelta -> mapOf("text" to text)
  is AgentStreamEvent.ContentDelta -> mapOf("text" to text)
  is AgentStreamEvent.ToolCall -> mapOf("name" to name, "arguments" to arguments)
  is AgentStreamEvent.ToolResult -> mapOf("ok" to ok, "text" to text, "code" to errorCode)
  is AgentStreamEvent.Final -> result.toDto()
  is AgentStreamEvent.Failure -> mapOf("message" to message)
}

fun FlowResult.toDto() = FlowResultDto(
  flowId = flowId.toString(),
  status = status.name,
  verdict = VerdictDto(status = verdictStatus.name, reasons = reasons),
  eventId = eventId?.toString(),
  duplicateOf = duplicateOf?.toString(),
  similarity = similarity,
  reply = reply.ifBlank { null },
  toolCalls = toolCalls.map { ToolCallDto(it.name, it.args, it.ok, it.errorCode) },
  iterations = iterations,
  limitReached = limitReached,
)

private const val MAX_TEXT_LENGTH = 10_000
