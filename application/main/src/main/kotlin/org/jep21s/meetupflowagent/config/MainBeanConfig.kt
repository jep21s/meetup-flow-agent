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

private val logger = KotlinLogging.logger { }

/**
 * App-уровень REST-слоя (application/main). Логика извлечения — в
 * [org.jep21s.meetupflowagent.extractor.ExtractorBeanConfig] модуля
 * meetup-info-extractor (подключается в Main.kt).
 */
@Module
@Configuration
@ComponentScan("org.jep21s.meetupflowagent")
class MainBeanConfig {

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
