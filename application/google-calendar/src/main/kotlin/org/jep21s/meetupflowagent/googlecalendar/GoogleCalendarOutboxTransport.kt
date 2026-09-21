package org.jep21s.meetupflowagent.googlecalendar

import com.fasterxml.jackson.databind.JsonNode
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jep21s.meetupflowagent.outbox.OutboxDeliveryException
import org.jep21s.meetupflowagent.outbox.OutboxDeliveryTask
import org.jep21s.meetupflowagent.outbox.OutboxPublicationPayload
import org.jep21s.meetupflowagent.outbox.OutboxTransport
import org.jep21s.meetupflowagent.outbox.PublishedEvent
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.core.annotation.Singleton
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger { }

/**
 * Доставка публикации в Google Calendar (назначение типа "google_calendar").
 * Адрес календаря — не-секретный `calendarId` в `destinations.config`; ключ
 * сервисного аккаунта — секрет в ENV. Идемпотентность outbox at-least-once:
 * событию задаётся собственный id (`mfa-<eventId>`), повторная вставка после
 * краша получает 409 и считается успехом — дублей в календаре нет.
 */
@Singleton
class GoogleCalendarOutboxTransport(
  private val client: GoogleCalendarClient,
) : OutboxTransport {

  override val type: String = "google_calendar"

  override suspend fun deliver(task: OutboxDeliveryTask) {
    val calendarId = task.destination.config.path("calendarId").asText("").trim()
    if (calendarId.isEmpty()) {
      throw OutboxDeliveryException(
        "destination '${task.destination.name}' (google_calendar) has no calendarId in destinations.config — " +
          "cannot deliver ${task.deliveryId}",
      )
    }
    val body = buildEventBody(
      payload = task.payload,
      timezone = ConfigLoader.getProperty("google.calendar.timezone", "Europe/Moscow").trim(),
      defaultDurationMinutes = ConfigLoader.getProperty("google.calendar.default-duration-minutes", "180").toLong(),
    )
    try {
      when (val outcome = client.insertEvent(calendarId, body)) {
        is InsertOutcome.Inserted ->
          logger.info {
            "calendar event created: delivery=${task.deliveryId} calendar=$calendarId googleEventId=${outcome.googleEventId}"
          }
        InsertOutcome.AlreadyPresent ->
          logger.info { "calendar event already exists (client id) — delivery ${task.deliveryId} counted as sent" }
      }
    } catch (e: GoogleApiException) {
      throw OutboxDeliveryException(e.message ?: "google calendar insert failed", e)
    }
  }

  companion object {
    // RFC3339 с обязательными секундами и смещением с двоеточием (для UTC — Z)
    private val RFC3339 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    /**
     * Google требует id из base32hex (0-9, a-v), без дефисов — проверено живым
     * вызовом: id с дефисом отбивается 400 «Invalid resource id value».
     * hex UUID после убирания дефисов укладывается в алфавит.
     */
    fun googleEventId(eventId: String): String = "mfa" + eventId.replace("-", "").lowercase()

    /**
     * Тело Calendar API events.insert из канонического снапшота: start/end в
     * конфиг-таймзоне, при пустом endsAt — дефолтная длительность; location и
     * описание собираются из полей события; extendedProperties.private — для
     * поиска/чисток/бэкфиллов. Чистая функция — тестируется без сервера.
     */
    fun buildEventBody(
      payload: OutboxPublicationPayload,
      timezone: String = "Europe/Moscow",
      defaultDurationMinutes: Long = 180,
    ): JsonNode {
      val event = payload.event
      val zone = ZoneId.of(timezone)
      val startsAt = event.startsAt.atZone(zone)
      val endsAt = (event.endsAt ?: event.startsAt.plus(Duration.ofMinutes(defaultDurationMinutes))).atZone(zone)

      val node = jacksonMapper.createObjectNode()
      node.put("id", googleEventId(event.eventId))
      node.put("summary", event.title)
      location(event)?.let { node.put("location", it) }
      description(event)?.let { node.put("description", it) }
      node.putObject("start")
        .put("dateTime", startsAt.format(RFC3339))
        .put("timeZone", timezone)
      node.putObject("end")
        .put("dateTime", endsAt.format(RFC3339))
        .put("timeZone", timezone)
      node.putObject("extendedProperties").putObject("private").apply {
        put("source", "meetup-flow-agent")
        put("eventId", event.eventId)
        put("flowId", payload.flowId)
        put("deliveryDedupKey", payload.deliveryDedupKey)
      }
      node.putObject("reminders").put("useDefault", true)
      return node
    }

    private fun location(event: PublishedEvent): String? =
      listOfNotNull(event.venueName, event.address, event.city)
        .filter { it.isNotBlank() }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ")

    private fun description(event: PublishedEvent): String? = buildString {
      when {
        !event.registrationUrl.isNullOrBlank() -> appendLine("Регистрация: ${event.registrationUrl}")
        event.registrationNotRequired == true -> appendLine("Регистрация: не требуется")
      }
      event.organizer?.takeIf { it.isNotBlank() }?.let { appendLine("Организатор: $it") }
      event.description?.takeIf { it.isNotBlank() }?.let {
        if (isNotEmpty()) appendLine()
        appendLine(it.trim().take(2000))
      }
      if (event.tags.isNotEmpty()) {
        if (isNotEmpty()) appendLine()
        append(event.tags.joinToString(", "))
      }
    }.trim().takeIf { it.isNotEmpty() }
  }
}
