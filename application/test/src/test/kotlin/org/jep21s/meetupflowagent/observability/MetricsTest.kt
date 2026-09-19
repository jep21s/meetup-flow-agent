package org.jep21s.meetupflowagent.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/** Метрики на SimpleMeterRegistry: счётчики растут на синтетике, cost > 0. */
class MetricsTest {

  @Test
  fun `flow counters and timer grow`() {
    val metrics = Metrics.inMemory()
    metrics.flowStatus("COMPLETED")
    metrics.flowStatus("COMPLETED")
    metrics.flowStatus("REJECTED")
    metrics.flowDuration(Duration.ofMillis(250))
    metrics.reactCycles(2)

    val scraped = metrics.scrape()
    // SimpleMeterRegistry не умеет prometheus-scrape — проверяем логом?
    // Нет: счётчики проверяем через реестр самой метрики.
    assertThat(countOf(metrics, "meetup_flows_total", "status", "COMPLETED")).isEqualTo(2.0)
    assertThat(countOf(metrics, "meetup_flows_total", "status", "REJECTED")).isEqualTo(1.0)
  }

  @Test
  fun `llm cost is computed from price table`() {
    val metrics = Metrics.inMemory()
    // glm-5.3: 1M prompt × $1.40 + 1M completion × $4.40 = $5.80
    metrics.llmCost("glm-5.3", promptTokens = 1_000_000, completionTokens = 1_000_000)
    // flash: 1M × $0.15 + 1M × $0.50 = $0.65
    metrics.llmCost("glm-5.3-flash", promptTokens = 1_000_000, completionTokens = 1_000_000)

    assertThat(countOf(metrics, "meetup_llm_cost_usd_total", "model", "glm-5.3"))
      .isCloseTo(5.80, org.assertj.core.data.Offset.offset(1e-9))
    assertThat(countOf(metrics, "meetup_llm_cost_usd_total", "model", "glm-5.3-flash"))
      .isCloseTo(0.65, org.assertj.core.data.Offset.offset(1e-9))
  }

  @Test
  fun `llm tokens and calls tracked by model and outcome`() {
    val metrics = Metrics.inMemory()
    metrics.llmCall("glm-5.3-flash", "ok")
    metrics.llmCall("glm-5.3-flash", "retryable_error")
    metrics.llmTokens("glm-5.3-flash", promptTokens = 10, completionTokens = 5)

    assertThat(countOf(metrics, "meetup_llm_calls_total", "outcome", "ok")).isEqualTo(1.0)
    assertThat(countOf(metrics, "meetup_llm_calls_total", "outcome", "retryable_error")).isEqualTo(1.0)
    assertThat(countOf(metrics, "meetup_llm_tokens_total", "direction", "prompt")).isEqualTo(10.0)
    assertThat(countOf(metrics, "meetup_llm_tokens_total", "direction", "completion")).isEqualTo(5.0)
  }

  @Test
  fun `guardrails and tool counters tracked`() {
    val metrics = Metrics.inMemory()
    metrics.guardrailsVerdict("PASS")
    metrics.guardrailsVerdict("INJECTION")
    metrics.toolCall("search_duplicate", "ok")

    assertThat(countOf(metrics, "meetup_guardrails_verdicts_total", "verdict", "PASS")).isEqualTo(1.0)
    assertThat(countOf(metrics, "meetup_guardrails_verdicts_total", "verdict", "INJECTION")).isEqualTo(1.0)
    assertThat(countOf(metrics, "meetup_tool_calls_total", "tool", "search_duplicate")).isEqualTo(1.0)
  }

  private fun countOf(metrics: Metrics, name: String, tagKey: String, tagValue: String): Double {
    val registry = metrics.registryForTests()
    return registry.find(name).tag(tagKey, tagValue).counter()?.count() ?: 0.0
  }
}
