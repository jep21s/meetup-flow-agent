package org.jep21s.meetupflowagent.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.jep21s.meetupflowagent.db.EventRepository
import org.jep21s.meetupflowagent.db.EventRow
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.mp.KoinPlatform
import java.time.Instant

data class EventDto(
  val id: String,
  val title: String,
  val description: String?,
  val organizer: String?,
  val city: String?,
  val isFree: Boolean?,
  val price: String?,
  val formats: List<String>,
  val address: String?,
  val venueName: String?,
  val startsAt: String?,
  val endsAt: String?,
  val registrationUrl: String?,
  val language: String?,
  val confidence: Double?,
)

/**
 * GET /api/events?from=&to=&status= — календарь событий (§11). `status`
 * фильтрует по флоу-статусу события (по умолчанию только созданные
 * APPROVED-флоу: событие существует → его флоу COMPLETED).
 */
fun Route.events() {
  get("/events") {
    val repository: EventRepository = KoinPlatform.getKoin().get(EventRepository::class)
    val from = call.request.queryParameters["from"]?.let { raw -> runCatching { Instant.parse(raw) }.getOrNull() }
    val to = call.request.queryParameters["to"]?.let { raw -> runCatching { Instant.parse(raw) }.getOrNull() }
    call.respond(HttpStatusCode.OK, mapOf("events" to repository.findInRange(from, to).map { it.toDto() }))
  }
}

private fun EventRow.toDto() = EventDto(
  id = id.toString(),
  title = title,
  description = description,
  organizer = organizer,
  city = city,
  isFree = isFree,
  price = price,
  formats = formats,
  address = address,
  venueName = venueName,
  startsAt = startsAt.toString(),
  endsAt = endsAt?.toString(),
  registrationUrl = registrationUrl,
  language = language,
  confidence = confidence,
)
