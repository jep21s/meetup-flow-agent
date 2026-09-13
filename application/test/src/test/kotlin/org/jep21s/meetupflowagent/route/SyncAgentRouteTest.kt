package org.jep21s.meetupflowagent.route

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.assertj.core.api.Assertions.assertThat
import org.jep21s.meetupflowagent.agent.SyncAgentService
import org.jep21s.meetupflowagent.agent.context.ContextProvider
import org.jep21s.meetupflowagent.agent.context.FileContextProvider
import org.jep21s.meetupflowagent.agent.context.SystemPromptBuilder
import org.jep21s.meetupflowagent.agent.tools.AgentTool
import org.jep21s.meetupflowagent.agent.tools.FetchWebPageTool
import org.jep21s.meetupflowagent.config.restModule
import org.jep21s.meetupflowagent.db.EventPersister
import org.jep21s.meetupflowagent.llm.LlmClient
import org.jep21s.meetupflowagent.testsupport.FakeChatClient
import org.jep21s.meetupflowagent.testsupport.StubEventPersister
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class SyncAgentRouteTest {

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
          single<AgentTool> { FetchWebPageTool() }
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
  fun `post messages requires token`() = testApplication {
    application { restModule() }
    val response = client.post("/api/messages") {
      contentType(ContentType.Application.Json)
      setBody("""{"text":"митап"}""")
    }
    assertThat(response.status.value).isEqualTo(401)
  }

  @Test
  fun `tool call then final answer`() = testApplication {
    // план ответов: сначала агент просит fetch_web_page, затем финальный JSON
    fake.enqueue(
      FakeChatClient.toolCall(
        name = "fetch_web_page", argumentsJson = """{"url":"https://example.com/meetup"}""",
      ),
    )
    fake.enqueue(
      FakeChatClient.text(
        """{"title":"Kotlin митап","verdict":{"status":"APPROVED","reasons":[]},"confidence":0.9}""",
      ),
    )

    application { restModule() }
    val response = client.post("/api/messages") {
      header(HttpHeaders.Authorization, "change-me-token")
      contentType(ContentType.Application.Json)
      setBody("""{"text":"Завтра митап про Kotlin, регистрация https://example.com/meetup"}""")
    }

    assertThat(response.status.value).isEqualTo(200)
    val body = response.bodyAsText()
    assertThat(body).contains("Kotlin митап")
    assertThat(body).contains("\"toolCalls\"")
    assertThat(body).contains("fetch_web_page")
    // ДЗ4: финальный ответ агента записан в память, результат отражён в DTO
    assertThat(body).contains("\"memory\"")
    assertThat(body).contains("\"type\":\"SAVED\"")
    assertThat(persister.persistedReplies).hasSize(1)
    assertThat(persister.persistedReplies.single()).contains("Kotlin митап")

    // история последнего запроса к LLM: system, user, assistant(tool_call), tool(observation);
    // финальный assistant-ответ циклом в историю уже не добавляется
    val lastRequest = fake.requests.last()
    assertThat(lastRequest.messages.map { it.role.name }).containsExactly(
      "SYSTEM", "USER", "ASSISTANT", "TOOL",
    )
    assertThat(lastRequest.messages.first { it.role.name == "TOOL" }.toolCallId).isEqualTo("call_1")
  }

  @Test
  fun `empty text rejected with 400`() = testApplication {
    application { restModule() }
    val response = client.post("/api/messages") {
      header(HttpHeaders.Authorization, "change-me-token")
      contentType(ContentType.Application.Json)
      setBody("""{"text":"   "}""")
    }
    assertThat(response.status.value).isEqualTo(400)
  }

  @Test
  fun `too long text rejected with 400`() = testApplication {
    application { restModule() }
    val response = client.post("/api/messages") {
      header(HttpHeaders.Authorization, "change-me-token")
      contentType(ContentType.Application.Json)
      setBody("""{"text":"${"x".repeat(10_001)}"}""")
    }
    assertThat(response.status.value).isEqualTo(400)
  }

  @Test
  fun `invalid json body rejected with 400`() = testApplication {
    application { restModule() }
    val response = client.post("/api/messages") {
      header(HttpHeaders.Authorization, "change-me-token")
      contentType(ContentType.Application.Json)
      setBody("not a json")
    }
    assertThat(response.status.value).isEqualTo(400)
  }
}
