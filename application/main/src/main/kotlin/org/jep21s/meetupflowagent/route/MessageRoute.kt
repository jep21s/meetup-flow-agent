package org.jep21s.meetupflowagent.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.jep21s.meetupflowagent.agent.AgentReply
import org.jep21s.meetupflowagent.agent.SyncAgentService
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.mp.KoinPlatform

data class MessageRequestDto(val text: String)

data class ToolCallDto(val name: String, val args: String, val ok: Boolean, val errorCode: String? = null)

data class AgentReplyDto(
  val reply: String,
  val toolCalls: List<ToolCallDto>,
  val iterations: Int,
  val limitReached: Boolean,
)

/**
 * POST /api/messages — синхронный запуск агента (этап 2): {text} → финальный ответ
 * агента + журнал вызовов тулов. Асинхронный inbox-паттерн (202 + flowId) — этап project.
 *
 * Сервис резолвится лениво из Koin внутри handler'а: restModule() используется
 * тестами без поднятого Koin (например, RootRouteTest).
 */
fun Route.messages() {
  post("/messages") {
    val raw = call.receiveText()
    val request = try {
      jacksonMapper.readValue(raw, MessageRequestDto::class.java)
    } catch (e: Exception) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON body: ${e.message}"))
      return@post
    }

    val text = request.text.trim()
    when {
      text.isEmpty() -> call.respond(
        HttpStatusCode.BadRequest,
        mapOf("error" to "Field 'text' must not be empty"),
      ).let { return@post }

      text.length > MAX_TEXT_LENGTH -> call.respond(
        HttpStatusCode.BadRequest,
        mapOf("error" to "Field 'text' must not exceed $MAX_TEXT_LENGTH characters"),
      ).let { return@post }
    }

    val agentService: SyncAgentService = KoinPlatform.getKoin().get(SyncAgentService::class)
    val reply: AgentReply = agentService.process(text)
    call.respond(reply.toDto())
  }
}

private fun AgentReply.toDto() = AgentReplyDto(
  reply = reply,
  toolCalls = toolCalls.map { ToolCallDto(it.name, it.args, it.ok, it.errorCode) },
  iterations = iterations,
  limitReached = limitReached,
)

private const val MAX_TEXT_LENGTH = 10_000
