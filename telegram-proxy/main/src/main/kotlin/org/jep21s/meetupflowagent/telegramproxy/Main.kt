package org.jep21s.meetupflowagent.telegramproxy

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.jep21s.meetupflowagent.telegramproxy.config.TelegramProxyBeanConfig
import org.jep21s.meetupflowagent.telegramproxy.config.restModule
import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin

@KoinApplication(
  modules = [
    TelegramProxyBeanConfig::class,
  ]
)
class Main

fun main() {
  // DI поднимается ПЕРВЫМ: здесь же создаются синглтоны (в т.ч. MessengerBot с
  // long polling при telegram.bot.enabled=true). Бот выключен по умолчанию —
  // тесты и локальный запуск Telegram не касаются (см. §6.1 плана).
  startKoin<Main> {}
  val port = ConfigLoader.getProperty("server.port", "8082").toInt()
  embeddedServer(CIO, port = port, module = { restModule() })
    .start(wait = true)
}
