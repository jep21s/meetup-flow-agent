package org.jep21s.meetupflowagent.route

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.utils.io.writeString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jep21s.meetupflowagent.db.FlowRepository
import org.jep21s.meetupflowagent.db.FlowStepRepository
import org.jep21s.meetupflowagent.db.FlowStepRow
import org.jep21s.meetupflowagent.flow.FlowEventBus
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.mp.KoinPlatform
import java.util.UUID

private val logger = KotlinLogging.logger { }

/**
 * GET /api/flows/{id}/stream — SSE флоу (§11): replay шагов из flow_steps
 * (seq > Last-Event-ID), затем live-события через FlowEventBus; heartbeat
 * каждые 15с; при WAITING_HUMAN — маркер waiting_human и закрытие соединения;
 * при терминальном статусе без live-событий — final из БД и закрытие.
 */
fun Route.flowStream() {
  get("/flows/{id}/stream") {
    val flowId = try {
      UUID.fromString(call.parameters["id"])
    } catch (e: Exception) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid flow id"))
      return@get
    }

    val flowRepository: FlowRepository = KoinPlatform.getKoin().get(FlowRepository::class)
    val stepRepository: FlowStepRepository = KoinPlatform.getKoin().get(FlowStepRepository::class)
    val eventBus: FlowEventBus = KoinPlatform.getKoin().get(FlowEventBus::class)

    val flow = flowRepository.findById(flowId)
    if (flow == null) {
      call.respond(HttpStatusCode.NotFound, mapOf("error" to "Flow not found"))
      return@get
    }

    val lastEventId = call.request.headers["Last-Event-ID"]?.toIntOrNull() ?: 0

    call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
      try {
        // replay: шаги строго после lastEventId
        stepRepository.stepsByFlow(flowId)
          .filter { it.seq > lastEventId }
          .forEach { step -> writeStep(step); flush() }

        when {
          flow.status == "WAITING_HUMAN" -> {
            writeEvent("waiting_human", mapOf("flowId" to flowId.toString()))
            flush()
            return@respondBytesWriter
          }

          flow.status in TERMINAL_STATUSES -> {
            writeEvent(
              "final",
              mapOf(
                "flowId" to flowId.toString(),
                "status" to flow.status,
                "verdict" to flow.verdict,
              ),
            )
            flush()
            return@respondBytesWriter
          }
        }

        // live: подписка + heartbeat (до final/waiting_human)
        kotlinx.coroutines.coroutineScope {
          val done = kotlinx.coroutines.CompletableDeferred<Unit>()
          val live = launch {
            eventBus.flowFor(flowId).collect { event ->
              writeEvent(event.type, event.payload ?: emptyMap<String, Any>(), event.seq)
              flush()
              if (event.type == "final" || event.type == "waiting_human") {
                done.complete(Unit)
              }
            }
          }
          launch {
            while (!done.isCompleted) {
              delay(15_000)
              if (!done.isCompleted) {
                writeEvent("heartbeat", emptyMap<String, Any>())
                flush()
              }
            }
          }
          done.await()
          live.cancel()
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.error(e) { "flow stream failed: flowId=$flowId" }
      }
    }
  }
}

private suspend fun io.ktor.utils.io.ByteWriteChannel.writeStep(step: FlowStepRow) {
  writeEvent("step", mapOf("type" to step.type.name, "content" to step.content), step.seq)
}

private suspend fun io.ktor.utils.io.ByteWriteChannel.writeEvent(event: String, data: Any?, id: Int? = null) {
  id?.let { writeString("id: $it\n") }
  writeString("event: $event\n")
  writeString("data: ${jacksonMapper.writeValueAsString(data)}\n\n")
}

private val TERMINAL_STATUSES = setOf("COMPLETED", "REJECTED", "DUPLICATE", "EXPIRED", "FAILED_PERMANENT")
