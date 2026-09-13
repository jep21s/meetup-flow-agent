package org.jep21s.meetupflowagent.config

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.jep21s.meetupflowagent.route.flows
import org.jep21s.meetupflowagent.route.messages
import org.jep21s.meetupflowagent.route.ping
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.jep21s.meetupflowagent.starter.jackson.JacksonConfig

private val logger = KotlinLogging.logger { }

fun Application.restModule() {
  configureCors()
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
  install(DoubleReceive)

  val appToken = ConfigLoader.getRequiredProperty(
    "app.token",
    "App token is not configured. Please set app.token in config.properties or APP_TOKEN environment variable"
  )

  routing {
    get("/") {
      call.respondText("Hello World!")
    }
    route("/api") {
      requireTokenAuth(appToken)
      ping()
      messages()
      flows()
    }
  }
}
