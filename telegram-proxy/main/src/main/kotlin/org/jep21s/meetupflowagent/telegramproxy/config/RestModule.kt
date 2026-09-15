package org.jep21s.meetupflowagent.telegramproxy.config

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.jep21s.meetupflowagent.starter.jackson.JacksonConfig
import org.jep21s.meetupflowagent.telegramproxy.telegram.HitlPendingStore
import org.jep21s.meetupflowagent.telegramproxy.telegram.TgMessageSender
import org.jep21s.meetupflowagent.telegramproxy.route.notify
import org.koin.mp.KoinPlatform

private val logger = KotlinLogging.logger { }

fun Application.restModule() {
  install(ContentNegotiation) {
    jackson {
      JacksonConfig.customizer(this)
    }
  }
  install(StatusPages) {
    exception<UnauthorizedException> { call: ApplicationCall, _: UnauthorizedException ->
      call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
    }
    exception<Throwable> { call: ApplicationCall, ex: Throwable ->
      logger.error(ex) { ex.message }
      call.respond(
        HttpStatusCode.InternalServerError,
        mapOf(
          "status" to HttpStatusCode.InternalServerError.value
        )
      )
    }
  }
  install(DefaultHeaders)

  // вызывают HttpProxyNotifier/TelegramProxyTransport из meetup-flow-agent:
  // Authorization: Bearer {proxy.token}
  val proxyToken = ConfigLoader.getRequiredProperty(
    "proxy.token",
    "Proxy token is not configured. Please set proxy.token in config.properties or PROXY_TOKEN environment variable"
  )

  // общий канал анонсов — fallback, когда userIds пуст (0/null = не задан)
  val mainChatId = ConfigLoader.getProperty("telegram.main.chat-id").trim().toLongOrNull()

  val sender = KoinPlatform.getKoin().get<TgMessageSender>()
  val pendingStore = KoinPlatform.getKoin().get<HitlPendingStore>()

  routing {
    get("/") {
      call.respondText("Hello World!")
    }
    // публичный healthcheck (Railway): без токена
    get("/ping") {
      call.respondText("pong")
    }
    route("/api") {
      requireTokenAuth("Bearer $proxyToken")
      notify(sender, pendingStore, mainChatId)
    }
  }
}
