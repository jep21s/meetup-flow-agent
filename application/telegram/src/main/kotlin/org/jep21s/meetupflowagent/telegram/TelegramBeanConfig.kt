package org.jep21s.meetupflowagent.telegram

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module

/**
 * Koin-модуль telegram-слоя основного сервиса: решения о маршрутизации входящих
 * апдейтов (TelegramUpdateService), адресация исходящих (TelegramNotifier —
 * биндится как ProxyNotifier extractor'а), доставка публикаций
 * (TelegramOutboxTransport, type = telegram_proxy), HTTP-клиент прокси.
 * Подключается в @KoinApplication в Main.kt.
 */
@Module
@Configuration
@ComponentScan("org.jep21s.meetupflowagent.telegram")
class TelegramBeanConfig
