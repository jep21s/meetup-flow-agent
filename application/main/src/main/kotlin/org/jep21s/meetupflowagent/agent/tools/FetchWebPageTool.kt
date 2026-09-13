package org.jep21s.meetupflowagent.agent.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readTo
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.io.readByteArray
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.koin.core.annotation.Singleton
import java.net.InetAddress
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Тул `fetch_web_page`: HTTP GET → текст страницы (Jsoup), обрезка до [MAX_TEXT_CHARS].
 *
 * Ограничения: только http/https; SSRF-гвард на каждом редиректе; редиректов ≤
 * [MAX_REDIRECTS] (вручную, относительные Location резолвятся от текущего URL);
 * connect 5s / total 15s; тело ≤ [MAX_BODY_BYTES]; Content-Type только text/html,
 * text/plain, application/xhtml+xml.
 *
 * Все ошибки возвращаются как [ToolResult.Error] с кодом — агент сам решает, что делать
 * (SOP: попробовать другую ссылку / продолжить без данных / спросить человека).
 *
 * Конструктор с HttpClient — для тестов.
 */
@Singleton
class FetchWebPageTool(
  private val ssrfGuard: SsrfGuard = SsrfGuard.DEFAULT,
  private val httpClient: HttpClient = defaultHttpClient(),
) : AgentTool {

  override val name = "fetch_web_page"

  override val description =
    "Загружает веб-страницу по URL и возвращает её текст (без HTML-разметки). " +
      "Используй, когда в сообщении есть ссылка на сайт мероприятия, а обязательных данных " +
      "(дата, место, программа, регистрация) не хватает. Один URL за вызов."

  override val parametersSchema: ObjectNode = JsonNodeFactory.instance.objectNode().apply {
    put("type", "object")
    putObject("properties").apply {
      putObject("url").apply {
        put("type", "string")
        put("format", "uri")
        put("description", "Абсолютный http(s)-URL страницы (например, страница регистрации митапа)")
      }
    }
    putArray("required").add("url")
  }

  override suspend fun execute(args: JsonNode): ToolResult {
    val urlNode = args.path("url")
    if (urlNode.isMissingNode || urlNode.asText().isBlank()) {
      return ToolResult.Error("Обязательный параметр 'url' отсутствует", "INVALID_ARGS")
    }

    var url = resolveUrl(urlNode.asText().trim())
      ?: return ToolResult.Error(
        "URL '${urlNode.asText()}' невалиден или схема не http(s)", "INVALID_URL",
      )
    var redirects = 0

    while (true) {
      checkHost(url.host)?.let { return it }

      val response = try {
        httpClient.get(url.toString()) {
          header(HttpHeaders.UserAgent, "meetup-flow-agent/0.1 (+web-tool)")
          header(HttpHeaders.Accept, "text/html, text/plain, application/xhtml+xml")
        }
      } catch (e: Exception) {
        return classifyNetworkError(e)
      }

      val status = response.status
      if (status.isRedirection()) {
        val location = response.headers[HttpHeaders.Location]
          ?: return ToolResult.Error("Редирект без заголовка Location", "INVALID_REDIRECT")
        redirects++
        if (redirects > MAX_REDIRECTS) {
          return ToolResult.Error("Превышен лимит редиректов ($MAX_REDIRECTS)", "REDIRECT_LIMIT")
        }
        url = resolveUrl(location, base = url)
          ?: return ToolResult.Error("Невалидный Location редиректа: '$location'", "INVALID_REDIRECT")
        continue
      }

      if (!status.isSuccess()) {
        return ToolResult.Error("Сайт ответил HTTP ${status.value}", httpCode(status.value))
      }

      val contentTypeHeader = response.headers[HttpHeaders.ContentType]
      val contentType = contentTypeHeader?.let { runCatching { ContentType.parse(it) }.getOrNull() }
      val allowed = contentType == null ||
        ALLOWED_CONTENT_TYPES.any { contentType.match(it) }
      if (!allowed) {
        return ToolResult.Error(
          "Неподдерживаемый Content-Type: ${contentTypeHeader ?: "отсутствует"} " +
            "(разрешены text/html, text/plain, application/xhtml+xml)",
          "UNSUPPORTED_CONTENT_TYPE",
        )
      }

      // Читаем поток с жёстким лимитом: readTo не прочитает больше limit байт.
      val channel = response.bodyAsChannel()
      val sink = kotlinx.io.Buffer()
      val total = try {
        channel.readTo(sink, MAX_BODY_BYTES + 1)
      } catch (e: Exception) {
        return classifyNetworkError(e)
      }
      if (total > MAX_BODY_BYTES) {
        return ToolResult.Error("Страница больше лимита ${MAX_BODY_BYTES / 1024} KB", "TOO_LARGE")
      }

      val charset = contentType?.parameter("charset")
        ?.let { runCatching { Charset.forName(it) }.getOrNull() }
        ?: StandardCharsets.UTF_8
      val body = String(sink.readByteArray(), charset)
      if (body.isBlank()) {
        return ToolResult.Error("Пустое тело ответа", "EMPTY_BODY")
      }

      val looksLikeHtml = contentType != null && !contentType.match(ContentType.Text.Plain) ||
        (contentType == null && body.trimStart().startsWith("<"))
      val text = if (looksLikeHtml) {
        runCatching { Jsoup.parse(body).text() }.getOrElse { body }
      } else {
        body
      }
      val normalized = text.replace(WHITESPACE_RE, " ").trim()
      if (normalized.isEmpty()) {
        return ToolResult.Error("Страница не содержит текста", "EMPTY_BODY")
      }
      return if (normalized.length <= MAX_TEXT_CHARS) {
        ToolResult.Success(normalized)
      } else {
        ToolResult.Success(normalized.take(MAX_TEXT_CHARS) + "\n…(текст обрезан)")
      }
    }
  }

  /** Парсит абсолютный URL; scheme обязана быть http/https. */
  private fun resolveUrl(raw: String, base: Url? = null): Url? = runCatching {
    if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
      Url(raw)
    } else if (base != null && raw.startsWith("/")) {
      val port = if (base.port == base.protocol.defaultPort) "" else ":${base.port}"
      Url("${base.protocol.name}://${base.host}$port${raw}")
    } else {
      null
    }
  }.getOrNull()

  /** Резолвит хост и проверяет все его A/AAAA-адреса через SSRF-гвард. */
  private suspend fun checkHost(host: String): ToolResult.Error? =
    withContext(Dispatchers.IO) {
      val addresses: Array<InetAddress> = try {
        InetAddress.getAllByName(host)
      } catch (e: Exception) {
        return@withContext ToolResult.Error("Хост '$host' не разрешается (DNS)", "DNS")
      }
      val blocked = addresses.firstOrNull { ssrfGuard.isBlocked(it) }
      if (blocked != null) {
        ToolResult.Error(
          "Хост '$host' указывает на приватный/запрещённый адрес (${blocked.hostAddress}) — " +
            "загрузка заблокирована политикой безопасности",
          "SSRF_BLOCKED",
        )
      } else {
        null
      }
    }

  private fun classifyNetworkError(e: Exception): ToolResult.Error = when (e) {
    is HttpRequestTimeoutException ->
      ToolResult.Error("Таймаут загрузки страницы (${TOTAL_TIMEOUT_MS / 1000}s)", "TIMEOUT")
    else ->
      ToolResult.Error(
        "Сетевая ошибка загрузки: ${e::class.simpleName}: ${e.message?.take(200)}",
        "NETWORK",
      )
  }

  private fun HttpStatusCode.isRedirection(): Boolean =
    this == HttpStatusCode.MovedPermanently ||
      this == HttpStatusCode.Found ||
      this == HttpStatusCode.SeeOther ||
      this == HttpStatusCode.TemporaryRedirect ||
      value == 308

  private fun httpCode(value: Int) = if (value in 400..499) "HTTP_4XX" else "HTTP_5XX"

  companion object {
    private const val MAX_REDIRECTS = 3
    private const val MAX_BODY_BYTES = 1L * 1024 * 1024
    private const val MAX_TEXT_CHARS = 4000
    private const val CONNECT_TIMEOUT_MS = 5_000L
    private const val TOTAL_TIMEOUT_MS = 15_000L
    private val XHTML_CONTENT_TYPE = ContentType.parse("application/xhtml+xml")
    private val ALLOWED_CONTENT_TYPES =
      listOf(ContentType.Text.Html, ContentType.Text.Plain, XHTML_CONTENT_TYPE)
    private val WHITESPACE_RE = Regex("\\s+")

    fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
      install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        requestTimeoutMillis = TOTAL_TIMEOUT_MS
      }
      followRedirects = false
      expectSuccess = false
    }
  }
}
