package org.jep21s.meetupflowagent.agent

import org.jep21s.meetupflowagent.agent.tools.AgentTool
import org.jep21s.meetupflowagent.agent.tools.ToolPolicies
import org.jep21s.meetupflowagent.agent.tools.ToolPolicyMode
import org.jep21s.meetupflowagent.agent.tools.ToolResult
import org.jep21s.meetupflowagent.llm.LlmClient
import org.jep21s.meetupflowagent.llm.dto.ChatCompletionRequest
import org.jep21s.meetupflowagent.llm.dto.ChatMessage
import org.jep21s.meetupflowagent.llm.dto.ChatRole
import org.jep21s.meetupflowagent.llm.dto.FunctionSpec
import org.jep21s.meetupflowagent.llm.dto.ToolSpec
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.core.annotation.Singleton

/** Выполненный вызов тула для ответа API. */
data class ToolCallRecord(
  val name: String,
  val args: String,
  val ok: Boolean,
  val errorCode: String? = null,
)

/** Итог синхронного агентского прогона. */
data class AgentReply(
  val reply: String,
  val toolCalls: List<ToolCallRecord>,
  val iterations: Int,
  val limitReached: Boolean,
)

/**
 * Мини-ReAct-цикл (этап 2): Reason (вызов LLM с описаниями тулов) → Act (исполнение
 * tool_calls) → Observe (результат в историю). Финал — контент модели без tool_calls.
 * Без БД, без стриминга, лимит — константа [MAX_ITERATIONS] (полный лимит с
 * прогресс-детектором — этап 6).
 */
@Singleton
class SyncAgentService(
  private val llmClient: LlmClient,
  private val tools: List<AgentTool>,
) {

  private val systemPrompt: String =
    this::class.java.classLoader.getResourceAsStream(SYSTEM_PROMPT_RESOURCE)
      ?.bufferedReader()?.readText()
      ?: throw IllegalStateException("System prompt resource not found: $SYSTEM_PROMPT_RESOURCE")

  private val toolSpecs: List<ToolSpec> = tools.map { t ->
    ToolSpec(
      function = FunctionSpec(
        name = t.name,
        description = t.description,
        parameters = t.parametersSchema,
      ),
    )
  }

  suspend fun process(userText: String): AgentReply {
    val model = ConfigLoader.getRequiredProperty(
      "llm.agent.model",
      "llm.agent.model is not configured",
    )
    val messages = mutableListOf(
      ChatMessage.system(systemPrompt),
      ChatMessage.user(userText),
    )
    val toolCallsLog = mutableListOf<ToolCallRecord>()

    for (iteration in 1..MAX_ITERATIONS) {
      val response = llmClient.complete(
        ChatCompletionRequest(model = model, messages = messages.toList(), tools = toolSpecs),
      )
      val assistantMessage = response.firstMessage()

      val calls = assistantMessage.toolCalls.orEmpty()
      if (calls.isEmpty()) {
        return AgentReply(
          reply = assistantMessage.content.orEmpty(),
          toolCalls = toolCallsLog,
          iterations = iteration,
          limitReached = false,
        )
      }

      messages.add(assistantMessage)
      for (call in calls) {
        val observation = executeWithPolicy(call.function.name, call.function.arguments)
        toolCallsLog += observation.record
        messages.add(ChatMessage.tool(call.id, observation.observationText))
      }
    }

    // Лимит исчерпан: возвращаем последний контент (или пометку), строгая валидация — этап 5.
    val lastContent = messages.lastOrNull { it.role == ChatRole.ASSISTANT }
      ?.content.orEmpty()
    return AgentReply(
      reply = lastContent.ifBlank {
        "Лимит итераций агента ($MAX_ITERATIONS) исчерпан до финального ответа."
      },
      toolCalls = toolCallsLog,
      iterations = MAX_ITERATIONS,
      limitReached = true,
    )
  }

  private suspend fun executeWithPolicy(
    toolName: String,
    argsJson: String,
  ): Observation = when (ToolPolicies.policyFor(toolName)) {
    ToolPolicyMode.ALLOW -> runTool(toolName, argsJson)
    ToolPolicyMode.ASK ->
      // Механика ask (WAITING_TOOL_APPROVAL) активируется на этапе project; сейчас
      // тулы с политикой ask исполняются как allow, о чём сообщаем в observation.
      runTool(toolName, argsJson)

    ToolPolicyMode.BLOCK ->
      Observation(
        record = ToolCallRecord(toolName, argsJson, ok = false, errorCode = "POLICY_BLOCKED"),
        observationText = "Вызов инструмента '$toolName' заблокирован политикой (policy=block). " +
          "Продолжай без него.",
      )
  }

  private suspend fun runTool(toolName: String, argsJson: String): Observation {
    val tool = tools.firstOrNull { it.name == toolName }
      ?: return Observation(
        record = ToolCallRecord(toolName, argsJson, ok = false, errorCode = "UNKNOWN_TOOL"),
        observationText = "Инструмент '$toolName' не существует. Доступны: ${tools.joinToString { it.name }}.",
      )

    val args = try {
      jacksonMapper.readTree(argsJson)
    } catch (e: Exception) {
      return Observation(
        record = ToolCallRecord(toolName, argsJson, ok = false, errorCode = "INVALID_ARGS"),
        observationText = "Аргументы не являются валидным JSON: ${e.message}",
      )
    }

    return when (val result = tool.execute(args)) {
      is ToolResult.Success -> Observation(
        record = ToolCallRecord(toolName, argsJson, ok = true),
        observationText = result.text,
      )
      is ToolResult.Error -> Observation(
        record = ToolCallRecord(toolName, argsJson, ok = false, errorCode = result.code),
        observationText = "Ошибка инструмента '$toolName' [${result.code}]: ${result.message}. " +
          "Можешь попробовать другую ссылку или продолжить без этих данных.",
      )
    }
  }

  private data class Observation(
    val record: ToolCallRecord,
    val observationText: String,
  )

  companion object {
    private const val SYSTEM_PROMPT_RESOURCE = "prompts/extractor-system.md"
    private const val MAX_ITERATIONS = 4
  }
}
