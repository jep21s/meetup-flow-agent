package org.jep21s.meetupflowagent.route

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.assertj.core.api.Assertions.assertThat
import org.jep21s.meetupflowagent.agent.SyncAgentService
import org.jep21s.meetupflowagent.agent.context.ContextProvider
import org.jep21s.meetupflowagent.agent.context.FileContextProvider
import org.jep21s.meetupflowagent.agent.context.SystemPromptBuilder
import org.jep21s.meetupflowagent.agent.tools.AgentTool
import org.jep21s.meetupflowagent.agent.tools.ToolResult
import org.jep21s.meetupflowagent.config.restModule
import org.jep21s.meetupflowagent.db.EventPersister
import org.jep21s.meetupflowagent.llm.LlmClient
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.jep21s.meetupflowagent.testsupport.FakeChatClient
import org.jep21s.meetupflowagent.testsupport.StubEventPersister
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class SyncAgentStreamRouteTest {

  private lateinit var fake: FakeChatClient
  private lateinit var persister: StubEventPersister

  @BeforeEach
  fun startKoinWithFakes() {
    fake = FakeChatClient()
    persister = StubEventPersister.saved()
    startKoin {
      modules(
        module {
          single<LlmClient> { fake }
          // стабильный тул вместо реального fetch_web_page: ноль внешних вызовов в тестах
          single<AgentTool> { StubFetchTool() }
          single<ContextProvider> { FileContextProvider() }
          single { SystemPromptBuilder(get()) }
          single { SyncAgentService(get(), getAll(), get()) }
          single<EventPersister> { persister }
        },
      )
    }
  }

  @AfterEach
  fun stopKoinAfter() {
    stopKoin()
  }

  @Test
  fun `sse stream emits tool call, tool result, deltas and final in order`() = testApplication {
    fake.enqueue(
      FakeChatClient.toolCall(
        name = "fetch_web_page", argumentsJson = """{"url":"https://example.com/meetup"}""",
      ),
    )
    fake.enqueue(
      FakeChatClient.text(
        """{"title":"Kotlin митап","verdict":{"status":"APPROVED","reasons":[]},"confidence":0.9}""",
        reasoning = "нужно открыть страницу",
      ),
    )

    application { restModule() }
    val response = client.post("/api/messages") {
      header(HttpHeaders.Authorization, "change-me-token")
      accept(ContentType.Text.EventStream)
      contentType(ContentType.Application.Json)
      setBody("""{"text":"митап по ссылке https://example.com/meetup"}""")
    }

    assertThat(response.status.value).isEqualTo(200)
    assertThat(response.contentType().toString()).contains("text/event-stream")

    val events = parseSse(response.bodyAsText())
    assertThat(events.map { it.first }).containsExactly(
      "tool_call", "tool_result", "reasoning_delta", "content_delta", "final", "persisted",
    )

    val toolCall = events[0].second
    assertThat(toolCall).contains("\"name\":\"fetch_web_page\"")
    assertThat(toolCall).contains("example.com/meetup")

    val toolResult = jacksonMapper.readTree(events[1].second)
    assertThat(toolResult.path("ok").asBoolean()).isTrue()
    assertThat(toolResult.path("text").asText()).isEqualTo(StubFetchTool.RESULT_TEXT)

    assertThat(events[2].second).isEqualTo("""{"text":"нужно открыть страницу"}""")

    val final = jacksonMapper.readTree(events[4].second)
    assertThat(final.path("reply").asText()).contains("Kotlin митап")
    assertThat(final.path("iterations").asInt()).isEqualTo(2)
    assertThat(final.path("toolCalls").first().path("ok").asBoolean()).isTrue()

    // ДЗ4: после final — событие persisted с результатом записи в память
    val persisted = jacksonMapper.readTree(events[5].second)
    assertThat(persisted.path("type").asText()).isEqualTo("SAVED")
    assertThat(persisted.path("eventId").asText()).isNotEmpty
    assertThat(persister.persistedReplies).hasSize(1)

    // стриминговый сценарий ходит через streamChat: запросы те же, история полная
    val lastRequest = fake.requests.last()
    assertThat(lastRequest.messages.map { it.role.name }).containsExactly(
      "SYSTEM", "USER", "ASSISTANT", "TOOL",
    )
  }

  @Test
  fun `sse stream without tools streams reasoning, content and final only`() = testApplication {
    fake.enqueue(
      FakeChatClient.text(
        """{"verdict":{"status":"REJECTED","reasons":["OFF_TOPIC"]}}""",
        reasoning = "сообщение не о мероприятии",
      ),
    )

    application { restModule() }
    val response = client.post("/api/messages") {
      header(HttpHeaders.Authorization, "change-me-token")
      accept(ContentType.Text.EventStream)
      contentType(ContentType.Application.Json)
      setBody("""{"text":"привет, как дела?"}""")
    }

    val events = parseSse(response.bodyAsText())
    assertThat(events.map { it.first }).containsExactly("reasoning_delta", "content_delta", "final", "persisted")
  }

  @Test
  fun `mid-flow failure becomes error event, not http 5xx`() = testApplication {
    // скрипт пуст: FakeChatClient бросит IllegalStateException на первом вызове
    application { restModule() }
    val response = client.post("/api/messages") {
      header(HttpHeaders.Authorization, "change-me-token")
      accept(ContentType.Text.EventStream)
      contentType(ContentType.Application.Json)
      setBody("""{"text":"митап"}""")
    }

    assertThat(response.status.value).isEqualTo(200)
    val events = parseSse(response.bodyAsText())
    assertThat(events.map { it.first }).containsExactly("error")
    assertThat(events[0].second).contains("script exhausted")
  }

  @Test
  fun `post without event-stream accept keeps json response`() = testApplication {
    fake.enqueue(FakeChatClient.text("""{"ok":true}"""))

    application { restModule() }
    val response = client.post("/api/messages") {
      header(HttpHeaders.Authorization, "change-me-token")
      accept(ContentType.Application.Json)
      contentType(ContentType.Application.Json)
      setBody("""{"text":"митап"}""")
    }

    assertThat(response.status.value).isEqualTo(200)
    assertThat(response.contentType().toString()).contains("application/json")
    assertThat(response.bodyAsText()).contains("\"reply\"")
  }

  private fun parseSse(raw: String): List<Pair<String, String>> =
    raw.split("\n\n")
      .filter { it.isNotBlank() }
      .map { frame ->
        val lines = frame.lines()
        val event = lines.first { it.startsWith("event: ") }.removePrefix("event: ")
        val data = lines.first { it.startsWith("data: ") }.removePrefix("data: ")
        event to data
      }

  private class StubFetchTool : AgentTool {
    override val name = "fetch_web_page"
    override val description = "Тестовый стабиль веб-тула"
    override val parametersSchema: ObjectNode = JsonNodeFactory.instance.objectNode()

    override suspend fun execute(args: JsonNode): ToolResult =
      ToolResult.Success(RESULT_TEXT)

    companion object {
      const val RESULT_TEXT = "Страница митапа: Kotlin митап, 25 сентября, Севкабель Порт"
    }
  }
}
