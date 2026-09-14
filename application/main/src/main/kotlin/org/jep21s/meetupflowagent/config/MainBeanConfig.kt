package org.jep21s.meetupflowagent.config

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Singleton
import org.jep21s.meetupflowagent.llm.KtorOpenAiLlmClient
import org.jep21s.meetupflowagent.llm.LlmClient
import org.jep21s.meetupflowagent.llm.RetryableLlmClient
import org.jep21s.meetupflowagent.observability.Metrics

private val logger = KotlinLogging.logger { }

@Module
@Configuration
@ComponentScan("org.jep21s.meetupflowagent")
class MainBeanConfig {

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

  /**
   * Единый CoroutineScope приложения: SupervisorJob + логирующий exception handler +
   * graceful shutdown по JVM shutdown hook. Инжектить по @Named("applicationCoroutineScope")
   * во все долгоживущие коллекторы/фоновые циклы.
   */
  @Singleton(createdAtStart = true)
  @Named("applicationCoroutineScope")
  fun applicationCoroutineScope(): CoroutineScope {
    val applicationScope = CoroutineScope(
      SupervisorJob() + Dispatchers.Default +
          CoroutineExceptionHandler { context, throwable ->
            logger.error(throwable) {
              "exception in application coroutine scope. Context: $context"
            }
          })
    Runtime.getRuntime().addShutdownHook(
      Thread {
        applicationScope.cancel(CancellationException("Graceful shutdown application"))
      }
    )
    return applicationScope
  }
}
