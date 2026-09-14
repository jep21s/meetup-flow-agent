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
import org.jep21s.meetupflowagent.agent.tools.HumanInputRequiredException
import org.jep21s.meetupflowagent.agent.tools.ToolResult
import org.jep21s.meetupflowagent.db.DuplicateCandidate
import org.jep21s.meetupflowagent.guardrails.GuardrailsService
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
import org.jep21s.meetupflowagent.llm.LlmException
import org.jep21s.meetupflowagent.notify.ProxyNotification
import org.jep21s.meetupflowagent.scheduler.RetrySchedule
import org.jep21s.meetupflowagent.llm.StreamDelta
import org.jep21s.meetupflowagent.llm.dto.ChatCompletionRequest
import org.jep21s.meetupflowagent.llm.dto.ChatMessage
import org.jep21s.meetupflowagent.llm.dto.FunctionSpec
import org.jep21s.meetupflowagent.observability.Metrics
import org.jep21s.meetupflowagent.llm.dto.ToolSpec
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.core.annotation.Singleton
import org.slf4j.MDC
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

private val logger = KotlinLogging.logger { }


/**
 * FlowEngine — управляемый сценарий (стейт-машина, PLAN_5 §1.2 + PLAN_6):
 *
 * 0. guardrails (детерминизм → glm-5.3): вердикт ≠ PASS → REJECTED, цикл не
 *    запускается (токены экономятся);
 * 1. извлечение — цикл Reason→Act→Observe (fetch_web_page / search_duplicate);
 * 2. fetch_web_page при нехватке данных и наличии ссылки (решение модели);
 * 3. search_duplicate при достаточных фактах (решение модели, SOP промпта);
 * 4. финальный JSON модели;
 * 5. пост-валидация [ContractValidator] + финальный дубль-чек (≥0.92 →
 *    DUPLICATE; 0.85–0.92 → NEEDS_REVIEW) + вердикт → статус.
 *
 * Каждый шаг пишется в flow_steps (включая CoT в REASON), снапшот состояния — в
 * flows.state_snapshot; на каждый шаг — структурированный лог (action, status,
 * latency_ms, tokens, model; БЕЗ CoT и тел запросов, §14). Лимит циклов —
 * [CycleLimiter] + [ProgressDetector] (8 → +4 при прогрессе → 16). Метрики —
 * [Metrics]. HITL/резюм — этап project.
 */
@Singleton
class AgentFlowService(
  private val llmClient: LlmClient,
  private val tools: List<AgentTool>,
  private val systemPromptBuilder: SystemPromptBuilder,
  private val flowRepository: FlowRepository,
  private val flowStepRepository: FlowStepRepository,
  private val eventRepository: EventRepository,
  private val outboxRepository: org.jep21s.meetupflowagent.db.OutboxRepository,
  private val embeddingClient: EmbeddingClient,
  private val guardrailsService: GuardrailsService,
  private val metrics: Metrics,
  private val humanRequestRepository: org.jep21s.meetupflowagent.db.HumanRequestRepository,
  private val proxyNotifier: org.jep21s.meetupflowagent.notify.ProxyNotifier,
  private val flowEventBus: FlowEventBus = FlowEventBus(),
  private val tracing: org.jep21s.meetupflowagent.observability.Tracing = org.jep21s.meetupflowagent.observability.Tracing(),
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

  /**
   * Исполнение флоу, созданного inbox-поллером (§12): guardrails + цикл как в
   * [run]; RETRYABLE-падение любого шага (после исчерпания in-request retry) →
   * WAITING_RETRY со шкалой retry.schedule (§13), сообщение остаётся необработанным
   * до RetryPoller. Повтор выполняется с начала флоу (дубль-чек идемпотентен;
   * история попыток накапливается в flow_steps).
   */
  suspend fun executeInboxFlow(flowId: UUID, inboxId: UUID, rawText: String): FlowResult {
    val model = ConfigLoader.getRequiredProperty(
      "llm.agent.model",
      "llm.agent.model is not configured",
    )
    val startedAt = System.nanoTime()
    return try {
      val result = MDC.putCloseable("flowId", flowId.toString()).use {
        withContext(MDCContext()) {
          val span = tracing.startFlowSpan(flowId.toString())
          try {
            span.setAttribute("inboxId", inboxId.toString())
            runLoop(flowId, rawText, model, emit = null)
          } finally {
            span.end()
          }
        }
      }
      metrics.flowStatus(result.status.name)
      metrics.flowDuration(Duration.ofNanos(System.nanoTime() - startedAt))
      result
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: LlmException) {
      if (e.category != LlmException.Category.RETRYABLE) throw e
      markWaitingRetry(flowId, e)
      FlowResult(flowId, FlowStatus.WAITING_RETRY, VerdictStatus.NEEDS_REVIEW, listOf("RETRY_SCHEDULED"))
    } catch (e: Exception) {
      // БД/сеть/прочие транзиентные сбои шага — тоже на retry-шкалу (§13)
      markWaitingRetry(flowId, e)
      FlowResult(flowId, FlowStatus.WAITING_RETRY, VerdictStatus.NEEDS_REVIEW, listOf("RETRY_SCHEDULED"))
    }
  }

  private suspend fun markWaitingRetry(flowId: UUID, error: Exception) {
    val schedule = RetrySchedule.parse(ConfigLoader.getProperty("retry.schedule", "1m,5m,15m,1h,6h"))
    val existing = flowRepository.findById(flowId)
    val attempt = (existing?.retryCount ?: 0) + 1
    val maxAttempts = ConfigLoader.getProperty("retry.maxAttempts", "6").toInt()
    if (attempt > maxAttempts) {
      flowRepository.markFailedPermanent(flowId, error.message)
      return
    }
    flowRepository.markWaitingRetry(
      flowId,
      error.message,
      attempt,
      Instant.now().plus(RetrySchedule.nextDelay(schedule, attempt - 1)),
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
    val startedAt = System.nanoTime()
    // flowId в MDC распространяется на все корутины флоу (MDCContext)
    val result = MDC.putCloseable("flowId", flowId.toString()).use {
      withContext(MDCContext()) {
        val span = tracing.startFlowSpan(flowId.toString())
        try {
          span.setAttribute("message.length", message.length.toLong())
          runLoop(flowId, message, model, emit)
        } finally {
          span.end()
        }
      }
    }
    metrics.flowStatus(result.status.name)
    metrics.flowDuration(Duration.ofNanos(System.nanoTime() - startedAt))
    return result
  }

  /** Контекст резюма после ответа человека (§8.3): восстановленное состояние цикла. */
  data class ResumeContext(
    val state: ConversationState,
    val iteration: Int,
  )

  private suspend fun runLoop(
    flowId: UUID,
    message: String,
    model: String,
    emit: (suspend (AgentStreamEvent) -> Unit)?,
    resume: ResumeContext? = null,
  ): FlowResult {
    // Шаг 0: guardrails до агентского цикла (§8.1); при резюме уже пройдены
    val guardrailsVerdict = if (resume != null) {
      org.jep21s.meetupflowagent.guardrails.GuardrailsVerdict(
        org.jep21s.meetupflowagent.guardrails.GuardrailsVerdict.Verdict.PASS,
      )
    } else {
      guardrailsService.check(message)
    }
    metrics.guardrailsVerdict(guardrailsVerdict.verdict.name)
    val guardrailsSeq = flowStepRepository.appendStep(
      flowId = flowId,
      type = FlowStepType.GUARDRAILS,
      content = jacksonMapper.createObjectNode().apply {
        put("verdict", guardrailsVerdict.verdict.name)
        putArray("reasons").apply { guardrailsVerdict.reasons.forEach { add(it) } }
      },
    )
    logStep(
      "guardrails",
      "проверка входящего сообщения",
      guardrailsVerdict.verdict.name,
      null,
      null,
      ConfigLoader.getProperty("llm.guardrails.model", "glm-5.3"),
      guardrailsSeq,
    )
    if (!guardrailsVerdict.isPass) {
      FlowTransitions.checkTransition(FlowStatus.PROCESSING, FlowStatus.REJECTED)
      val reasons = guardrailsVerdict.reasons.ifEmpty { listOf(guardrailsVerdict.verdict.name) }
      flowRepository.updateStatus(
        flowId,
        FlowStatus.REJECTED.name,
        verdict = jacksonMapper.createObjectNode().apply {
          put("status", "REJECTED")
          putArray("reasons").apply { reasons.forEach { add(it) } }
        },
      )
      logger.warn { "flow rejected by guardrails: flowId=$flowId verdict=${guardrailsVerdict.verdict} reasons=$reasons" }
      val result = FlowResult(
        flowId = flowId,
        status = FlowStatus.REJECTED,
        verdictStatus = VerdictStatus.REJECTED,
        reasons = reasons,
        reply = "",
        iterations = 0,
      )
      emit?.invoke(AgentStreamEvent.Final(result))
      return result
    }

    val state = resume?.state ?: ConversationState(
      listOf(
        ChatMessage.system(systemPrompt),
        ChatMessage.user(message),
      ),
    )
    val toolCallsLog = mutableListOf<ToolCallRecord>()
    val progressDetector = ProgressDetector()
    val cycleLimiter = cycleLimiterFromConfig()
    var iteration = startIterationContinuation(resume)

    while (true) {
      iteration++
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

      val reasonSeq = flowStepRepository.appendStep(
        flowId = flowId,
        type = FlowStepType.REASON,
        content = reasonStep(assistantMessage, response.usage?.totalTokens),
        tokens = response.usage?.totalTokens,
        latencyMs = latencyMs,
        snapshot = snapshotJson(state, iteration, toolCallsLog.size),
      )
      MDC.put("stepSeq", reasonSeq.toString())
      flowEventBus.publish(FlowEvent(flowId, "step", reasonSeq, mapOf("type" to "REASON")))
      logStep(
        "llm_call",
        if (assistantMessage.toolCalls.orEmpty().isEmpty()) "финальный ответ модели" else "выбор инструмента",
        "ok",
        latencyMs,
        response.usage?.totalTokens?.toLong(),
        model,
        reasonSeq,
      )

      val calls = assistantMessage.toolCalls.orEmpty()
      if (calls.isEmpty()) {
        val finalText = assistantMessage.content.orEmpty()
        val finalSeq = flowStepRepository.appendStep(
          flowId = flowId,
          type = FlowStepType.FINAL,
          content = simpleObject("reply" to finalText.take(4000)),
          tokens = response.usage?.completionTokens,
          snapshot = snapshotJson(state, iteration, toolCallsLog.size),
        )
        logStep("final", "итоговый JSON агента", "completed", null, response.usage?.completionTokens?.toLong(), model, finalSeq)
        flowEventBus.publish(FlowEvent(flowId, "step", finalSeq, mapOf("type" to "FINAL")))
        metrics.reactCycles(iteration)
        val result = finalize(flowId, finalText, toolCallsLog, iteration)
        emit?.invoke(AgentStreamEvent.Final(result))
        return result
      }

      state.add(assistantMessage)
      try {
        for (call in calls) {
        emit?.invoke(AgentStreamEvent.ToolCall(call.function.name, call.function.arguments))
        flowStepRepository.appendStep(
          flowId = flowId,
          type = FlowStepType.ACTION,
          content = actionStep(call.function.name, call.function.arguments),
        )
        val toolStartedAt = System.nanoTime()
        val observation = try {
          executeWithPolicy(call.function.name, call.function.arguments)
        } catch (e: HumanInputRequiredException) {
          // тул не знает свой call-id — восполняем прерванный вызов для резюма
          throw if (e.pendingToolCall != null) e
          else HumanInputRequiredException(
            e.question,
            HumanInputRequiredException.PendingToolCall(call.id, call.function.name, call.function.arguments),
          )
        }
        val toolLatencyMs = (System.nanoTime() - toolStartedAt) / 1_000_000
        toolCallsLog += observation.record
        metrics.toolCall(call.function.name, if (observation.record.ok) "ok" else "error")
        state.add(ChatMessage.tool(call.id, observation.observationText))
        flowStepRepository.appendStep(
          flowId = flowId,
          type = FlowStepType.OBSERVATION,
          content = observationStep(observation.observationText, observation.record.ok, observation.record.errorCode),
          snapshot = snapshotJson(state, iteration, toolCallsLog.size),
        )
        logStep(
          "tool_call",
          "исполнение ${call.function.name}",
          if (observation.record.ok) "completed" else "error:${observation.record.errorCode}",
          toolLatencyMs,
          null,
          model,
          null,
        )
        emit?.invoke(
          AgentStreamEvent.ToolResult(
            ok = observation.record.ok,
            text = observation.observationText,
            errorCode = observation.record.errorCode,
          ),
        )
        }
      } catch (e: HumanInputRequiredException) {
        // HITL (§8.3): снапшот → human_requests → WAITING_HUMAN → уведомление; корутина завершается
        val result = suspendForHuman(flowId, e, state, iteration, toolCallsLog)
        emit?.invoke(AgentStreamEvent.Final(result))
        return result
      }
      // итерация цикла — допустимый самопереход PROCESSING → PROCESSING
      FlowTransitions.checkTransition(FlowStatus.PROCESSING, FlowStatus.PROCESSING)

      // Лимит циклов с прогресс-детектором (§8.4): сигнатура = факты черновика + история тулов
      val facts = draftFacts(state.lastAssistantContent())
      val hasProgress = progressDetector.update(facts, toolCallsLog.map { it.name to it.args })
      val decision = cycleLimiter.onIterationCompleted(iteration, hasProgress)
      if (decision.extended) {
        logger.info { "cycle limit extended: flowId=$flowId iteration=$iteration (${decision.reason})" }
      }
      if (decision.stop) {
        // на project-этапе кап циклов — вопрос человеку (§8.4), а не NEEDS_REVIEW
        metrics.reactCycles(iteration)
        val question = jacksonMapper.createObjectNode().apply {
          put("question", "Цикл агента остановлен после $iteration итераций: ${decision.reason}. " +
            "Уточните недостающие данные о мероприятии (дата/место/регистрация) или подтвердите, что данных нет.")
          put("contextSummary", state.lastAssistantContent()?.take(300) ?: "")
          putArray("options").apply {
            add("Данных достаточно — завершить с NEEDS_REVIEW")
          }
        }
        val result = suspendForHuman(
          flowId,
          HumanInputRequiredException(question, pendingToolCall = null),
          state,
          iteration,
          toolCallsLog,
          capLimit = true,
        )
        emit?.invoke(AgentStreamEvent.Final(result))
        return result
      }
    }
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
      // Событие и публикация outbox — атомарно: успешный результат сразу получает
      // задания на доставку во все активные назначения (доставит OutboxPoller).
      val eventId = outboxRepository.insertEventAndEnqueue(
        toEventRow(parsed.dto, parsed.raw, validated, flowId, dedup?.embedding),
      ).eventId
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

  /**
   * Переводит флоу в WAITING_HUMAN: полный снапшот цикла в state_snapshot,
   * вопрос в human_requests, шаг HUMAN_ASK, уведомление прокси. Корутина
   * завершается — процесс не блокируется (§8.3).
   */
  private suspend fun suspendForHuman(
    flowId: UUID,
    e: HumanInputRequiredException,
    state: ConversationState,
    iteration: Int,
    toolCallsLog: List<ToolCallRecord>,
    capLimit: Boolean = false,
  ): FlowResult {
    val snapshot = jacksonMapper.createObjectNode().apply {
      put("snapshotVersion", 1)
      put("iteration", iteration)
      put("capLimit", capLimit)
      e.pendingToolCall?.let { pending ->
        putObject("pendingToolCall").apply {
          put("id", pending.id)
          put("name", pending.name)
          put("arguments", pending.arguments)
        }
      }
      putArray("messages").apply {
        state.snapshot().forEach { add(jacksonMapper.valueToTree<JsonNode>(it)) }
      }
    }
    humanRequestRepository.create(flowId, e.question)
    flowStepRepository.appendStep(
      flowId = flowId,
      type = FlowStepType.HUMAN_ASK,
      content = e.question,
      snapshot = snapshot,
    )
    FlowTransitions.checkTransition(FlowStatus.PROCESSING, FlowStatus.WAITING_HUMAN)
    flowRepository.updateStatus(flowId, FlowStatus.WAITING_HUMAN.name)
    proxyNotifier.notify(
      ProxyNotification(
        flowId = flowId,
        event = "HUMAN_INPUT_REQUIRED",
        userIds = emptyList(),
        text = e.question.path("question").asText(),
        options = e.question.path("options").mapNotNull { it.takeIf { it.isTextual }?.asText() },
      ),
    )
    logger.info { "flow waiting human: flowId=$flowId iteration=$iteration capLimit=$capLimit" }
    return FlowResult(
      flowId = flowId,
      status = FlowStatus.WAITING_HUMAN,
      verdictStatus = VerdictStatus.NEEDS_REVIEW,
      reasons = listOf(if (capLimit) "CYCLE_LIMIT" else "HUMAN_INPUT_REQUIRED"),
      reply = state.lastAssistantContent().orEmpty(),
      toolCalls = toolCallsLog,
      iterations = iteration,
    )
  }

  /**
   * Резюм после ответа человека (§8.3): ответ добавляется как observation к
   * прерванному tool_call и цикл продолжается; для cap-вопроса (без
   * прерванного вызова) флоу завершается NEEDS_REVIEW с зафиксированным
   * ответом.
   */
  suspend fun resume(flowId: UUID, answer: String): FlowResult {
    val flow = flowRepository.findById(flowId)
      ?: error("flow $flowId not found")
    check(flow.status == FlowStatus.WAITING_HUMAN.name) { "flow $flowId is not WAITING_HUMAN (status=${flow.status})" }
    val snapshot = flow.stateSnapshot ?: error("flow $flowId has no state snapshot to resume")

    val messages = snapshot.path("messages").map { jacksonMapper.treeToValue(it, ChatMessage::class.java) }
    val state = ConversationState(messages)
    val iteration = snapshot.path("iteration").asInt()
    val pending = snapshot.path("pendingToolCall").takeIf { it.isObject }

    flowStepRepository.appendStep(
      flowId = flowId,
      type = FlowStepType.HUMAN_ANSWER,
      content = jacksonMapper.createObjectNode().apply { put("answer", answer.take(2000)) },
    )

    if (pending == null) {
      // cap-вопрос: подтверждение человека — финализируем NEEDS_REVIEW с ответом
      FlowTransitions.checkTransition(FlowStatus.WAITING_HUMAN, FlowStatus.COMPLETED)
      val verdict = Verdict(VerdictStatus.NEEDS_REVIEW, listOf("CYCLE_LIMIT", "HUMAN_ANSWERED"))
      flowRepository.updateStatus(
        flowId,
        FlowStatus.COMPLETED.name,
        lastError = "human answered cap-limit question: ${answer.take(200)}",
        verdict = verdictJson(verdict),
      )
      metrics.flowStatus(FlowStatus.COMPLETED.name)
      return FlowResult(flowId, FlowStatus.COMPLETED, VerdictStatus.NEEDS_REVIEW, verdict.reasons, reply = answer)
    }

    FlowTransitions.checkTransition(FlowStatus.WAITING_HUMAN, FlowStatus.PROCESSING)
    flowRepository.updateStatus(flowId, FlowStatus.PROCESSING.name)
    state.add(
      ChatMessage.tool(
        pending.path("id").asText(),
        "Ответ человека: ${answer.take(2000)}",
      ),
    )
    val model = ConfigLoader.getRequiredProperty("llm.agent.model", "llm.agent.model is not configured")
    return runLoop(flowId, "", model, emit = null, resume = ResumeContext(state, iteration))
  }

  // --- JSON шагов/снапшота ---

  /**
   * Структурированный лог шага (стиль памятки курса, §14): action, статус,
   * latency/tokens/model — БЕЗ reasoning_content и тел запросов; flowId/stepSeq
   * идут через MDC.
   */
  private fun logStep(
    action: String,
    reasonSummary: String,
    status: String,
    latencyMs: Long?,
    tokens: Long?,
    model: String,
    stepSeq: Int?,
  ) {
    logger.info {
      buildString {
        append("action=").append(action)
        append(" reason_summary=\"").append(reasonSummary).append("\"")
        append(" status=").append(status)
        latencyMs?.let { append(" latency_ms=").append(it) }
        tokens?.let { append(" tokens=").append(it) }
        append(" model=").append(model)
        stepSeq?.let { append(" step=").append(it) }
      }
    }
  }

  /** Извлечённые факты черновика контракта (для прогресс-детектора). */
  private fun draftFacts(content: String?): Set<String> {
    if (content.isNullOrBlank()) return emptySet()
    val parsed = runCatching { ContractParser.parse(content) }.getOrNull() ?: return emptySet()
    return buildSet {
      parsed.dto.title?.let { add("title=$it") }
      parsed.dto.startsAt?.let { add("startsAt=$it") }
      parsed.dto.city?.let { add("city=$it") }
      parsed.dto.venueName?.let { add("venueName=$it") }
      parsed.dto.organizer?.let { add("organizer=$it") }
      parsed.dto.registrationUrl?.let { add("registrationUrl=$it") }
    }
  }

  private fun startIterationContinuation(resume: ResumeContext?): Int = resume?.iteration ?: 0

  private fun cycleLimiterFromConfig(): CycleLimiter = CycleLimiter(
    baseLimit = ConfigLoader.getProperty("react.baseLimit", "8").toInt(),
    extendBy = ConfigLoader.getProperty("react.extendBy", "4").toInt(),
    maxLimit = ConfigLoader.getProperty("react.maxLimit", "16").toInt(),
  )

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

}
