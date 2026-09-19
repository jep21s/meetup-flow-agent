package org.jep21s.meetupflowagent.flow

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.jep21s.meetupflowagent.agent.context.FileContextProvider
import org.jep21s.meetupflowagent.agent.context.SystemPromptBuilder
import org.jep21s.meetupflowagent.agent.tools.AskHumanTool
import org.jep21s.meetupflowagent.agent.tools.SearchDuplicateTool
import org.jep21s.meetupflowagent.db.EventRepository
import org.jep21s.meetupflowagent.db.EventRow
import org.jep21s.meetupflowagent.db.FlowRepository
import org.jep21s.meetupflowagent.db.FlowStepRepository
import org.jep21s.meetupflowagent.db.FlowStepType
import io.mockk.coEvery
import io.mockk.mockk
import org.jep21s.meetupflowagent.guardrails.GuardrailsService
import org.jep21s.meetupflowagent.guardrails.GuardrailsVerdict
import org.jep21s.meetupflowagent.llm.EmbeddingClient
import org.jep21s.meetupflowagent.llm.LlmClient
import org.jep21s.meetupflowagent.observability.Metrics
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.jep21s.meetupflowagent.testsupport.FakeChatClient
import org.jep21s.meetupflowagent.testsupport.PostgresTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.math.sqrt

/**
 * Интеграционные сценарии FlowEngine (PLAN_5 §2): реальный Postgres+pgvector
 * (Testcontainers), скриптованная LLM (FakeChatClient), детерминированные
 * эмбеддинги; сами внешние вызовы отсутствуют.
 */
class AgentFlowServiceIT : PostgresTestBase() {

  private lateinit var fake: FakeChatClient
  private val eventRepository = EventRepository(testConnectivity())
  private val flowRepository = FlowRepository(testConnectivity())
  private val flowStepRepository = FlowStepRepository(testConnectivity())
  private val outboxRepository =
    org.jep21s.meetupflowagent.db.OutboxRepository(
      testConnectivity(),
      org.jep21s.meetupflowagent.db.DestinationRepository(testConnectivity()),
      eventRepository,
    )

  /** Эмбеддер с явными векторами по подстрокам текста (контроль similarity). */
  private class ScriptedEmbedder(
    private val default: FloatArray,
    private val byText: Map<String, FloatArray> = emptyMap(),
  ) : EmbeddingClient {
    override suspend fun embed(text: String): FloatArray =
      byText.entries.firstOrNull { text.contains(it.key, ignoreCase = true) }?.value ?: default
  }

  @BeforeEach
  fun resetScript() {
    fake = FakeChatClient()
  }

  private fun service(embedder: EmbeddingClient): AgentFlowService =
    AgentFlowService(
      llmClient = fake,
      tools = listOf(SearchDuplicateTool(embedder, eventRepository)),
      systemPromptBuilder = SystemPromptBuilder(FileContextProvider()),
      flowRepository = flowRepository,
      flowStepRepository = flowStepRepository,
      eventRepository = eventRepository,
      outboxRepository = outboxRepository,
      embeddingClient = embedder,
      guardrailsService = passGuardrails,
      metrics = metrics,
      humanRequestRepository = humanRequestRepository,
      usersRepository = usersRepository,
      proxyNotifier = noopNotifier,
    )

  private val passGuardrails: GuardrailsService = io.mockk.mockk {
    io.mockk.coEvery { check(any()) } returns GuardrailsVerdict(GuardrailsVerdict.Verdict.PASS)
  }

  private val metrics = Metrics.inMemory()

  private val humanRequestRepository = org.jep21s.meetupflowagent.db.HumanRequestRepository(testConnectivity())

  private val usersRepository = org.jep21s.meetupflowagent.db.UsersRepository(testConnectivity())

  private val noopNotifier = io.mockk.mockk<org.jep21s.meetupflowagent.notify.ProxyNotifier>(relaxed = true)

  @Test
  fun `happy path - search tool then APPROVED final to COMPLETED with event and steps`() {
    val embedder = ScriptedEmbedder(default = unit(7))
    fake.enqueue(
      FakeChatClient.toolCall(
        name = "search_duplicate",
        argumentsJson = """{"query":"PiterJS #61 | 2026-10-02","eventDate":"2026-10-02"}""",
      ),
    )
    fake.enqueue(FakeChatClient.text(approvedJson(), reasoning = "сначала проверю дубликаты"))

    val result = runBlocking { service(embedder).run("митап PiterJS 2 октября") }

    assertThat(result.status).isEqualTo(FlowStatus.COMPLETED)
    assertThat(result.verdictStatus.name).isEqualTo("APPROVED")
    assertThat(result.eventId).isNotNull
    assertThat(result.limitReached).isFalse()
    assertThat(result.toolCalls).hasSize(1)
    assertThat(result.toolCalls.single().ok).isTrue()

    // событие записано в календарь
    val event = runBlocking { eventRepository.findById(result.eventId!!) }
    assertThat(event!!.title).isEqualTo("PiterJS #61")
    assertThat(event.flowId).isEqualTo(result.flowId)
    // событие сохраняется С эмбеддингом (иначе последующие дубль-чеки ничего не найдут)
    assertThat(event.embedding).isNotNull
    assertThat(event.embedding!!.size).isEqualTo(768)

    // история шагов: GUARDRAILS (этап 6) → REASON(CoT) → ACTION → OBSERVATION → REASON → FINAL
    val steps = runBlocking { flowStepRepository.stepsByFlow(result.flowId) }
    assertThat(steps.map { it.type }).containsExactly(
      FlowStepType.GUARDRAILS,
      FlowStepType.REASON, FlowStepType.ACTION, FlowStepType.OBSERVATION,
      FlowStepType.REASON, FlowStepType.FINAL,
    )
    assertThat(steps.map { it.seq }).containsExactlyElementsOf((1..6).toList())
    assertThat(steps.first().content.path("verdict").asText()).isEqualTo("PASS")
    // CoT лежит во втором REASON-шаге (финальный ответ с reasoning)
    val finalReason = steps[4].content
    assertThat(finalReason.path("reasoning").asText()).contains("дубликаты")

    // снапшот состояния обновился (заготовка резюма)
    val flow = runBlocking { flowRepository.findById(result.flowId) }
    val snapshot = flow!!.stateSnapshot
    assertThat(snapshot).isNotNull
    assertThat(snapshot!!.path("snapshotVersion").asInt()).isEqualTo(1)
    assertThat(snapshot.path("messages").size()).isGreaterThan(0)
    assertThat(flow.status).isEqualTo("COMPLETED")

    // успешный результат получает публикацию outbox: доставка на сеянное назначение
    val deliveries = runBlocking { outboxRepository.deliveriesByFlow(result.flowId) }
    assertThat(deliveries).hasSize(1)
    assertThat(deliveries.single().destinationName).isEqualTo("telegram_main")
    assertThat(deliveries.single().status).isEqualTo("PENDING")
  }

  @Test
  fun `paid event is REJECTED without event`() {
    fake.enqueue(FakeChatClient.text(rejectedJson()))

    val result = runBlocking { service(ScriptedEmbedder(unit(1))).run("платная конференция") }

    assertThat(result.status).isEqualTo(FlowStatus.REJECTED)
    assertThat(result.verdictStatus.name).isEqualTo("REJECTED")
    assertThat(result.reasons).contains("PAID")
    assertThat(result.eventId).isNull()
    // отклонённый результат НЕ публикуется
    assertThat(runBlocking { outboxRepository.deliveriesByFlow(result.flowId) }).isEmpty()
  }

  @Test
  fun `duplicate above threshold becomes DUPLICATE with link`() {
    val vector = unit(11)
    val embedder = ScriptedEmbedder(default = unit(99), byText = mapOf("PiterJS" to vector))
    val existingId = seedEvent("PiterJS #61 (существующий)", vector, "2026-10-02T16:00:00Z")
    fake.enqueue(FakeChatClient.text(approvedJson()))

    val result = runBlocking { service(embedder).run("повтор PiterJS") }

    assertThat(result.status).isEqualTo(FlowStatus.DUPLICATE)
    assertThat(result.duplicateOf).isEqualTo(existingId)
    assertThat(result.similarity).isGreaterThanOrEqualTo(0.92)
    assertThat(result.eventId).isNull()

    val duplicates = runBlocking { flowRepository.findDuplicatesByFlow(result.flowId) }
    assertThat(duplicates).hasSize(1)
    assertThat(duplicates.single().existingEventId).isEqualTo(existingId)
    assertThat(duplicates.single().decidedBy).isEqualTo("AGENT")
  }

  @Test
  fun `grey zone similarity becomes COMPLETED with NEEDS_REVIEW and no event`() {
    // косинус 0.9 между запросом и существующим событием — серая зона 0.85–0.92
    val queryVector = unit(21)
    val greyVector = rotated(queryVector, 0.9)
    val embedder = ScriptedEmbedder(default = unit(99), byText = mapOf("PiterJS" to queryVector))
    seedEvent("Похожий митап", greyVector, "2026-10-02T16:00:00Z")
    fake.enqueue(FakeChatClient.text(approvedJson()))

    val result = runBlocking { service(embedder).run("похожий на PiterJS анонс") }

    assertThat(result.status).isEqualTo(FlowStatus.COMPLETED)
    assertThat(result.verdictStatus.name).isEqualTo("NEEDS_REVIEW")
    assertThat(result.reasons).contains("POSSIBLE_DUPLICATE")
    assertThat(result.eventId).isNull()
    assertThat(result.similarity ?: 0.0).isBetween(0.85, 0.92)
  }

  @Test
  fun `stalled cycle asks human and cap answer finalizes NEEDS_REVIEW`() {
    // одинаковый tool_call каждую итерацию: сигнатура не меняется 2 цикла → прогресса нет
    repeat(8) {
      fake.enqueue(
        FakeChatClient.toolCall(
          id = "call_$it",
          name = "search_duplicate",
          argumentsJson = """{"query":"митап"}""",
        ),
      )
    }

    val result = runBlocking { service(ScriptedEmbedder(unit(5))).run("бесконечный цикл") }

    // project-этап: кап циклов → ask_human (WAITING_HUMAN), не финал
    assertThat(result.status).isEqualTo(FlowStatus.WAITING_HUMAN)
    assertThat(result.reasons).contains("CYCLE_LIMIT")
    assertThat(result.iterations).isEqualTo(8)
    val flow = runBlocking { flowRepository.findById(result.flowId) }
    assertThat(flow!!.status).isEqualTo("WAITING_HUMAN")
    val snap = flow.stateSnapshot
    assertThat(snap).isNotNull
    assertThat(snap!!.path("capLimit").asBoolean()).isTrue()
    assertThat(snap.path("messages").size()).isGreaterThan(0)

    // ответ человека на cap-вопрос → COMPLETED / NEEDS_REVIEW / HUMAN_ANSWERED
    val resumed = runBlocking {
      service(ScriptedEmbedder(unit(5))).resume(result.flowId, "Данных нет, завершайте")
    }
    assertThat(resumed.status).isEqualTo(FlowStatus.COMPLETED)
    assertThat(resumed.verdictStatus.name).isEqualTo("NEEDS_REVIEW")
    assertThat(resumed.reasons).contains("CYCLE_LIMIT", "HUMAN_ANSWERED")
    val steps = runBlocking { flowStepRepository.stepsByFlow(result.flowId) }
    assertThat(steps.map { it.type }).contains(FlowStepType.HUMAN_ASK, FlowStepType.HUMAN_ANSWER)
  }

  @Test
  fun `ask_human tool suspends flow and answer resumes the cycle`() {
    // 1) модель просит ask_human; 2) после ответа — финальный APPROVED
    fake.enqueue(
      FakeChatClient.toolCall(
        name = "ask_human",
        argumentsJson = """{"question":"Уточните дату митапа","options":["2 октября","9 октября"]}""",
      ),
    )
    fake.enqueue(FakeChatClient.text(approvedJson()))

    val embedder = ScriptedEmbedder(default = unit(7))
    val svc = AgentFlowService(
      llmClient = fake,
      tools = listOf(SearchDuplicateTool(embedder, eventRepository), AskHumanTool()),
      systemPromptBuilder = SystemPromptBuilder(FileContextProvider()),
      flowRepository = flowRepository,
      flowStepRepository = flowStepRepository,
      eventRepository = eventRepository,
      outboxRepository = outboxRepository,
      embeddingClient = embedder,
      guardrailsService = passGuardrails,
      metrics = metrics,
      humanRequestRepository = humanRequestRepository,
      usersRepository = usersRepository,
      proxyNotifier = noopNotifier,
    )

    val notifications = mutableListOf<org.jep21s.meetupflowagent.notify.ProxyNotification>()
    coEvery { noopNotifier.notify(any()) } answers {
      notifications += firstArg<org.jep21s.meetupflowagent.notify.ProxyNotification>()
    }
    val suspended = runBlocking { svc.run("митап без даты") }

    assertThat(suspended.status).isEqualTo(FlowStatus.WAITING_HUMAN)
    assertThat(suspended.reasons).contains("HUMAN_INPUT_REQUIRED")
    // уведомление прокси: текст вопроса + options
    val hitl = notifications.first { it.event == "HUMAN_INPUT_REQUIRED" }
    assertThat(hitl.text).contains("дату")
    assertThat(hitl.options).hasSize(2)
    // вопрос в human_requests (PENDING) и шаг HUMAN_ASK записан
    val flow = runBlocking { flowRepository.findById(suspended.flowId) }
    assertThat(flow!!.status).isEqualTo("WAITING_HUMAN")
    val steps = runBlocking { flowStepRepository.stepsByFlow(suspended.flowId) }
    assertThat(steps.map { it.type }).contains(FlowStepType.HUMAN_ASK)
    // снапшот хранит прерванный tool_call для резюма
    val snap = flow.stateSnapshot
    assertThat(snap).isNotNull
    assertThat(snap!!.path("pendingToolCall").path("name").asText()).isEqualTo("ask_human")

    // первый ответ побеждает и резюмит цикл → финал APPROVED
    val first = runBlocking { humanRequestRepository.submitAnswer(suspended.flowId, 1L, "2 октября") }
    assertThat(first).isTrue()
    val resumed = runBlocking { svc.resume(suspended.flowId, "2 октября") }
    assertThat(resumed.status).isEqualTo(FlowStatus.COMPLETED)
    assertThat(resumed.verdictStatus.name).isEqualTo("APPROVED")
    assertThat(resumed.eventId).isNotNull()
    // опоздавший ответ не побеждает
    val second = runBlocking { humanRequestRepository.submitAnswer(suspended.flowId, 2L, "9 октября") }
    assertThat(second).isFalse()
  }

  @Test
  fun `unparseable final answer becomes NEEDS_REVIEW with BAD_FINAL`() {
    fake.enqueue(FakeChatClient.text("финальный ответ прозой без JSON"))

    val result = runBlocking { service(ScriptedEmbedder(unit(3))).run("митап") }

    assertThat(result.status).isEqualTo(FlowStatus.COMPLETED)
    assertThat(result.verdictStatus.name).isEqualTo("NEEDS_REVIEW")
    assertThat(result.reasons).contains("BAD_FINAL")
    val steps = runBlocking { flowStepRepository.stepsByFlow(result.flowId) }
    assertThat(steps.map { it.type }).contains(FlowStepType.ERROR)
  }

  // --- helpers ---

  private fun seedEvent(title: String, embedding: FloatArray, startsAtIso: String): UUID =
    runBlocking {
      eventRepository.insert(
        EventRow(title = title, startsAt = Instant.parse(startsAtIso), embedding = embedding),
      )
    }

  private fun approvedJson() =
    """{"title":"PiterJS #61","description":"митап","organizer":"PiterJS",
       "city":"Санкт-Петербург","isFree":true,"formats":["OFFLINE"],
       "address":"Кожевенная линия, 40","venueName":"Севкабель Порт",
       "startsAt":"2026-10-02T19:00+03:00","endsAt":"2026-10-02T22:00+03:00",
       "registrationUrl":"https://piterjs.org","sourceUrls":["https://t.me/piterjs"],
       "language":"RU","confidence":0.9}"""

  private fun rejectedJson() =
    """{"title":"Heisenbug","city":"Санкт-Петербург","isFree":false,"price":"от 3500 ₽",
       "formats":["OFFLINE"],"startsAt":"2026-11-14T10:00+03:00","endsAt":"2026-11-14T18:00+03:00",
       "venueName":"Конгресс-холл","registrationUrl":"https://heisenbug.ru"}"""

  /** Детерминированный единичный вектор заданной размерности. */
  private fun unit(seed: Long, dim: Int = 768): FloatArray {
    var state = seed
    val v = FloatArray(dim) {
      state = state * 6364136223846793005L + 1442695040888963407L
      ((state ushr 33) % 2001 - 1000).toFloat() / 1000f
    }
    return normalize(v)
  }

  /** Вектор с косинусной близостью cos к base (через ортогональную добавку). */
  private fun rotated(base: FloatArray, cos: Double): FloatArray {
    val ortho = normalize(FloatArray(base.size) { it.toFloat() * 0.001f + 0.7f }.let { diff(it, base) })
    val sin = sqrt(1 - cos * cos)
    val result = FloatArray(base.size) { i -> (base[i] * cos + ortho[i] * sin).toFloat() }
    return normalize(result)
  }

  private fun diff(a: FloatArray, b: FloatArray): FloatArray =
    FloatArray(a.size) { i -> a[i] - b[i] }

  private fun normalize(v: FloatArray): FloatArray {
    val norm = sqrt(v.map { it.toDouble() * it.toDouble() }.sum()).toFloat()
    for (i in v.indices) v[i] /= norm
    return v
  }
}
