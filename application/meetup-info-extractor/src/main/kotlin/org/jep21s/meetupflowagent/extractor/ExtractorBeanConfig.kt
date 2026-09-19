package org.jep21s.meetupflowagent.extractor

import org.jep21s.meetupflowagent.llm.KtorOpenAiLlmClient
import org.jep21s.meetupflowagent.llm.LlmClient
import org.jep21s.meetupflowagent.llm.RetryableLlmClient
import org.jep21s.meetupflowagent.observability.Metrics
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton

/**
 * Koin-модуль всей логики извлечения (agent/llm/guardrails/domain/flow/db/
 * scheduler/notify/outbox/resilience/observability — пакет
 * `org.jep21s.meetupflowagent` этого Gradle-модуля). REST-слой (application/main)
 * подключает его в `@KoinApplication` вместе со своим MainBeanConfig.
 */
@Module
@Configuration
@ComponentScan("org.jep21s.meetupflowagent")
class ExtractorBeanConfig {

  /** Prometheus-реестр метрик (в тестах — inMemory с SimpleMeterRegistry). */
  @Singleton
  fun metrics(): Metrics = Metrics.prometheus()

  /**
   * Retry-декоратор над конкретным HTTP-клиентом, биндится как LlmClient:
   * агент и guardrails получают retry + метрики вызовов (цикла зависимостей нет —
   * декоратор зависит от конкретного [KtorOpenAiLlmClient], а не от интерфейса).
   */
  @Singleton(binds = [LlmClient::class])
  fun llmClient(ktor: KtorOpenAiLlmClient, metrics: Metrics): LlmClient =
    RetryableLlmClient(ktor, metrics)
}
