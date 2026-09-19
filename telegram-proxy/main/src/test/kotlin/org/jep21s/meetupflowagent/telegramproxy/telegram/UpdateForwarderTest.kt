package org.jep21s.meetupflowagent.telegramproxy.telegram

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.junit.jupiter.api.Test
import org.telegram.telegrambots.meta.api.objects.Chat
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.User
import java.util.concurrent.atomic.AtomicInteger

/**
 * Форвардер «тупой трубы»: каждый апдейт — ЦЕЛИКОМ в POST /api/telegram/updates
 * основного сервиса с RAW-заголовком; 5xx/сеть — ретраи; 4xx — терминально.
 */
class UpdateForwarderTest {

  init {
    // ConfigLoader проверяет System.getProperty первым — override для теста
    System.setProperty("meetup-flow.url", "http://main.test")
    System.setProperty("meetup-flow.token", "test-main-token")
  }

  private fun update(updateId: Int, text: String = "митап"): Update = Update().apply {
    this.updateId = updateId
    message = Message().apply {
      chat = Chat().apply { id = 100L; type = "private" }
      from = User().apply { id = 7L; userName = "jep" }
      this.text = text
      date = 1_700_000_000
    }
  }

  private fun forwarder(engine: MockEngine) =
    UpdateForwarder(HttpClient(engine), CoroutineScope(Dispatchers.Unconfined))

  @Test
  fun `forwards whole update dto with raw auth header`() = runTest {
    val requests = mutableListOf<HttpRequestData>()
    val engine = MockEngine { request ->
      requests += request
      respond("""{"accepted":true}""", HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    forwarder(engine).forward(update(updateId = 42))

    assertThat(requests).hasSize(1)
    val url = requests[0].url.toString()
    assertThat(url).endsWith("/api/telegram/updates")
    assertThat(requests[0].headers[HttpHeaders.Authorization]).isEqualTo("test-main-token")
    val body = requests[0].body.toString()
    assertThat(body).contains("\"update_id\":42")
  }

  @Test
  fun `5xx retried then accepted`() = runTest {
    val calls = AtomicInteger()
    val engine = MockEngine {
      if (calls.incrementAndGet() <= 2) {
        respond("boom", HttpStatusCode.InternalServerError)
      } else {
        respond("""{"accepted":true}""", HttpStatusCode.Accepted)
      }
    }

    forwarder(engine).forward(update(updateId = 1))

    assertThat(calls.get()).isEqualTo(3)
  }

  @Test
  fun `4xx is terminal - no retry`() = runTest {
    val calls = AtomicInteger()
    val engine = MockEngine {
      calls.incrementAndGet()
      respond("bad", HttpStatusCode.BadRequest)
    }

    forwarder(engine).forward(update(updateId = 2))

    assertThat(calls.get()).isEqualTo(1)
  }
}
