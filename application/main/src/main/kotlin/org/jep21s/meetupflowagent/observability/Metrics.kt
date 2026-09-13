package org.jep21s.meetupflowagent.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration

/**
 * Единая точка метрик Micrometer (§14). Успех/время/стоимость (ДЗ6):
 * `meetup_flows_total{status}`, `meetup_flow_duration_seconds`,
 * `meetup_llm_cost_usd_total{model}` + заделы по вызовам/токенам/латентности,
 * тулам, циклам и guardrails-вердиктам.
 */
class Metrics(private val registry: MeterRegistry) {

  private val flowDuration: Timer = Timer.builder("meetup_flow_duration_seconds")
    .description("Полное время флоу от создания до финального статуса")
    .register(registry)

  fun flowStatus(status: String) {
    registry.counter("meetup_flows_total", "status", status).increment()
  }

  fun flowDuration(duration: Duration) {
    flowDuration.record(duration)
  }

  fun llmCall(model: String, outcome: String) {
    registry.counter("meetup_llm_calls_total", "model", model, "outcome", outcome).increment()
  }

  fun llmTokens(model: String, promptTokens: Long, completionTokens: Long) {
    registry.counter("meetup_llm_tokens_total", "model", model, "direction", "prompt").increment(promptTokens.toDouble())
    registry.counter("meetup_llm_tokens_total", "model", model, "direction", "completion").increment(completionTokens.toDouble())
  }

  fun llmLatency(model: String, duration: Duration) {
    Timer.builder("meetup_llm_latency_seconds")
      .tag("model", model)
      .register(registry)
      .record(duration)
  }

  /** Стоимость вызова по прайсу §10 (USD за 1M токенов). */
  fun llmCost(model: String, promptTokens: Long, completionTokens: Long) {
    val (priceIn, priceOut) = prices(model)
    val usd = promptTokens / 1_000_000.0 * priceIn + completionTokens / 1_000_000.0 * priceOut
    registry.counter("meetup_llm_cost_usd_total", "model", model).increment(usd)
  }

  fun toolCall(tool: String, outcome: String) {
    registry.counter("meetup_tool_calls_total", "tool", tool, "outcome", outcome).increment()
  }

  fun reactCycles(iterations: Int) {
    io.micrometer.core.instrument.DistributionSummary
      .builder("meetup_react_cycles")
      .register(registry)
      .record(iterations.toDouble())
  }

  fun guardrailsVerdict(verdict: String) {
    registry.counter("meetup_guardrails_verdicts_total", "verdict", verdict).increment()
  }

  fun scrape(): String =
    (registry as? PrometheusMeterRegistry)?.scrape()
      ?: "prometheus registry not configured (tests use SimpleMeterRegistry)"

  /** Доступ к реестру для тестов (поиск счётчиков по тегам). */
  fun registryForTests(): MeterRegistry = registry

  private fun prices(model: String): Pair<Double, Double> = when {
    model.contains("flash") -> 0.15 to 0.50
    else -> 1.40 to 4.40 // glm-5.3
  }

  companion object {
    fun prometheus(): Metrics = Metrics(PrometheusMeterRegistry(io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT))

    fun inMemory(): Metrics = Metrics(SimpleMeterRegistry())
  }
}
