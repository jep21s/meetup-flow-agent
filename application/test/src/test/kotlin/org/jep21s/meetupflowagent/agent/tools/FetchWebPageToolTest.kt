package org.jep21s.meetupflowagent.agent.tools

import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FetchWebPageToolTest {

  companion object {
    private var port: Int = 0
    private val server = embeddedServer(ServerCIO, port = 0) {
      routing {
        get("/html") {
          call.respondText(
            "<html><head><title>T</title></head><body><p>Митап про Kotlin, бесплатно</p></body></html>",
            ContentType.Text.Html,
          )
        }
        get("/plain") { call.respondText("просто   текст страницы", ContentType.Text.Plain) }
        get("/json") { call.respondText("{}", ContentType.Application.Json) }
        get("/notfound") { call.respond(HttpStatusCode.NotFound) }
        get("/boom") { call.respond(HttpStatusCode.InternalServerError) }
        get("/slow") {
          delay(3_000)
          call.respondText("slow", ContentType.Text.Plain)
        }
        get("/redirect") { call.respondRedirect("/html") }
        get("/self") { call.respondRedirect("/self") }
        get("/big") { call.respondText("x".repeat(2 * 1024 * 1024), ContentType.Text.Plain) }
      }
    }

    @BeforeAll
    @JvmStatic
    fun start() {
      server.start(wait = false)
      port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @AfterAll
    @JvmStatic
    fun stop() {
      server.stop(500, 1000)
    }
  }

  /** Гвард, разрешающий localhost: embedded-сервер слушает 127.0.0.1. */
  private val permissive = SsrfGuard { false }

  private fun tool(client: HttpClient = HttpClient(CIO) { followRedirects = false; expectSuccess = false }) =
    FetchWebPageTool(ssrfGuard = permissive, httpClient = client)

  private fun shortTimeoutClient(): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) { requestTimeoutMillis = 700 }
    followRedirects = false
    expectSuccess = false
  }

  private fun url(path: String) = "http://127.0.0.1:$port$path"

  @Test
  fun `html page extracts text`() = runBlocking {
    val result = tool().execute(jacksonMapper.readTree("""{"url":"${url("/html")}"}"""))
    assertThat(result).isInstanceOf(ToolResult.Success::class.java)
    assertThat((result as ToolResult.Success).text).contains("Митап про Kotlin")
    assertThat(result.text).doesNotContain("<")
  }

  @Test
  fun `plain text normalized`() = runBlocking {
    val result = tool().execute(jacksonMapper.readTree("""{"url":"${url("/plain")}"}"""))
    assertThat(result).isInstanceOf(ToolResult.Success::class.java)
    assertThat((result as ToolResult.Success).text).isEqualTo("просто текст страницы")
  }

  @Test
  fun `unsupported content type rejected`() = runBlocking {
    val result = tool().execute(jacksonMapper.readTree("""{"url":"${url("/json")}"}"""))
    assertThat(result).isInstanceOf(ToolResult.Error::class.java)
    assertThat((result as ToolResult.Error).code).isEqualTo("UNSUPPORTED_CONTENT_TYPE")
  }

  @Test
  fun `http 404 mapped to HTTP_4XX`() = runBlocking {
    val result = tool().execute(jacksonMapper.readTree("""{"url":"${url("/notfound")}"}"""))
    assertThat((result as ToolResult.Error).code).isEqualTo("HTTP_4XX")
  }

  @Test
  fun `http 500 mapped to HTTP_5XX`() = runBlocking {
    val result = tool().execute(jacksonMapper.readTree("""{"url":"${url("/boom")}"}"""))
    assertThat((result as ToolResult.Error).code).isEqualTo("HTTP_5XX")
  }

  @Test
  fun `timeout mapped to TIMEOUT`() = runBlocking {
    val result = tool(shortTimeoutClient())
      .execute(jacksonMapper.readTree("""{"url":"${url("/slow")}"}"""))
    assertThat((result as ToolResult.Error).code).isEqualTo("TIMEOUT")
  }

  @Test
  fun `redirect followed once`() = runBlocking {
    val result = tool().execute(jacksonMapper.readTree("""{"url":"${url("/redirect")}"}"""))
    assertThat(result).isInstanceOf(ToolResult.Success::class.java)
  }

  @Test
  fun `redirect loop hits REDIRECT_LIMIT`() = runBlocking {
    val result = tool().execute(jacksonMapper.readTree("""{"url":"${url("/self")}"}"""))
    assertThat((result as ToolResult.Error).code).isEqualTo("REDIRECT_LIMIT")
  }

  @Test
  fun `oversized body mapped to TOO_LARGE`() = runBlocking {
    val result = tool().execute(jacksonMapper.readTree("""{"url":"${url("/big")}"}"""))
    assertThat((result as ToolResult.Error).code).isEqualTo("TOO_LARGE")
  }

  @Test
  fun `localhost blocked by default ssrf guard`() = runBlocking {
    val strictTool = FetchWebPageTool() // DEFAULT guard + defaultHttpClient
    val result = strictTool.execute(jacksonMapper.readTree("""{"url":"${url("/html")}"}"""))
    assertThat((result as ToolResult.Error).code).isEqualTo("SSRF_BLOCKED")
  }

  @Test
  fun `missing url argument rejected`() = runBlocking {
    val result = tool().execute(jacksonMapper.readTree("{}"))
    assertThat((result as ToolResult.Error).code).isEqualTo("INVALID_ARGS")
  }

  @Test
  fun `non-http scheme rejected`() = runBlocking {
    val result = tool().execute(jacksonMapper.readTree("""{"url":"file:///etc/passwd"}"""))
    assertThat((result as ToolResult.Error).code).isEqualTo("INVALID_URL")
  }

  @Test
  fun `parameters schema is a valid draft-07 json schema`() {
    val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
    val schema = factory.getSchema(tool().parametersSchema.toString())

    val valid = schema.validate(jacksonMapper.readTree("""{"url":"https://example.com/page"}"""))
    assertThat(valid).isEmpty()

    val missingUrl = schema.validate(jacksonMapper.readTree("{}"))
    assertThat(missingUrl).isNotEmpty

    val wrongType = schema.validate(jacksonMapper.readTree("""{"url":42}"""))
    assertThat(wrongType).isNotEmpty
  }
}
