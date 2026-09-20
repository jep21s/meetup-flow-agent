package org.jep21s.meetupflowagent.e2e

import kotlinx.coroutines.runBlocking
import org.jep21s.meetupflowagent.db.DatabaseConnectivity
import org.jep21s.meetupflowagent.db.DestinationRepository
import org.jep21s.meetupflowagent.db.EventRepository
import org.jep21s.meetupflowagent.db.EventRow
import org.jep21s.meetupflowagent.db.FlowRepository
import org.jep21s.meetupflowagent.db.LiquibaseRunner
import org.jep21s.meetupflowagent.db.OutboxRepository
import org.jep21s.meetupflowagent.googlecalendar.GoogleCalendarClient
import org.jep21s.meetupflowagent.googlecalendar.GoogleCalendarOutboxTransport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E2E доставки в РЕАЛЬНЫЙ Google Calendar (сервисный аккаунт из ENV
 * GOOGLE_CALENDAR_CREDENTIALS_JSON, локальный .env): публикация → транспорт →
 * events.insert → события в календаре; повторная доставка той же публикации —
 * 409 → успех без дубля. События удаляются после прогона (клиентские id
 * детерминированы). Календарь — GOOGLE_E2E_CALENDAR_ID, по умолчанию тестовый
 * (расшарен сервисному аккаунту). Postgres — docker-compose (localhost:5432),
 * в БД активируется изолированное назначение google_e2e, исходные строки
 * справочника восстанавливаются в @AfterAll.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoogleCalendarE2ETest {

  companion object {
    private const val DEFAULT_TEST_CALENDAR =
      "0ba222d99e678d0c462c71d7dc38a4b0e5eeff1e13cc2eb065275221027a210e@group.calendar.google.com"

    @BeforeAll
    @JvmStatic
    fun requireEnv() {
      Assumptions.assumeTrue(
        !System.getenv("GOOGLE_CALENDAR_CREDENTIALS_JSON").isNullOrBlank(),
        "GOOGLE_CALENDAR_CREDENTIALS_JSON не задан — e2e пропущен (запускать с загруженным .env)",
      )
    }
  }

  private val db = DatabaseConnectivity(LiquibaseRunner())
  private val eventRepository = EventRepository(db)
  private val flowRepository = FlowRepository(db)
  private val outboxRepository = OutboxRepository(db, DestinationRepository(db), eventRepository)
  private val client = GoogleCalendarClient() // реальные endpoints из конфига (без -D подмен)
  private val transport = GoogleCalendarOutboxTransport(client)
  private val calendarId: String =
    System.getenv("GOOGLE_E2E_CALENDAR_ID")?.takeIf { it.isNotBlank() } ?: DEFAULT_TEST_CALENDAR

  /** Клиентские id событий, созданных прогоном — чистятся в @AfterAll. */
  private val createdEventIds = mutableListOf<String>()

  @BeforeAll
  fun isolateDestination() {
    db.dataSource.connection.use { conn ->
      conn.createStatement().use { stmt ->
        // изоляция: в локальной БД на время прогона активен только google_e2e
        stmt.execute("UPDATE destinations SET is_active = false")
        stmt.execute(
          "INSERT INTO destinations (id, type, name, config, is_active) VALUES " +
            "(gen_random_uuid(), 'google_calendar', 'google_e2e', " +
            "'{\"calendarId\":\"$calendarId\"}'::jsonb, true) " +
            "ON CONFLICT (name) DO UPDATE SET config = EXCLUDED.config, is_active = true",
        )
      }
    }
  }

  @AfterAll
  fun cleanWorkspace() {
    runBlocking {
      createdEventIds.forEach { id -> runCatching { client.deleteEvent(calendarId, id) } }
    }
    db.dataSource.connection.use { conn ->
      conn.createStatement().use { stmt ->
        stmt.execute(
          "TRUNCATE outbox_deliveries, outbox_messages, duplicates, events, flow_steps, human_requests, flows, inbox_messages",
        )
        stmt.execute("DELETE FROM destinations WHERE name = 'google_e2e'")
        stmt.execute("UPDATE destinations SET is_active = true") // вернуть исходное состояние
      }
    }
  }

  @Test
  fun `publication is delivered into real google calendar`() = runBlocking {
    val event = e2eEvent()
    val flowId = flowRepository.create("PROCESSING")
    val published = event.copy(flowId = flowId)

    val enqueued = outboxRepository.insertEventAndEnqueue(published)
    assertEquals(1, enqueued.deliveries, "активен только google_e2e")

    val task = outboxRepository.claimPending(limit = 10).single()
    transport.deliver(task) // реальный Google Calendar API
    outboxRepository.markSent(task.deliveryId)

    assertEquals("SENT", outboxRepository.deliveriesByFlow(flowId).single().status)

    val googleId = GoogleCalendarOutboxTransport.googleEventId(published.id.toString())
    createdEventIds += googleId
    val remote = client.findEvent(calendarId, googleId)
    assertNotNull(remote, "событие должно появиться в календаре")
    assertEquals(published.title, remote.path("summary").asText())
    assertEquals("Europe/Moscow", remote.path("start").path("timeZone").asText())
    assertTrue(remote.path("start").path("dateTime").asText().endsWith("+03:00"), "время в МСК")
    assertTrue(
      remote.path("location").asText().contains(published.venueName!!),
      "location содержит площадку: ${remote.path("location").asText()}",
    )
    assertEquals(
      published.id.toString(),
      remote.path("extendedProperties").path("private").path("eventId").asText(),
    )
  }

  @Test
  fun `redelivery of same publication hits 409 and stays single`() = runBlocking {
    val event = e2eEvent()
    val flowId = flowRepository.create("PROCESSING")
    outboxRepository.insertEventAndEnqueue(event.copy(flowId = flowId))

    val task = outboxRepository.claimPending(limit = 10).single()
    val googleId = GoogleCalendarOutboxTransport.googleEventId(event.id.toString())
    createdEventIds += googleId

    transport.deliver(task) // первая вставка
    transport.deliver(task.copy(deliveryId = UUID.randomUUID())) // повтор at-least-once → 409

    val remote = client.findEvent(calendarId, googleId)
    assertNotNull(remote, "событие в календаре после двух доставок")
    assertEquals(event.title, remote.path("summary").asText())
  }

  private fun e2eEvent() = EventRow(
    id = UUID.randomUUID(),
    title = "E2E Проверка календаря ${UUID.randomUUID().toString().take(8)}",
    description = "Сквозной тест доставки meetup-flow-agent → Google Calendar",
    organizer = "e2e",
    city = "Санкт-Петербург",
    isFree = true,
    formats = listOf("OFFLINE"),
    address = "Песочная наб., 14",
    venueName = "ИТМО",
    startsAt = Instant.parse("2026-12-05T13:00:00Z"),
    endsAt = Instant.parse("2026-12-05T16:00:00Z"),
    registrationUrl = "https://example.com/e2e",
  )
}
