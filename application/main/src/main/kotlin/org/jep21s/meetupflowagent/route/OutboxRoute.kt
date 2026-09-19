package org.jep21s.meetupflowagent.route

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.jep21s.meetupflowagent.db.OutboxRepository
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.mp.KoinPlatform

/** Сообщение outbox + его доставки в ответе /internal/outbox. */
data class OutboxMessageDto(
  val id: String,
  val flowId: String,
  val eventId: String,
  val createdAt: String?,
  val deliveries: List<Map<String, Any?>>,
)

/**
 * GET /internal/outbox?status=&destination=&limit= — инспекция публикаций:
 * состояние доставок по всем назначениям. Роут ВНЕ /api (как /internal/metrics):
 * без авторизации, для локальной отладки и дашбордов.
 */
fun Route.internalOutbox() {
  get("/internal/outbox") {
    val repository: OutboxRepository = KoinPlatform.getKoin().get(OutboxRepository::class)
    val status = call.request.queryParameters["status"]?.takeIf { it.isNotBlank() }
    val destination = call.request.queryParameters["destination"]?.takeIf { it.isNotBlank() }
    val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
    val messages = repository.listMessages(status = status, destinationName = destination, limit = limit)
    call.respond(
      messages.map { message ->
        OutboxMessageDto(
          id = message.id.toString(),
          flowId = message.flowId.toString(),
          eventId = message.eventId.toString(),
          createdAt = message.createdAt.toString(),
          deliveries = message.deliveries.map {
            mapOf(
              "destination" to it.destinationName,
              "type" to it.destinationType,
              "status" to it.status,
              "attempts" to it.attempts,
              "nextRetryAt" to if (it.status == "PENDING") it.nextRetryAt.toString() else null,
              "sentAt" to it.sentAt?.toString(),
              "lastError" to it.lastError,
            )
          },
        )
      },
    )
  }
}
