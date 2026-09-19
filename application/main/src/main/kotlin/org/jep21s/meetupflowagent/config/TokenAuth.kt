package org.jep21s.meetupflowagent.config

import io.ktor.http.HttpHeaders
import io.ktor.server.application.createRouteScopedPlugin

class UnauthorizedException : RuntimeException()

/**
 * Route-scoped token auth: сравнивает заголовок Authorization с ожидаемым токеном.
 * Кидает UnauthorizedException — его ловит StatusPages в RestModule и отвечает 401.
 */
fun io.ktor.server.routing.Route.requireTokenAuth(expectedToken: String) {
    install(createRouteScopedPlugin(name = "TokenAuth") {
        onCall { call ->
            val authHeader = call.request.headers[HttpHeaders.Authorization]
            if (authHeader != expectedToken) {
                throw UnauthorizedException()
            }
        }
    })
}
