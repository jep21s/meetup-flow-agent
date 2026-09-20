package org.jep21s.meetupflowagent.googlecalendar

import com.fasterxml.jackson.databind.JsonNode
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.delete
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.core.annotation.Singleton
import java.net.URLEncoder
import java.time.Instant
import java.util.Base64

private val logger = KotlinLogging.logger { }

/** Исход вставки события: создано либо уже существовало (идемпотентный повтор). */
sealed interface InsertOutcome {
  data class Inserted(val googleEventId: String) : InsertOutcome
  data object AlreadyPresent : InsertOutcome
}

/** Сбой вызова Google API: неуспешный HTTP-ответ или незаполненная конфигурация. */
class GoogleApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * HTTP-клиент Google Calendar API v3 + OAuth2 сервисного аккаунта
 * (grant_type=jwt-bearer, JWT подписывается RS256 штатным java.security — без
 * внешних зависимостей). Access-токен кэшируется до exp-60s; при 401 — ровно
 * один refresh и повтор вставки. Ретраи НЕ здесь: одна попытка за вызов,
 * лестница ретраев — задача OutboxDeliveryPoller'а (SPI-контракт транспорта).
 * tokenUrl/api-base берутся из конфига (переопределяются -D — так тесты
 * подменяют Google на WireMock).
 */
@Singleton
class GoogleCalendarClient(
  private val httpClient: HttpClient = defaultHttpClient(),
  private val tokenUrl: String = ConfigLoader.getProperty("google.oauth.token-url", DEFAULT_TOKEN_URL).trim(),
  private val apiBase: String = ConfigLoader.getProperty("google.calendar.api-base", DEFAULT_API_BASE).trim().trimEnd('/'),
) {

  @Volatile
  private var cachedCredentials: ServiceAccountCredentials? = null

  private var cachedToken: String? = null
  private var tokenValidUntil: Instant = Instant.EPOCH
  private val tokenMutex = Mutex()

  /**
   * Вставка события в календарь [calendarId]. Идемпотентность: транспорт
   * задаёт собственный id события, повторная вставка получает 409 →
   * [InsertOutcome.AlreadyPresent] (успех, событие уже в календаре).
   */
  suspend fun insertEvent(calendarId: String, eventBody: JsonNode): InsertOutcome {
    val id = requireCalendarId(calendarId)
    val creds = credentials()
    val response = withTokenRetry(creds) { token -> postEvent(id, eventBody, token) }
    val body = response.bodyAsText()
    return when {
      response.status.isSuccess() ->
        InsertOutcome.Inserted(jacksonMapper.readTree(body).path("id").asText())
      response.status.value == 409 -> InsertOutcome.AlreadyPresent
      else -> throw GoogleApiException(
        "google calendar events.insert HTTP ${response.status.value} calendar=$id: ${body.take(200)}",
      )
    }
  }

  /** Событие по клиентскому id (проверка доставки/чистки); null — нет или удалено. */
  suspend fun findEvent(calendarId: String, googleEventId: String): JsonNode? {
    val id = requireCalendarId(calendarId)
    requireEventId(googleEventId)
    val creds = credentials()
    val response = withTokenRetry(creds) { token ->
      httpClient.get("$apiBase/calendars/$id/events/$googleEventId") {
        header(HttpHeaders.Authorization, "Bearer $token")
      }
    }
    val body = response.bodyAsText()
    return when {
      response.status.isSuccess() -> jacksonMapper.readTree(body)
      response.status.value == 404 || response.status.value == 410 -> null
      else -> throw GoogleApiException(
        "google calendar events.get HTTP ${response.status.value} calendar=$id event=$googleEventId: ${body.take(200)}",
      )
    }
  }

  /** Удаление события по клиентскому id; отсутствующее (404/410) — не ошибка. */
  suspend fun deleteEvent(calendarId: String, googleEventId: String) {
    val id = requireCalendarId(calendarId)
    requireEventId(googleEventId)
    val creds = credentials()
    val response = withTokenRetry(creds) { token ->
      httpClient.delete("$apiBase/calendars/$id/events/$googleEventId") {
        header(HttpHeaders.Authorization, "Bearer $token")
      }
    }
    if (!response.status.isSuccess() && response.status.value != 404 && response.status.value != 410) {
      val body = response.bodyAsText()
      throw GoogleApiException(
        "google calendar events.delete HTTP ${response.status.value} calendar=$id event=$googleEventId: ${body.take(200)}",
      )
    }
  }

  /** Один повтор с refresh токена при 401 (не ретрай доставки — его держит поллер). */
  private suspend fun withTokenRetry(
    creds: ServiceAccountCredentials,
    call: suspend (String) -> HttpResponse,
  ): HttpResponse {
    var response = call(accessToken(creds))
    if (response.status.value == 401) {
      logger.info { "google calendar 401 — refreshing access token, single retry" }
      tokenMutex.withLock { cachedToken = null }
      response = call(accessToken(creds))
    }
    return response
  }

  private fun requireCalendarId(calendarId: String): String {
    val id = calendarId.trim()
    if (!CALENDAR_ID.matches(id)) {
      throw GoogleApiException("invalid calendarId '$id' — expected like 'xxx@group.calendar.google.com' or 'primary'")
    }
    return id
  }

  private fun requireEventId(googleEventId: String) {
    if (!EVENT_ID.matches(googleEventId)) {
      throw GoogleApiException("invalid googleEventId '$googleEventId'")
    }
  }

  private fun credentials(): ServiceAccountCredentials =
    cachedCredentials ?: ServiceAccountCredentials
      .parse(ConfigLoader.getProperty("google.calendar.credentials-json"))
      ?.also { cachedCredentials = it }
      ?: throw GoogleApiException(
        "google.calendar.credentials-json is empty — set ENV GOOGLE_CALENDAR_CREDENTIALS_JSON " +
          "(service account key json) to deliver into Google Calendar",
      )

  private suspend fun accessToken(creds: ServiceAccountCredentials): String = tokenMutex.withLock {
    cachedToken?.takeIf { Instant.now().isBefore(tokenValidUntil) }?.let { return it }
    val form = "grant_type=" + URLEncoder.encode(JWT_BEARER_GRANT, Charsets.UTF_8) +
      "&assertion=" + URLEncoder.encode(creds.rs256Assertion(SCOPE, tokenUrl), Charsets.UTF_8)
    val response = httpClient.post(tokenUrl) {
      contentType(ContentType.Application.FormUrlEncoded)
      setBody(form)
    }
    val body = response.bodyAsText()
    if (!response.status.isSuccess()) {
      throw GoogleApiException("google token endpoint HTTP ${response.status.value}: ${body.take(200)}")
    }
    val json = jacksonMapper.readTree(body)
    val token = json.path("access_token").asText()
    if (token.isEmpty()) throw GoogleApiException("google token response has no access_token: ${body.take(200)}")
    cachedToken = token
    tokenValidUntil = Instant.now()
      .plusSeconds(json.path("expires_in").asLong(3600).coerceAtLeast(60) - TOKEN_EXPIRY_MARGIN_SECONDS)
    token
  }

  private suspend fun postEvent(calendarId: String, eventBody: JsonNode, token: String): HttpResponse =
    httpClient.post("$apiBase/calendars/$calendarId/events") {
      header(HttpHeaders.Authorization, "Bearer $token")
      contentType(ContentType.Application.Json)
      setBody(jacksonMapper.writeValueAsString(eventBody))
    }

  companion object {
    const val DEFAULT_TOKEN_URL = "https://oauth2.googleapis.com/token"
    const val DEFAULT_API_BASE = "https://www.googleapis.com/calendar/v3"
    private const val SCOPE = "https://www.googleapis.com/auth/calendar.events"
    private const val JWT_BEARER_GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer"
    private const val TOKEN_EXPIRY_MARGIN_SECONDS = 60L
    private val CALENDAR_ID = Regex("[A-Za-z0-9@._%+-]{3,256}")
    private val EVENT_ID = Regex("[A-Za-z0-9_-]{3,256}")

    fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
      install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 15_000
      }
      expectSuccess = false
    }
  }
}
