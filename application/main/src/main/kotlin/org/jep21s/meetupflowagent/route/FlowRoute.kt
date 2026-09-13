package org.jep21s.meetupflowagent.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.jep21s.meetupflowagent.db.FlowRepository
import org.jep21s.meetupflowagent.db.FlowStepRepository
import org.jep21s.meetupflowagent.db.FlowStepRow
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.mp.KoinPlatform
import java.util.UUID

/** Шаг флоу в ответе API (content — JSON как есть, включая CoT REASON-шагов). */
data class FlowStepDto(
  val seq: Int,
  val type: String,
  val content: Map<String, Any?>,
  val tokens: Int?,
  val latencyMs: Int?,
)

data class FlowDetailsDto(
  val id: String,
  val status: String,
  val verdict: Map<String, Any?>?,
  val lastError: String?,
  val createdAt: String?,
  val updatedAt: String?,
  val steps: List<FlowStepDto>,
)

/**
 * GET /api/flows/{id} — флоу + вся история шагов (включая CoT из REASON) +
 * вердикт; 404 если флоу нет (§11, упрощённо для этапа 5: без eventId-поля —
 * событие ищется по events.flow_id на этапе project).
 */
fun Route.flows() {
  get("/flows/{id}") {
    val id = try {
      UUID.fromString(call.parameters["id"])
    } catch (e: Exception) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid flow id"))
      return@get
    }

    val flowRepository: FlowRepository = KoinPlatform.getKoin().get(FlowRepository::class)
    val stepRepository: FlowStepRepository = KoinPlatform.getKoin().get(FlowStepRepository::class)
    val flow = flowRepository.findById(id)
    if (flow == null) {
      call.respond(HttpStatusCode.NotFound, mapOf("error" to "Flow not found"))
      return@get
    }

    val steps = stepRepository.stepsByFlow(id)
    call.respond(
      FlowDetailsDto(
        id = flow.id.toString(),
        status = flow.status,
        verdict = flow.verdict?.let { jacksonMapper.convertValue(it, Map::class.java) as Map<String, Any?> },
        lastError = flow.lastError,
        createdAt = flow.createdAt?.toString(),
        updatedAt = flow.updatedAt?.toString(),
        steps = steps.map { it.toDto() },
      ),
    )
  }
}

private fun FlowStepRow.toDto() = FlowStepDto(
  seq = seq,
  type = type.name,
  content = jacksonMapper.convertValue(content, Map::class.java) as Map<String, Any?>,
  tokens = tokens,
  latencyMs = latencyMs,
)
