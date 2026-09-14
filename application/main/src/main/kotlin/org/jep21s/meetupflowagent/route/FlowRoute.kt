package org.jep21s.meetupflowagent.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jep21s.meetupflowagent.agent.AgentStreamEvent
import org.jep21s.meetupflowagent.db.FlowRepository
import org.jep21s.meetupflowagent.db.HumanRequestRepository
import org.jep21s.meetupflowagent.db.FlowStepRepository
import org.jep21s.meetupflowagent.db.FlowStepRow
import org.jep21s.meetupflowagent.flow.AgentFlowService
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

/** Доставка публикации флоу в одно назначение (outbox, один-ко-многим). */
data class FlowDeliveryDto(
  val destination: String,
  val type: String,
  val status: String,
  val attempts: Int,
  val nextRetryAt: String?,
  val sentAt: String?,
  val lastError: String?,
)

data class FlowDetailsDto(
  val id: String,
  val status: String,
  val verdict: Map<String, Any?>?,
  val lastError: String?,
  val createdAt: String?,
  val updatedAt: String?,
  val steps: List<FlowStepDto>,
  val deliveries: List<FlowDeliveryDto> = emptyList(),
)

data class FlowResponseRequestDto(val responderUserId: Long, val answer: String)

/**
 * Флоу-эндпоинты §11:
 * - GET /api/flows/{id} — флоу + вся история шагов (включая CoT) + вердикт;
 * - POST /api/flows/{id}/responses — ответ человека: первый побеждает (202,
 *   резюм в фоне), опоздавшие — 409; все ответы пишутся в human_requests.
 */
fun Route.flows() {
  post("/flows/{id}/responses") {
    val id = try {
      UUID.fromString(call.parameters["id"])
    } catch (e: Exception) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid flow id"))
      return@post
    }
    val raw = call.receiveText()
    val request = try {
      jacksonMapper.readValue(raw, FlowResponseRequestDto::class.java)
    } catch (e: Exception) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON body: ${e.message}"))
      return@post
    }
    if (request.answer.isBlank()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Field 'answer' must not be empty"))
      return@post
    }

    val flowRepository: FlowRepository = KoinPlatform.getKoin().get(FlowRepository::class)
    val humanRequestRepository: HumanRequestRepository = KoinPlatform.getKoin().get(HumanRequestRepository::class)
    val flowService: AgentFlowService = KoinPlatform.getKoin().get(AgentFlowService::class)

    val flow = flowRepository.findById(id)
    if (flow == null) {
      call.respond(HttpStatusCode.NotFound, mapOf("error" to "Flow not found"))
      return@post
    }
    if (flow.status != "WAITING_HUMAN") {
      call.respond(
        HttpStatusCode.Conflict,
        mapOf("error" to "Flow is not waiting for human input (status=${flow.status})"),
      )
      return@post
    }

    val won = humanRequestRepository.submitAnswer(id, request.responderUserId, request.answer)
    if (!won) {
      call.respond(HttpStatusCode.Conflict, mapOf("error" to "Already answered"))
      return@post
    }

    // резюм в фоне: цикл с LLM может быть долгим, ответ клиенту — сразу 202
    val scope: CoroutineScope = KoinPlatform.getKoin().get(
      CoroutineScope::class,
      qualifier = org.koin.core.qualifier.qualifier("applicationCoroutineScope"),
    )
    scope.launch {
      try {
        flowService.resume(id, request.answer)
      } catch (e: Exception) {
        io.github.oshai.kotlinlogging.KotlinLogging.logger { }.error(e) { "flow resume failed: flowId=$id" }
      }
    }
    call.respond(HttpStatusCode.Accepted, mapOf("flowId" to id.toString(), "accepted" to true))
  }

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
    val outboxRepository: org.jep21s.meetupflowagent.db.OutboxRepository =
      KoinPlatform.getKoin().get(org.jep21s.meetupflowagent.db.OutboxRepository::class)
    val deliveries = outboxRepository.deliveriesByFlow(id)
    call.respond(
      FlowDetailsDto(
        id = flow.id.toString(),
        status = flow.status,
        verdict = flow.verdict?.let { jacksonMapper.convertValue(it, Map::class.java) as Map<String, Any?> },
        lastError = flow.lastError,
        createdAt = flow.createdAt?.toString(),
        updatedAt = flow.updatedAt?.toString(),
        steps = steps.map { it.toDto() },
        deliveries = deliveries.map {
          FlowDeliveryDto(
            destination = it.destinationName,
            type = it.destinationType,
            status = it.status,
            attempts = it.attempts,
            nextRetryAt = if (it.status == "PENDING") it.nextRetryAt.toString() else null,
            sentAt = it.sentAt?.toString(),
            lastError = it.lastError,
          )
        },
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
