package org.jep21s.meetupflowagent.telegramproxy.config

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.jep21s.meetupflowagent.telegramproxy.telegram.DummyTgMessageSender
import org.jep21s.meetupflowagent.telegramproxy.telegram.MessengerBot
import org.jep21s.meetupflowagent.telegramproxy.telegram.TgMessageSender
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Singleton

private val logger = KotlinLogging.logger { }

@Module
@Configuration
@ComponentScan("org.jep21s.meetupflowagent.telegramproxy")
class TelegramProxyBeanConfig {

  /**
   * Kill-switch бота (§6.1 плана): дефолт false — в тестах и при локальном
   * запуске бот не создаётся вообще (никаких вызовов Bot API / long polling),
   * отправка идёт через [DummyTgMessageSender]. На Railway задать
   * TELEGRAM_BOT_ENABLED=true.
   */
  @Singleton(binds = [TgMessageSender::class])
  fun tgMessageSender(): TgMessageSender {
    val enabled = ConfigLoader.getProperty("telegram.bot.enabled", "false").toBoolean()
    if (!enabled) {
      logger.warn { "telegram bot disabled (telegram.bot.enabled=false): long polling NOT started, sends are logged only" }
      return DummyTgMessageSender()
    }
    val token = ConfigLoader.getRequiredProperty(
      "telegram.bot.token",
      "TELEGRAM_BOT_ENABLED=true, но TELEGRAM_BOT_TOKEN не задан (telegram.bot.token)",
    )
    val username = ConfigLoader.getProperty("telegram.bot.username")
    logger.info { "telegram bot enabled: starting long polling (username=$username)" }
    return MessengerBot(token, username)
  }

  /**
   * Единый CoroutineScope приложения: SupervisorJob + логирующий exception handler +
   * graceful shutdown по JVM shutdown hook (как MainBeanConfig в application/main).
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
