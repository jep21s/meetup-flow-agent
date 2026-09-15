package org.jep21s.meetupflowagent.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.jep21s.meetupflowagent.telegram.TelegramUpdateService
import org.koin.mp.KoinPlatform

/**
 * POST /api/telegram/updates — вход сырых апдейтов Telegram от прокси
 * (Authorization: <app.token> RAW — тот же контракт, что у /api/messages).
 * Тело — JSON всей DTO Update; все решения (HITL/команда/источник/флоу)
 * принимает TelegramUpdateService модуля application/telegram.
 */
fun Route.telegramUpdates() {
  post("/telegram/updates") {
    val raw = call.receiveText()
    if (raw.isBlank()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Empty body"))
      return@post
    }
    val updateService: TelegramUpdateService = KoinPlatform.getKoin().get(TelegramUpdateService::class)
    updateService.handle(raw)
    call.respond(HttpStatusCode.Accepted, mapOf("accepted" to true))
  }
}
