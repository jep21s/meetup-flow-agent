package org.jep21s.meetupflowagent.route

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.jep21s.meetupflowagent.config.restModule
import org.jep21s.meetupflowagent.db.FlowRepository
import org.jep21s.meetupflowagent.db.FlowStepRepository
import org.jep21s.meetupflowagent.db.FlowStepRow
import org.jep21s.meetupflowagent.db.FlowStepType
import org.jep21s.meetupflowagent.flow.AgentFlowService
import org.jep21s.meetupflowagent.flow.FlowResult
import org.jep21s.meetupflowagent.flow.FlowStatus
import org.jep21s.meetupflowagent.domain.VerdictStatus
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.time.Instant
import java.util.UUID

class FlowRouteTest {

  private val flowService: AgentFlowService = mockk()
  private val flowRepository: FlowRepository = mockk()
  private val flowStepRepository: FlowStepRepository = mockk()

  @BeforeEach
  fun startKoinWithMocks() {
    startKoin {
      modules(
        module {
          single { flowService }
          single { flowRepository }
          single { flowStepRepository }
        },
      )
    }
  }

  @AfterEach
  fun stopKoinAfter() {
    stopKoin()
  }

  @Test
  fun `post messages returns flow result`() = testApplication {
    val flowId = UUID.randomUUID()
    val eventId = UUID.randomUUID()
    coEvery { flowService.run(any(), any()) } coAnswers {
      FlowResult(
        flowId = flowId,
        status = FlowStatus.COMPLETED,
        verdictStatus = VerdictStatus.APPROVED,
        reasons = emptyList(),
        eventId = eventId,
        reply = """{"title":"PiterJS #61"}""",
        iterations = 2,
      )
    }

    application { restModule() }
    val response = client.post("/api/messages") {
      header(HttpHeaders.Authorization, "change-me-token")
      contentType(ContentType.Application.Json)
      setBody("""{"text":"митап PiterJS 2 октября"}""")
    }

    assertThat(response.status.value).isEqualTo(200)
    val body = jacksonMapper.readTree(response.bodyAsText())
    assertThat(body.path("flowId").asText()).isEqualTo(flowId.toString())
    assertThat(body.path("status").asText()).isEqualTo("COMPLETED")
    assertThat(body.path("verdict").path("status").asText()).isEqualTo("APPROVED")
    assertThat(body.path("eventId").asText()).isEqualTo(eventId.toString())
    assertThat(body.path("iterations").asInt()).isEqualTo(2)
  }

  @Test
  fun `post messages requires token`() = testApplication {
    application { restModule() }
    val response = client.post("/api/messages") {
      contentType(ContentType.Application.Json)
      setBody("""{"text":"митап"}""")
    }
    assertThat(response.status.value).isEqualTo(401)
  }

  @Test
  fun `post messages streams flow events and final carries flow result`() = testApplication {
    coEvery { flowService.run(any(), any()) } coAnswers {
      val emit = secondArg<suspend (org.jep21s.meetupflowagent.agent.AgentStreamEvent) -> Unit>()
      emit(org.jep21s.meetupflowagent.agent.AgentStreamEvent.ReasoningDelta("думаю"))
      emit(org.jep21s.meetupflowagent.agent.AgentStreamEvent.ToolCall("search_duplicate", "{}"))
      emit(org.jep21s.meetupflowagent.agent.AgentStreamEvent.ToolResult(true, "не найдено", null))
      emit(org.jep21s.meetupflowagent.agent.AgentStreamEvent.ContentDelta("{\"title\""))
      emit(
        org.jep21s.meetupflowagent.agent.AgentStreamEvent.Final(
          FlowResult(
            flowId = UUID.randomUUID(),
            status = FlowStatus.COMPLETED,
            verdictStatus = VerdictStatus.APPROVED,
            reasons = emptyList(),
          ),
        ),
      )
      FlowResult(
        flowId = UUID.randomUUID(),
        status = FlowStatus.COMPLETED,
        verdictStatus = VerdictStatus.APPROVED,
        reasons = emptyList(),
      )
    }

    application { restModule() }
    val response = client.post("/api/messages") {
      header(HttpHeaders.Authorization, "change-me-token")
      accept(ContentType.Text.EventStream)
      contentType(ContentType.Application.Json)
      setBody("""{"text":"митап"}""")
    }

    assertThat(response.status.value).isEqualTo(200)
    assertThat(response.contentType().toString()).contains("text/event-stream")
    val events = parseSse(response.bodyAsText())
    assertThat(events.map { it.first }).containsExactly(
      "reasoning_delta", "tool_call", "tool_result", "content_delta", "final",
    )
    val final = jacksonMapper.readTree(events.last().second)
    assertThat(final.path("status").asText()).isEqualTo("COMPLETED")
    assertThat(final.path("verdict").path("status").asText()).isEqualTo("APPROVED")
  }

  @Test
  fun `get flow returns steps ordered by seq`() = testApplication {
    val flowId = UUID.randomUUID()
    coEvery { flowRepository.findById(flowId) } returns org.jep21s.meetupflowagent.db.FlowRow(
      id = flowId,
      status = "COMPLETED",
      stateSnapshot = null,
      verdict = jacksonMapper.readTree("""{"status":"APPROVED","reasons":[]}"""),
      lastError = null,
      createdAt = Instant.parse("2026-09-14T10:00:00Z"),
      updatedAt = Instant.parse("2026-09-14T10:01:00Z"),
    )
    coEvery { flowStepRepository.stepsByFlow(flowId) } returns listOf(
      step(1, FlowStepType.REASON, """{"reasoning":"CoT шаг 1"}"""),
      step(2, FlowStepType.ACTION, """{"tool":"fetch_web_page"}"""),
      step(3, FlowStepType.OBSERVATION, """{"ok":true}"""),
      step(4, FlowStepType.FINAL, """{"reply":"{}"}"""),
    )

    application { restModule() }
    val response = client.get("/api/flows/$flowId") {
      header(HttpHeaders.Authorization, "change-me-token")
    }

    assertThat(response.status.value).isEqualTo(200)
    val body = jacksonMapper.readTree(response.bodyAsText())
    assertThat(body.path("status").asText()).isEqualTo("COMPLETED")
    assertThat(body.path("verdict").path("status").asText()).isEqualTo("APPROVED")
    val steps = body.path("steps")
    assertThat(steps.isArray).isTrue()
    assertThat(steps.map { it.path("seq").asInt() }).containsExactly(1, 2, 3, 4)
    assertThat(steps.map { it.path("type").asText() }).containsExactly(
      "REASON", "ACTION", "OBSERVATION", "FINAL",
    )
    // CoT доступен в истории шагов
    assertThat(steps[0].path("content").path("reasoning").asText()).contains("CoT")
  }

  @Test
  fun `get unknown flow returns 404`() = testApplication {
    val unknown = UUID.randomUUID()
    coEvery { flowRepository.findById(unknown) } returns null

    application { restModule() }
    val response = client.get("/api/flows/$unknown") {
      header(HttpHeaders.Authorization, "change-me-token")
    }
    assertThat(response.status.value).isEqualTo(404)
  }

  @Test
  fun `get invalid flow id returns 400`() = testApplication {
    application { restModule() }
    val response = client.get("/api/flows/not-a-uuid") {
      header(HttpHeaders.Authorization, "change-me-token")
    }
    assertThat(response.status.value).isEqualTo(400)
  }

  private fun step(seq: Int, type: FlowStepType, contentJson: String) = FlowStepRow(
    seq = seq,
    type = type,
    content = jacksonMapper.readTree(contentJson),
    tokens = null,
    latencyMs = null,
    createdAt = null,
  )

  private fun parseSse(raw: String): List<Pair<String, String>> =
    raw.split("\n\n")
      .filter { it.isNotBlank() }
      .map { frame ->
        val lines = frame.lines()
        val event = lines.first { it.startsWith("event: ") }.removePrefix("event: ")
        val data = lines.first { it.startsWith("data: ") }.removePrefix("data: ")
        event to data
      }
}
