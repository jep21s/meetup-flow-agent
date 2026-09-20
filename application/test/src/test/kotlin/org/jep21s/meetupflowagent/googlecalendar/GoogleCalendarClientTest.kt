package org.jep21s.meetupflowagent.googlecalendar

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * HTTP-клиент Google API на MockEngine: jwt-bearer-обмен токена (кэш, ошибки),
 * events.insert с Bearer-заголовком, 409 → AlreadyPresent, 401 → один refresh
 * и повтор, 5xx → GoogleApiException.
 */
class GoogleCalendarClientTest {

  private val okToken = """{"access_token":"tok-1","expires_in":3600,"token_type":"Bearer"}"""
  private val calendarId = "test-cal@group.calendar.google.com"

  @AfterEach
  fun cleanup() {
    System.clearProperty("google.calendar.credentials-json")
  }

  private fun client(responder: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): GoogleCalendarClient {
    System.setProperty("google.calendar.credentials-json", TestServiceAccounts.json())
    return GoogleCalendarClient(
      httpClient = HttpClient(MockEngine { responder(it) }),
      tokenUrl = "http://google.test/token",
      apiBase = "http://google.test/calendar/v3",
    )
  }

  private fun eventBody() = jacksonMapper.readTree("""{"id":"mfa-x1","summary":"Митап"}""")

  private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

  private fun body(request: HttpRequestData): String = (request.body as? TextContent)?.text.orEmpty()

  @Test
  fun `insert posts event with bearer token and parses google event id`() {
    var tokenForm: String? = null
    var insertAuth: String? = null
    var insertBody: String? = null
    var insertUrl: String? = null
    val c = client { request ->
      when {
        request.url.encodedPath == "/token" -> {
          tokenForm = body(request)
          respond(okToken, HttpStatusCode.OK, jsonHeaders())
        }
        else -> {
          insertAuth = request.headers["Authorization"]
          insertBody = body(request)
          insertUrl = request.url.toString()
          respond("""{"id":"gcal-evt-1","status":"confirmed"}""", HttpStatusCode.OK, jsonHeaders())
        }
      }
    }

    val outcome = runBlocking { c.insertEvent(calendarId, eventBody()) }

    assertThat(outcome).isEqualTo(InsertOutcome.Inserted("gcal-evt-1"))
    assertThat(insertUrl).isEqualTo("http://google.test/calendar/v3/calendars/$calendarId/events")
    assertThat(insertAuth).isEqualTo("Bearer tok-1")
    assertThat(insertBody).contains("\"summary\":\"Митап\"")
    // jwt-bearer: form-encoded grant с подписанным assertion
    assertThat(tokenForm).contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer")
    assertThat(tokenForm).contains("assertion=")
  }

  @Test
  fun `token is cached across inserts`() {
    var tokenCalls = 0
    var insertCalls = 0
    val c = client { request ->
      if (request.url.encodedPath == "/token") {
        tokenCalls++
        respond(okToken, HttpStatusCode.OK, jsonHeaders())
      } else {
        insertCalls++
        respond("""{"id":"gcal-evt-1"}""", HttpStatusCode.OK, jsonHeaders())
      }
    }

    runBlocking {
      c.insertEvent(calendarId, eventBody())
      c.insertEvent(calendarId, eventBody())
    }

    assertThat(tokenCalls).isEqualTo(1)
    assertThat(insertCalls).isEqualTo(2)
  }

  @Test
  fun `409 duplicate maps to AlreadyPresent`() {
    val c = client { request ->
      if (request.url.encodedPath == "/token") respond(okToken, HttpStatusCode.OK, jsonHeaders())
      else respond("""{"error":{"code":409,"message":"Duplicate detected"}}""", HttpStatusCode.Conflict, jsonHeaders())
    }

    val outcome = runBlocking { c.insertEvent(calendarId, eventBody()) }

    assertThat(outcome).isEqualTo(InsertOutcome.AlreadyPresent)
  }

  @Test
  fun `insert 5xx throws GoogleApiException with status and body`() {
    val c = client { request ->
      if (request.url.encodedPath == "/token") respond(okToken, HttpStatusCode.OK, jsonHeaders())
      else respond("""{"error":{"code":500}}""", HttpStatusCode.InternalServerError, jsonHeaders())
    }

    val ex = assertThrows<GoogleApiException> { runBlocking { c.insertEvent(calendarId, eventBody()) } }
    assertThat(ex.message).contains("HTTP 500").contains("calendar=")
  }

  @Test
  fun `token endpoint failure throws GoogleApiException`() {
    val c = client { request ->
      if (request.url.encodedPath == "/token") respond("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest, jsonHeaders())
      else error("insert must not be called when token failed")
    }

    val ex = assertThrows<GoogleApiException> { runBlocking { c.insertEvent(calendarId, eventBody()) } }
    assertThat(ex.message).contains("token endpoint").contains("HTTP 400")
  }

  @Test
  fun `401 refreshes token once and retries insert`() {
    val tokens = ArrayDeque(listOf("tok-1", "tok-2"))
    val tokenCalls = ArrayList<String>()
    val insertAuths = ArrayList<String?>()
    var insertCalls = 0
    val c = client { request ->
      when {
        request.url.encodedPath == "/token" -> {
          tokenCalls += "call"
          val json = """{"access_token":"${tokens.removeFirst()}","expires_in":3600,"token_type":"Bearer"}"""
          respond(json, HttpStatusCode.OK, jsonHeaders())
        }
        else -> {
          insertCalls++
          insertAuths += request.headers["Authorization"]
          if (insertCalls == 1) respond("""{"error":{"code":401}}""", HttpStatusCode.Unauthorized, jsonHeaders())
          else respond("""{"id":"gcal-evt-2"}""", HttpStatusCode.OK, jsonHeaders())
        }
      }
    }

    val outcome = runBlocking { c.insertEvent(calendarId, eventBody()) }

    assertThat(outcome).isEqualTo(InsertOutcome.Inserted("gcal-evt-2"))
    assertThat(tokenCalls).hasSize(2)
    assertThat(insertCalls).isEqualTo(2)
    assertThat(insertAuths).containsExactly("Bearer tok-1", "Bearer tok-2")
  }

  @Test
  fun `missing credentials throw with ENV hint`() {
    // свойство не выставлено → google.calendar.credentials-json пуст
    val c = GoogleCalendarClient(
      httpClient = HttpClient(MockEngine { error("no http calls expected") }),
      tokenUrl = "http://google.test/token",
      apiBase = "http://google.test/calendar/v3",
    )

    val ex = assertThrows<GoogleApiException> { runBlocking { c.insertEvent(calendarId, eventBody()) } }
    assertThat(ex.message).contains("GOOGLE_CALENDAR_CREDENTIALS_JSON")
  }

  @Test
  fun `calendarId with forbidden characters is rejected before any http call`() {
    val c = client { error("no http calls expected") }

    val ex = assertThrows<GoogleApiException> { runBlocking { c.insertEvent("bad id/with spaces", eventBody()) } }
    assertThat(ex.message).contains("invalid calendarId")
  }
}
