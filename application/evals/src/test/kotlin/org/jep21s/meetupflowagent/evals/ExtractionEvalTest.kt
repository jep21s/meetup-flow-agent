package org.jep21s.meetupflowagent.evals

import com.fasterxml.jackson.databind.JsonNode
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions
import org.jep21s.meetupflowagent.agent.context.FileContextProvider
import org.jep21s.meetupflowagent.agent.context.SystemPromptBuilder
import org.jep21s.meetupflowagent.agent.tools.FetchWebPageTool
import org.jep21s.meetupflowagent.agent.tools.SearchDuplicateTool
import org.jep21s.meetupflowagent.db.DatabaseConnectivity
import org.jep21s.meetupflowagent.db.EventRepository
import org.jep21s.meetupflowagent.db.FlowRepository
import org.jep21s.meetupflowagent.db.FlowStepRepository
import org.jep21s.meetupflowagent.db.HumanRequestRepository
import org.jep21s.meetupflowagent.flow.AgentFlowService
import org.jep21s.meetupflowagent.flow.FlowStatus
import org.jep21s.meetupflowagent.llm.KtorOpenAiLlmClient
import org.jep21s.meetupflowagent.llm.YandexEmbeddingClient
import org.jep21s.meetupflowagent.notify.ProxyNotifier
import org.jep21s.meetupflowagent.observability.Metrics
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

private val logger = KotlinLogging.logger { }

/**
 * Extraction eval (§15): полные флоу с реальной LLM/эмбеддингами и реальной БД
 * (docker-compose postgres на localhost:5432 — `docker compose up -d postgres`).
 * Порог: verdict-точность ≥ 0.9. Запуск: source .env → gradlew eval.
 */
@Tag("eval")
class ExtractionEvalTest {

  companion object {
    @BeforeAll
    @JvmStatic
    fun requireEnv() {
      assumeTrue(!System.getenv("LLM_API_KEY").isNullOrBlank(), "LLM_API_KEY не задан — extraction eval пропущен")
      assumeTrue(!System.getenv("EMBEDDING_API_KEY").isNullOrBlank(), "EMBEDDING_API_KEY не задан — extraction eval пропущен")
    }
  }

  private val harness = GuardrailsEvalTest()

  @Test
  fun `extraction verdict accuracy`() {
    val db = DatabaseConnectivity(org.jep21s.meetupflowagent.db.LiquibaseRunner())
    val eventRepository = EventRepository(db)
    val flowRepository = FlowRepository(db)
    val stepRepository = FlowStepRepository(db)
    val noopNotifier = object : ProxyNotifier {
      override suspend fun notify(notification: org.jep21s.meetupflowagent.notify.ProxyNotification) = Unit
    }
    val guardrails = org.jep21s.meetupflowagent.guardrails.LlmGuardrails(KtorOpenAiLlmClient())
    val service = AgentFlowService(
      llmClient = KtorOpenAiLlmClient(),
      tools = listOf(FetchWebPageTool(), SearchDuplicateTool(YandexEmbeddingClient(), eventRepository)),
      systemPromptBuilder = SystemPromptBuilder(FileContextProvider()),
      flowRepository = flowRepository,
      flowStepRepository = stepRepository,
      eventRepository = eventRepository,
      outboxRepository = org.jep21s.meetupflowagent.db.OutboxRepository(
        db,
        org.jep21s.meetupflowagent.db.DestinationRepository(db),
        eventRepository,
      ),
      embeddingClient = YandexEmbeddingClient(),
      guardrailsService = org.jep21s.meetupflowagent.guardrails.GuardrailsService(guardrails),
      metrics = Metrics.inMemory(),
      humanRequestRepository = HumanRequestRepository(db),
      proxyNotifier = noopNotifier,
    )

    // чистим рабочие таблицы: дубль-чек не должен находить события прошлых прогонов
    db.dataSource.connection.use { connection ->
      connection.createStatement().use { stmt -> stmt.execute("TRUNCATE outbox_deliveries, outbox_messages, duplicates, events, flow_steps, human_requests, flows, inbox_messages") }
    }

    val cases = harness.loadCases("/golden/extraction/cases.json")
    var correctVerdicts = 0
    var verdictChecked = 0
    val rows = mutableListOf<String>()

    cases.forEach { case ->
      val input = case.path("input").asText()
      val expectedVerdict = case.path("expected").path("verdict").asText()
      val result = runBlocking { service.run(input) }
      val actualVerdict = result.verdictStatus.name
      verdictChecked++
      val verdictOk = actualVerdict == expectedVerdict || (expectedVerdict == "NEEDS_REVIEW" && actualVerdict == "NEEDS_REVIEW")
      if (verdictOk) correctVerdicts++
      rows += "| ${case.path("id").asText()} | $expectedVerdict | $actualVerdict | ${if (verdictOk) "✅" else "❌"} | ${result.status} |"
      logger.info { "extraction eval ${case.path("id").asText()}: expected=$expectedVerdict actual=$actualVerdict flow=${result.status}" }
    }

    val accuracy = correctVerdicts.toDouble() / verdictChecked
    harness.writeReport(
      """
      ## Extraction eval (glm-5.3-flash, полный флоу)
      | Кейс | Ожидание | Факт | OK | Статус флоу |
      |---|---|---|---|---|
      ${rows.joinToString("\n")}

      **Verdict-точность**: $correctVerdicts/$verdictChecked = ${"%.2f".format(accuracy)} (порог ≥ 0.9)
      """.trimIndent(),
    )
    Assertions.assertThat(accuracy).describedAs("verdict accuracy").isGreaterThanOrEqualTo(0.9)
  }
}
