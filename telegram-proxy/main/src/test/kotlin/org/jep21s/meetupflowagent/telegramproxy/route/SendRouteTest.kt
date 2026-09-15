package org.jep21s.meetupflowagent.telegramproxy.route

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
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
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.jep21s.meetupflowagent.starter.jackson.JacksonConfig
import org.jep21s.meetupflowagent.telegramproxy.config.UnauthorizedException
import org.jep21s.meetupflowagent.telegramproxy.config.requireTokenAuth
import org.jep21s.meetupflowagent.telegramproxy.telegram.SendButton
import org.jep21s.meetupflowagent.telegramproxy.telegram.SentTgMessage
import org.jep21s.meetupflowagent.telegramproxy.telegram.TgMessageSender
import org.junit.jupiter.api.Test

/**
 * Команды отправки /api/send|callback-answer|message-keyboard-remove: Bearer-авторизация,
 * прокси отправляет не решая; все попытки упали → 500.
 */
class SendRouteTest {

  private val sender = mockk<TgMessageSender>()

  /** Копия restModule с явной зависимостью (паттерн Application-extension). */
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
        send(sender)
      }
    }
  }

  private suspend fun io.ktor.server.testing.ApplicationTestBuilder.postApi(
    path: String,
    body: String,
    token: String? = "Bearer test-proxy-token",
  ): HttpResponse = client.post("/api/$path") {
    token?.let { header(HttpHeaders.Authorization, it) }
    contentType(ContentType.Application.Json)
    setBody(body)
  }

  @Test
  fun `no auth - 401`() = testApplication {
    application { testProxyApp() }
    val response = postApi("send", """{"chatId":1,"text":"hi"}""", token = null)
    assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
  }

  @Test
  fun `send text - messageId from bot`() = testApplication {
    application { testProxyApp() }
    coEvery { sender.sendText(11L, "анонс") } returns SentTgMessage(11L, 42)
    val response = postApi("send", """{"chatId":11,"text":"анонс"}""")
    assertThat(response.status).isEqualTo(HttpStatusCode.Accepted)
    assertThat(response.bodyAsText()).contains("\"messageId\":42")
    coVerify(exactly = 1) { sender.sendText(11L, "анонс") }
  }

  @Test
  fun `send with buttons - forwarded as SendButton`() = testApplication {
    application { testProxyApp() }
    coEvery { sender.sendButtons(any(), any(), any()) } returns SentTgMessage(11L, 43)
    val response = postApi(
      "send",
      """{"chatId":11,"text":"Вопрос?","buttons":[{"text":"да","callbackData":"hitl:x:0"}]}""",
    )
    assertThat(response.status).isEqualTo(HttpStatusCode.Accepted)
    coVerify(exactly = 1) {
      sender.sendButtons(11L, "Вопрос?", listOf(SendButton("да", "hitl:x:0")))
    }
  }

  @Test
  fun `send failed - 500`() = testApplication {
    application { testProxyApp() }
    coEvery { sender.sendText(any(), any()) } returns null
    val response = postApi("send", """{"chatId":11,"text":"x"}""")
    assertThat(response.status).isEqualTo(HttpStatusCode.InternalServerError)
  }

  @Test
  fun `callback answer and keyboard remove - 202 best-effort`() = testApplication {
    application { testProxyApp() }
    coEvery { sender.answerCallback(any(), any()) } returns Unit
    coEvery { sender.removeKeyboard(any(), any()) } returns Unit
    assertThat(postApi("callback-answer", """{"callbackQueryId":"cb1","text":"ок"}""").status)
      .isEqualTo(HttpStatusCode.Accepted)
    assertThat(postApi("message-keyboard-remove", """{"chatId":11,"messageId":42}""").status)
      .isEqualTo(HttpStatusCode.Accepted)
    coVerify { sender.answerCallback("cb1", "ок") }
    coVerify { sender.removeKeyboard(11L, 42L) }
  }
}
