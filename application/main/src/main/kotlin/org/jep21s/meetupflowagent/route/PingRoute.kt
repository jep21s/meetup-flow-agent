package org.jep21s.meetupflowagent.route

import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.ping() {
    get("/ping") {
        call.respondText("pong")
    }
}
