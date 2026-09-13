package org.jep21s.meetupflowagent.flow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.jep21s.meetupflowagent.agent.AgentStreamEvent
import org.jep21s.meetupflowagent.agent.ConversationState
import org.jep21s.meetupflowagent.agent.ToolCallRecord
import org.jep21s.meetupflowagent.agent.context.SystemPromptBuilder
import org.jep21s.meetupflowagent.agent.tools.AgentTool
import org.jep21s.meetupflowagent.agent.tools.ToolPolicies
import org.jep21s.meetupflowagent.agent.tools.ToolPolicyMode
import org.jep21s.meetupflowagent.agent.tools.ToolResult
import org.jep21s.meetupflowagent.db.DuplicateCandidate
import org.jep21s.meetupflowagent.db.EventRepository
import org.jep21s.meetupflowagent.db.EventRow
import org.jep21s.meetupflowagent.db.FlowRepository
import org.jep21s.meetupflowagent.db.FlowStepRepository
import org.jep21s.meetupflowagent.db.FlowStepType
import org.jep21s.meetupflowagent.domain.ContractParseException
import org.jep21s.meetupflowagent.domain.ContractParser
import org.jep21s.meetupflowagent.domain.ContractValidator
import org.jep21s.meetupflowagent.domain.EventContractDto
import org.jep21s.meetupflowagent.domain.ValidatedContract
import org.jep21s.meetupflowagent.domain.Verdict
import org.jep21s.meetupflowagent.domain.VerdictStatus
import org.jep21s.meetupflowagent.llm.EmbeddingClient
import org.jep21s.meetupflowagent.llm.EmbeddingException
import org.jep21s.meetupflowagent.llm.LlmClient
import org.jep21s.meetupflowagent.llm.StreamDelta
import org.jep21s.meetupflowagent.llm.dto.ChatCompletionRequest
import org.jep21s.meetupflowagent.llm.dto.ChatMessage
import org.jep21s.meetupflowagent.llm.dto.FunctionSpec
import org.jep21s.meetupflowagent.llm.dto.ToolSpec
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.core.annotation.Singleton
import org.slf4j.MDC
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

private val logger = KotlinLogging.logger { }


/**
 * FlowEngine этапа 5 — управляемый сценарий (стейт-машина, PLAN_5 §1.2):
 *
 * 1. извлечение — цикл Reason→Act→Observe (fetch_web_page / search_duplicate);
 * 2. fetch_web_page при нехватке данных и наличии ссылки (решение модели);
 * 3. search_duplicate при достаточных фактах (решение модели, SOP промпта);
 * 4. финальный JSON модели;
 * 5. пост-валидация [ContractValidator] +
 *    финальный дубль-чек (≥0.92 → DUPLICATE; 0.85–0.92 → NEEDS_REVIEW) +
 *    вердикт → статус.
 *
 * Каждый шаг пишется в flow_steps (включая CoT в REASON), снапшот состояния — в
 * flows.state_snapshot. Временный хард-кап [MAX_ITERATIONS] (полный лимит с
 * прогресс-детектором — этап 6). HITL/retry/резюм — этап project.
 */
@Singleton
class AgentFlowService(
  private val llmClient: LlmClient,
  private val tools: List<AgentTool>,
  private val systemPromptBuilder: SystemPromptBuilder,
  private val flowRepository: FlowRepository,
  private val flowStepRepository: FlowStepRepository,
  private val eventRepository: EventRepository,
  private val embeddingClient: EmbeddingClient,
) {

  private val systemPrompt: String by lazy { systemPromptBuilder.build() }

  private val toolSpecs: List<ToolSpec> = tools.map { t ->
    ToolSpec(
      function = FunctionSpec(
        name = t.name,
        description = t.description,
        parameters = t.parametersSchema,
      ),
    )
  }

  suspend fun run(
    message: String,
    emit: (suspend (AgentStreamEvent) -> Unit)? = null,
  ): FlowResult {
    val model = ConfigLoader.getRequiredProperty(
      "llm.agent.model",
      "llm.agent.model is not configured",
    )
    val flowId = flowRepository.create(FlowStatus.PROCESSING.name)
    // flowId в MDC распространяется на все корутины флоу (MDCContext)
    return MDC.putCloseable("flowId", flowId.toString()).use {
      withContext(MDCContext()) {
        runLoop(flowId, message, model, emit)
      }
    }
  }

  private suspend fun runLoop(
    flowId: UUID,
    message: String,
    model: String,
    emit: (suspend (AgentStreamEvent) -> Unit)?,
  ): FlowResult {
    val state = ConversationState(
      listOf(
        ChatMessage.system(systemPrompt),
        ChatMessage.user(message),
      ),
    )
    val toolCallsLog = mutableListOf<ToolCallRecord>()

    for (iteration in 1..MAX_ITERATIONS) {
      val request = ChatCompletionRequest(model = model, messages = state.snapshot(), tools = toolSpecs)
      val startedAt = System.nanoTime()
      val response = if (emit == null) {
        llmClient.complete(request)
      } else {
        llmClient.streamChat(request) { delta ->
          when (delta) {
            is StreamDelta.ReasoningDelta -> emit(AgentStreamEvent.ReasoningDelta(delta.text))
            is StreamDelta.ContentDelta -> emit(AgentStreamEvent.ContentDelta(delta.text))
            is StreamDelta.ToolCallDelta, is StreamDelta.Finish -> Unit
          }
        }
      }
      val latencyMs = (System.nanoTime() - startedAt) / 1_000_000
      val assistantMessage = response.firstMessage()

      flowStepRepository.appendStep(
        flowId = flowId,
        type = FlowStepType.REASON,
        content = reasonStep(assistantMessage, response.usage?.totalTokens),
        tokens = response.usage?.totalTokens,
        latencyMs = latencyMs,
        snapshot = snapshotJson(state, iteration, toolCallsLog.size),
      )

      val calls = assistantMessage.toolCalls.orEmpty()
      if (calls.isEmpty()) {
        val finalText = assistantMessage.content.orEmpty()
        flowStepRepository.appendStep(
          flowId = flowId,
          type = FlowStepType.FINAL,
          content = simpleObject("reply" to finalText.take(4000)),
          tokens = response.usage?.completionTokens,
          snapshot = snapshotJson(state, iteration, toolCallsLog.size),
        )
        val result = finalize(flowId, finalText, toolCallsLog, iteration)
        emit?.invoke(AgentStreamEvent.Final(result))
        return result
      }

      state.add(assistantMessage)
      for (call in calls) {
        emit?.invoke(AgentStreamEvent.ToolCall(call.function.name, call.function.arguments))
        flowStepRepository.appendStep(
          flowId = flowId,
          type = FlowStepType.ACTION,
          content = actionStep(call.function.name, call.function.arguments),
        )
        val observation = executeWithPolicy(call.function.name, call.function.arguments)
        toolCallsLog += observation.record
        state.add(ChatMessage.tool(call.id, observation.observationText))
        flowStepRepository.appendStep(
          flowId = flowId,
          type = FlowStepType.OBSERVATION,
          content = observationStep(observation.observationText, observation.record.ok, observation.record.errorCode),
          snapshot = snapshotJson(state, iteration, toolCallsLog.size),
        )
        emit?.invoke(
          AgentStreamEvent.ToolResult(
            ok = observation.record.ok,
            text = observation.observationText,
            errorCode = observation.record.errorCode,
          ),
        )
      }
      // итерация цикла — допустимый самопереход PROCESSING → PROCESSING
      FlowTransitions.checkTransition(FlowStatus.PROCESSING, FlowStatus.PROCESSING)
    }

    // Хард-кап итераций без финального ответа модели
    FlowTransitions.checkTransition(FlowStatus.PROCESSING, FlowStatus.COMPLETED)
    val verdict = Verdict(VerdictStatus.NEEDS_REVIEW, listOf("CYCLE_LIMIT"))
    flowRepository.updateStatus(
      flowId,
      FlowStatus.COMPLETED.name,
      lastError = "iteration limit $MAX_ITERATIONS reached without final answer",
      verdict = verdictJson(verdict),
    )
    logger.warn { "flow hit cycle limit: flowId=$flowId iterations=$MAX_ITERATIONS" }
    val result = FlowResult(
      flowId = flowId,
      status = FlowStatus.COMPLETED,
      verdictStatus = VerdictStatus.NEEDS_REVIEW,
      reasons = verdict.reasons,
      reply = state.lastAssistantContent().orEmpty(),
      toolCalls = toolCallsLog,
      iterations = MAX_ITERATIONS,
      limitReached = true,
    )
    emit?.invoke(AgentStreamEvent.Final(result))
    return result
  }

  /** Шаг 5: строгий парсинг → пост-валидация → финальный дубль-чек → статус. */
  private suspend fun finalize(
    flowId: UUID,
    finalText: String,
    toolCallsLog: List<ToolCallRecord>,
    iterations: Int,
  ): FlowResult {
    val parsed = try {
      ContractParser.parse(finalText)
    } catch (e: ContractParseException) {
      flowStepRepository.appendStep(flowId, FlowStepType.ERROR, simpleObject("message" to (e.message ?: "parse error")))
      FlowTransitions.checkTransition(FlowStatus.PROCESSING, FlowStatus.COMPLETED)
      val verdict = Verdict(VerdictStatus.NEEDS_REVIEW, listOf("BAD_FINAL"))
      flowRepository.updateStatus(
        flowId, FlowStatus.COMPLETED.name,
        lastError = "final answer is not a valid contract: ${e.message?.take(200)}",
        verdict = verdictJson(verdict),
      )
      return FlowResult(flowId, FlowStatus.COMPLETED, VerdictStatus.NEEDS_REVIEW, verdict.reasons, reply = finalText, toolCalls = toolCallsLog, iterations = iterations)
    }

    val validated = ContractValidator.validate(parsed.dto)
    if (validated.verdict.status == VerdictStatus.REJECTED) {
      FlowTransitions.checkTransition(FlowStatus.PROCESSING, FlowStatus.REJECTED)
      flowRepository.updateStatus(flowId, FlowStatus.REJECTED.name, verdict = verdictJson(validated.verdict))
      logger.info { "flow rejected: flowId=$flowId reasons=${validated.verdict.reasons}" }
      return FlowResult(flowId, FlowStatus.REJECTED, VerdictStatus.REJECTED, validated.verdict.reasons, reply = finalText, toolCalls = toolCallsLog, iterations = iterations)
    }

    // Финальный дубль-чек перед записью (§8.5); ошибка эмбеддинга не блокирует флоу (resilience — этап 6)
    val dedup = runDedupCheck(parsed.dto, validated.startsAtInstant)
    val top = dedup?.top
    if (top != null && top.similarity >= thresholdHigh()) {
      FlowTransitions.checkTransition(FlowStatus.PROCESSING, FlowStatus.DUPLICATE)
      flowRepository.insertDuplicate(flowId, top.eventId, top.similarity, decidedBy = "AGENT")
      flowRepository.updateStatus(flowId, FlowStatus.DUPLICATE.name, verdict = verdictJson(validated.verdict))
      logger.info { "flow duplicate: flowId=$flowId existingEventId=${top.eventId} similarity=${top.similarity}" }
      return FlowResult(flowId, FlowStatus.DUPLICATE, validated.verdict.status, validated.verdict.reasons, duplicateOf = top.eventId, similarity = top.similarity, reply = finalText, toolCalls = toolCallsLog, iterations = iterations)
    }

    val greyZone = top != null && top.similarity >= thresholdLow()
    if (greyZone && validated.verdict.status == VerdictStatus.APPROVED) {
      // Серая зона 0.85–0.92: сомнения — человеку (ask_human появится на project)
      val reasons = validated.verdict.reasons + "POSSIBLE_DUPLICATE"
      val verdict = Verdict(VerdictStatus.NEEDS_REVIEW, reasons.distinct())
      FlowTransitions.checkTransition(FlowStatus.PROCESSING, FlowStatus.COMPLETED)
      flowRepository.updateStatus(flowId, FlowStatus.COMPLETED.name, verdict = verdictJson(verdict))
      return FlowResult(flowId, FlowStatus.COMPLETED, VerdictStatus.NEEDS_REVIEW, reasons.distinct(), similarity = top!!.similarity, reply = finalText, toolCalls = toolCallsLog, iterations = iterations)
    }

    if (validated.verdict.status == VerdictStatus.APPROVED && validated.startsAtInstant != null) {
      val eventId = eventRepository.insert(
        toEventRow(parsed.dto, parsed.raw, validated, flowId, dedup?.embedding),
      )
      FlowTransitions.checkTransition(FlowStatus.PROCESSING, FlowStatus.COMPLETED)
      flowRepository.updateStatus(flowId, FlowStatus.COMPLETED.name, verdict = verdictJson(validated.verdict))
      logger.info { "flow completed: flowId=$flowId eventId=$eventId" }
      return FlowResult(flowId, FlowStatus.COMPLETED, VerdictStatus.APPROVED, validated.verdict.reasons, eventId = eventId, similarity = top?.similarity, reply = finalText, toolCalls = toolCallsLog, iterations = iterations)
    }

    // NEEDS_REVIEW от валидатора: событие НЕ создаётся (§8 маппинг)
    FlowTransitions.checkTransition(FlowStatus.PROCESSING, FlowStatus.COMPLETED)
    flowRepository.updateStatus(flowId, FlowStatus.COMPLETED.name, verdict = verdictJson(validated.verdict))
    return FlowResult(flowId, FlowStatus.COMPLETED, VerdictStatus.NEEDS_REVIEW, validated.verdict.reasons, similarity = top?.similarity, reply = finalText, toolCalls = toolCallsLog, iterations = iterations)
  }

  /** Результат финального дубль-чека: вектор события (переиспользуется при insert) + топ-кандидат. */
  private data class DedupCheck(
    val embedding: FloatArray,
    val top: DuplicateCandidate?,
  )

  private suspend fun runDedupCheck(dto: EventContractDto, startsAt: Instant?): DedupCheck? {
    if (startsAt == null || dto.title.isNullOrBlank()) return null
    val embeddingText = listOfNotNull(
      dto.title?.trim(),
      dto.organizer?.trim(),
      startsAt.atZone(ZoneOffset.UTC).toLocalDate().toString(),
      dto.venueName?.trim(),
    ).filter { it.isNotBlank() }.joinToString(" | ")
    val embedding = try {
      embeddingClient.embed(embeddingText)
    } catch (e: EmbeddingException) {
      logger.warn(e) { "dedup skipped: embedding unavailable (${e.category})" }
      return null
    }
    val windowDays = ConfigLoader.getProperty("dedup.dateWindowDays", "3").toLong()
    val top = eventRepository.searchSimilar(
      embedding = embedding,
      dateFrom = startsAt.minusSeconds(windowDays * 24 * 3600),
      dateTo = startsAt.plusSeconds(windowDays * 24 * 3600),
    ).firstOrNull()
    return DedupCheck(embedding, top)
  }

  private fun toEventRow(
    dto: EventContractDto,
    raw: JsonNode,
    validated: ValidatedContract,
    flowId: UUID,
    embedding: FloatArray?,
  ) = EventRow(
    flowId = flowId,
    title = dto.title!!.trim(),
    description = dto.description,
    organizer = dto.organizer,
    city = validated.cityNormalized,
    isFree = dto.isFree,
    price = dto.price,
    formats = dto.formats.map { it.uppercase() },
    address = dto.address,
    venueName = dto.venueName,
    startsAt = validated.startsAtInstant!!,
    endsAt = validated.endsAtInstant,
    talks = dto.talks.takeIf { it.isNotEmpty() }?.let { jacksonMapper.valueToTree<JsonNode>(it) },
    registrationUrl = dto.registrationUrl,
    sourceUrls = dto.sourceUrls.takeIf { it.isNotEmpty() }?.let { jacksonMapper.valueToTree<JsonNode>(it) },
    language = dto.language,
    confidence = dto.confidence,
    raw = raw,
    embedding = embedding,
  )

  private suspend fun executeWithPolicy(toolName: String, argsJson: String): Observation =
    when (ToolPolicies.policyFor(toolName)) {
      ToolPolicyMode.ALLOW, ToolPolicyMode.ASK -> runTool(toolName, argsJson)

      ToolPolicyMode.BLOCK -> Observation(
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

  // --- JSON шагов/снапшота ---

  private fun reasonStep(message: ChatMessage, totalTokens: Int?): ObjectNode =
    jacksonMapper.createObjectNode().apply {
      put("reasoning", message.reasoningContent)
      put("contentPreview", message.content?.take(200))
      put("toolCallsCount", message.toolCalls?.size ?: 0)
      put("totalTokens", totalTokens)
    }

  private fun actionStep(name: String, arguments: String): ObjectNode =
    jacksonMapper.createObjectNode().apply {
      put("tool", name)
      put("arguments", arguments.take(2000))
    }

  private fun observationStep(text: String, ok: Boolean, errorCode: String?): ObjectNode =
    jacksonMapper.createObjectNode().apply {
      put("ok", ok)
      put("errorCode", errorCode)
      put("text", text.take(2000))
    }

  private fun simpleObject(vararg fields: Pair<String, String>): ObjectNode =
    jacksonMapper.createObjectNode().apply {
      fields.forEach { (k, v) -> put(k, v) }
    }

  /** state_snapshot (§8): версия формата, сообщения сессии, счётчики. */
  private fun snapshotJson(state: ConversationState, iteration: Int, toolCallCount: Int): ObjectNode =
    jacksonMapper.createObjectNode().apply {
      put("snapshotVersion", 1)
      put("iteration", iteration)
      put("toolCalls", toolCallCount)
      putArray("messages").apply {
        state.snapshot().forEach { add(jacksonMapper.valueToTree<JsonNode>(it)) }
      }
    }

  private fun verdictJson(verdict: Verdict): ObjectNode =
    jacksonMapper.createObjectNode().apply {
      put("status", verdict.status.name)
      putArray("reasons").apply { verdict.reasons.forEach { add(it) } }
    }

  private fun thresholdHigh(): Double = ConfigLoader.getProperty("dedup.thresholdHigh", "0.92").toDouble()
  private fun thresholdLow(): Double = ConfigLoader.getProperty("dedup.thresholdLow", "0.85").toDouble()

  private data class Observation(
    val record: ToolCallRecord,
    val observationText: String,
  )

  companion object {
    const val MAX_ITERATIONS = 10
  }
}
