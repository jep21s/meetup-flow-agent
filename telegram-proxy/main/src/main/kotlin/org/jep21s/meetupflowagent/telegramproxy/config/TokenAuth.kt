package org.jep21s.meetupflowagent.telegramproxy.config

import io.ktor.http.HttpHeaders
import io.ktor.server.application.createRouteScopedPlugin

class UnauthorizedException : RuntimeException()

/**
 * Route-scoped token auth: сравнивает заголовок Authorization с ожидаемым
 * значением целиком. Для /api/notify ожидание — `"Bearer ${proxy.token}"`
 * (так шлёт HttpProxyNotifier в meetup-flow-agent).
 */
fun io.ktor.server.routing.Route.requireTokenAuth(expectedHeaderValue: String) {
  install(createRouteScopedPlugin(name = "TokenAuth") {
    onCall { call ->
      val authHeader = call.request.headers[HttpHeaders.Authorization]
      if (authHeader != expectedHeaderValue) {
        throw UnauthorizedException()
      }
    }
  })
}
