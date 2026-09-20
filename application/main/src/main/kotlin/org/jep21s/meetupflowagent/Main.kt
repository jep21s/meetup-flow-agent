package org.jep21s.meetupflowagent

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import org.jep21s.meetupflowagent.config.MainBeanConfig
import org.jep21s.meetupflowagent.config.restModule
import org.jep21s.meetupflowagent.extractor.ExtractorBeanConfig
import org.jep21s.meetupflowagent.googlecalendar.GoogleCalendarBeanConfig
import org.jep21s.meetupflowagent.telegram.TelegramBeanConfig
import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin

@KoinApplication(
  modules = [
    MainBeanConfig::class,
    ExtractorBeanConfig::class,
    TelegramBeanConfig::class,
    GoogleCalendarBeanConfig::class,
  ]
)
class Main

fun main() {
  // DI поднимается ПЕРВЫМ: роуты используют inject(), Koin должен быть готов до старта Ktor
  startKoin<Main> {}
  embeddedServer(CIO, port = 8090, module = { rootModule() })
    .start(wait = true)
}

suspend fun Application.rootModule() {
  restModule()
}
