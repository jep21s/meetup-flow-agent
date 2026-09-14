package org.jep21s.meetupflowagent.route

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import org.jep21s.meetupflowagent.config.restModule
import org.jep21s.meetupflowagent.db.DeliveryStateRow
import org.jep21s.meetupflowagent.db.OutboxMessageState
import org.jep21s.meetupflowagent.db.OutboxRepository
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** GET /internal/outbox — инспекция публикаций без авторизации (как /internal/metrics). */
class OutboxRouteTest {

  private val outboxRepository: OutboxRepository = mockk(relaxed = true)

  @BeforeEach
  fun startKoinWithMocks() {
    startKoin {
      modules(module { single { outboxRepository } })
    }
  }

  @AfterEach
  fun stopKoinAfter() {
    stopKoin()
  }

  private fun ApplicationTestBuilder.installRoutes() {
    application { restModule() }
  }

  @Test
  fun `returns messages with nested deliveries and applies filters`() = testApplication {
    val flowId = UUID.randomUUID()
    val messageId = UUID.randomUUID()
    coEvery { outboxRepository.listMessages(status = "SENT", destinationName = null, limit = 100) } returns listOf(
      OutboxMessageState(
        id = messageId,
        flowId = flowId,
        eventId = UUID.randomUUID(),
        createdAt = Instant.parse("2026-09-13T10:00:00Z"),
        deliveries = listOf(
          DeliveryStateRow(
            id = UUID.randomUUID(),
            outboxMessageId = messageId,
            destinationId = UUID.randomUUID(),
            destinationName = "telegram_main",
            destinationType = "telegram_proxy",
            status = "SENT",
            attempts = 1,
            nextRetryAt = Instant.EPOCH,
            lastError = null,
            sentAt = Instant.parse("2026-09-13T10:00:05Z"),
          ),
        ),
      ),
    )

    installRoutes()
    val response = client.get("/internal/outbox?status=SENT")

    assertEquals(HttpStatusCode.OK, response.status)
    val body = jacksonMapper.readTree(response.bodyAsText())
    assertTrue(body.isArray)
    val message = body.single()
    assertEquals(flowId.toString(), message.path("flowId").asText())
    val delivery = message.path("deliveries").single()
    assertEquals("telegram_main", delivery.path("destination").asText())
    assertEquals("SENT", delivery.path("status").asText())
    assertEquals(1, delivery.path("attempts").asInt())
    assertTrue(
      delivery.path("nextRetryAt").isMissingNode || delivery.path("nextRetryAt").isNull,
      "у терминальной доставки нет nextRetryAt",
    )
    assertTrue(delivery.path("sentAt").asText().startsWith("2026-09-13T10:00:05"))
  }

  @Test
  fun `empty outbox returns empty array`() = testApplication {
    coEvery { outboxRepository.listMessages(any(), any(), any()) } returns emptyList()

    installRoutes()
    val response = client.get("/internal/outbox")

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("[]", response.bodyAsText().trim())
  }
}
