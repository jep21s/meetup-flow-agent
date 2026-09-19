package org.jep21s.meetupflowagent.guardrails

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jep21s.meetupflowagent.llm.LlmClient
import org.jep21s.meetupflowagent.llm.dto.ChatCompletionRequest
import org.jep21s.meetupflowagent.llm.dto.ChatMessage
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.koin.core.annotation.Singleton

private val logger = KotlinLogging.logger { }

/**
 * LLM-модератор входящих сообщений — сильная модель (glm-5.3, роутинг §1 hw1:
 * «безопасность → продвинутая модель»). Промпт: prompts/guardrails-system.md;
 * ответ строго `{"verdict":"PASS|INJECTION|SUSPICIOUS|OFF_TOPIC","reasons":[…]}`.
 * Невалидный ответ модели → SUSPICIOUS (fail-safe в сторону блокировки).
 * Конструктор с LlmClient — для тестов (FakeChatClient).
 */
@Singleton
class LlmGuardrails(private val llmClient: LlmClient) {

  private val systemPrompt: String by lazy {
    javaClass.classLoader.getResourceAsStream(PROMPT_RESOURCE)
      ?.bufferedReader()?.readText()
      ?: throw IllegalStateException("Guardrails prompt not found: $PROMPT_RESOURCE")
  }

  suspend fun check(text: String): GuardrailsVerdict {
    val model = ConfigLoader.getRequiredProperty(
      "llm.guardrails.model",
      "llm.guardrails.model is not configured",
    )
    val response = llmClient.complete(
      ChatCompletionRequest(
        model = model,
        messages = listOf(
          ChatMessage.system(systemPrompt),
          ChatMessage.user(text.take(MAX_GUARDRAILS_CHARS)),
        ),
      ),
    )
    val raw = response.firstMessage().content.orEmpty()

    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) {
      logger.warn { "guardrails unparsable answer (no JSON), fail-safe SUSPICIOUS: ${raw.take(120)}" }
      return failSafe()
    }
    return try {
      val tree = jacksonMapper.readTree(raw.substring(start, end + 1))
      val verdictName = tree.path("verdict").asText("").trim().uppercase()
      val verdict = GuardrailsVerdict.Verdict.entries.firstOrNull { it.name == verdictName }
      if (verdict == null) {
        logger.warn { "guardrails unknown verdict '$verdictName', fail-safe SUSPICIOUS" }
        failSafe()
      } else {
        GuardrailsVerdict(
          verdict = verdict,
          reasons = tree.path("reasons").mapNotNull { it.takeIf { it.isTextual }?.asText() },
        )
      }
    } catch (e: Exception) {
      logger.warn(e) { "guardrails answer parse failed, fail-safe SUSPICIOUS" }
      failSafe()
    }
  }

  private fun failSafe() =
    GuardrailsVerdict(GuardrailsVerdict.Verdict.SUSPICIOUS, listOf("GUARDRAILS_UNPARSABLE"))

  companion object {
    private const val PROMPT_RESOURCE = "prompts/guardrails-system.md"
    private const val MAX_GUARDRAILS_CHARS = 12_000
  }
}

/**
 * Guardrails-конвейер: сначала дешёвые детерминированные проверки, затем
 * LLM-модератор (§8: вердикт ≠ PASS → флоу REJECTED, цикл не запускается).
 */
@Singleton
class GuardrailsService(
  private val llmGuardrails: LlmGuardrails,
) {
  suspend fun check(text: String): GuardrailsVerdict =
    DeterministicGuardrails.check(text) ?: llmGuardrails.check(text)
}
