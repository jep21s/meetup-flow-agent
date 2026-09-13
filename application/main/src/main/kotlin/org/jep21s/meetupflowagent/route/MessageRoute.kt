package org.jep21s.meetupflowagent.route

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.jep21s.meetupflowagent.db.FlowRepository
import org.jep21s.meetupflowagent.db.InboxRepository
import org.jep21s.meetupflowagent.flow.FlowStatus
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.mp.KoinPlatform

private val logger = KotlinLogging.logger { }

data class MessageRequestDto(
  val idempotencyKey: String? = null,
  val text: String,
  val meta: MetaDto? = null,
) {
  data class MetaDto(
    val authorUsername: String? = null,
    val chatTitle: String? = null,
    val receivedAt: String? = null,
  )
}

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
 * POST /api/messages — финальный контракт §11: асинхронный inbox-паттерн.
 * body `{idempotencyKey?, text, meta?}` → вставка в inbox (ON CONFLICT по
 * idempotency_key) + флоу PROCESSING → `202 {flowId}`; повтор idempotencyKey →
 * `409 {flowId существующего}`. Обработка — InboxPoller'ом, НЕ в потоке запроса.
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

    val inboxRepository: InboxRepository = KoinPlatform.getKoin().get(InboxRepository::class)
    val flowRepository: FlowRepository = KoinPlatform.getKoin().get(FlowRepository::class)

    when (val insert = inboxRepository.insertIfAbsent(request.idempotencyKey?.trim()?.ifEmpty { null }, text, null)) {
      is InboxRepository.InsertResult.Duplicate -> {
        call.respond(
          HttpStatusCode.Conflict,
          mapOf(
            "flowId" to (insert.existingFlowId?.toString() ?: ""),
            "error" to "Duplicate idempotency key",
          ),
        )
      }

      is InboxRepository.InsertResult.Inserted -> {
        val flowId = flowRepository.create(FlowStatus.PROCESSING.name, inboxMessageId = insert.id)
        inboxRepository.attachFlow(insert.id, flowId)
        call.respond(HttpStatusCode.Accepted, mapOf("flowId" to flowId.toString()))
      }
    }
  }
}

private const val MAX_TEXT_LENGTH = 10_000
