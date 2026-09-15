package org.jep21s.meetupflowagent.telegramproxy.route

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.jep21s.meetupflowagent.starter.jackson.JacksonConfig
import org.jep21s.meetupflowagent.telegramproxy.config.UnauthorizedException
import org.jep21s.meetupflowagent.telegramproxy.config.requireTokenAuth
import org.jep21s.meetupflowagent.telegramproxy.telegram.HitlPendingStore
import org.jep21s.meetupflowagent.telegramproxy.telegram.SentTgMessage
import org.jep21s.meetupflowagent.telegramproxy.telegram.TgMessageSender
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * POST /api/notify: Bearer-авторизация, адресация (userIds → лички, fallback —
 * общий канал), кнопочный HITL + pending-стор, дедуп deliveryId, 500 при
 * полном отказе отправок.
 */
class NotifyRouteTest {

  private val sender = mockk<TgMessageSender>()
  private val pendingStore = HitlPendingStore(CoroutineScope(Dispatchers.Unconfined))
  private val mainChatId = -100200300L

  /** Тестовая копия restModule: те же плагины и роуты, но явные зависимости. */
  private fun Application.testProxyApp() {
    install(ContentNegotiation) { jackson { JacksonConfig.customizer(this) } }
    install(StatusPages) {
      exception<UnauthorizedException> { call, _ ->
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
      }
    }
    routing {
      route("/api") {
        requireTokenAuth("Bearer test-proxy-token")
        notify(sender, pendingStore, mainChatId)
      }
    }
  }

  private suspend fun ApplicationTestBuilder.postNotify(
    body: String,
    token: String? = "Bearer test-proxy-token",
  ): HttpResponse = client.post("/api/notify") {
    token?.let { header(HttpHeaders.Authorization, it) }
    contentType(ContentType.Application.Json)
    setBody(body)
  }

  @Test
  fun `no auth header - 401`() = testApplication {
    application { testProxyApp() }
    val response = postNotify("""{"flowId":"${UUID.randomUUID()}","event":"REMINDER","text":"hi"}""", token = null)
    assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
  }

  @Test
  fun `wrong bearer - 401`() = testApplication {
    application { testProxyApp() }
    val response = postNotify("""{"flowId":"${UUID.randomUUID()}","event":"REMINDER","text":"hi"}""", token = "Bearer wrong")
    assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
  }

  @Test
  fun `empty userIds - text goes to main chat`() = testApplication {
    application { testProxyApp() }
    coEvery { sender.sendText(any(), any()) } returns SentTgMessage(mainChatId, 5)
    val response = postNotify("""{"flowId":"${UUID.randomUUID()}","event":"EVENT_PUBLISHED","text":"анонс","deliveryId":"d-1"}""")
    assertThat(response.status).isEqualTo(HttpStatusCode.Accepted)
    coVerify(exactly = 1) { sender.sendText(mainChatId, "анонс") }
  }

  @Test
  fun `duplicate deliveryId - second call sends nothing`() = testApplication {
    application { testProxyApp() }
    coEvery { sender.sendText(any(), any()) } returns SentTgMessage(mainChatId, 5)
    val body = """{"flowId":"${UUID.randomUUID()}","event":"EVENT_PUBLISHED","text":"анонс","deliveryId":"d-dup"}"""
    assertThat(postNotify(body).status).isEqualTo(HttpStatusCode.Accepted)
    assertThat(postNotify(body).status).isEqualTo(HttpStatusCode.Accepted)
    coVerify(exactly = 1) { sender.sendText(any(), any()) }
  }

  @Test
  fun `HUMAN_INPUT_REQUIRED - question with buttons to every userId and pending registered`() = testApplication {
    application { testProxyApp() }
    val flowId = UUID.randomUUID()
    coEvery { sender.sendQuestion(any(), any(), any(), any()) } answers {
      SentTgMessage(firstArg(), 77)
    }
    val response = postNotify(
      """{"flowId":"$flowId","event":"HUMAN_INPUT_REQUIRED","userIds":[11,22],"text":"Какую дату?","options":["да","нет"]}"""
    )
    assertThat(response.status).isEqualTo(HttpStatusCode.Accepted)
    coVerify(exactly = 1) { sender.sendQuestion(11, flowId, "Какую дату?", listOf("да", "нет")) }
    coVerify(exactly = 1) { sender.sendQuestion(22, flowId, "Какую дату?", listOf("да", "нет")) }
    assertThat(pendingStore.find(11, 77)).isNotNull
    assertThat(pendingStore.find(22, 77)?.flowId).isEqualTo(flowId)
  }

  @Test
  fun `all sends failed - 500`() = testApplication {
    application { testProxyApp() }
    coEvery { sender.sendText(any(), any()) } returns null
    val response = postNotify("""{"flowId":"${UUID.randomUUID()}","event":"REMINDER","userIds":[11],"text":"напоминание"}""")
    assertThat(response.status).isEqualTo(HttpStatusCode.InternalServerError)
  }
}
