package org.jep21s.meetupflowagent.googlecalendar

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module

/**
 * Koin-модуль google-calendar-слоя основного сервиса: доставка публикаций
 * в Google Calendar (GoogleCalendarOutboxTransport, type = "google_calendar")
 * и HTTP-клиент Calendar API v3 (GoogleCalendarClient: OAuth2 сервисного
 * аккаунта). Подключается в @KoinApplication в Main.kt. Интеграция включается
 * строкой в справочнике destinations (is_active) — до появления ключа
 * сеется выключенной и ничего не доставляет.
 */
@Module
@Configuration
@ComponentScan("org.jep21s.meetupflowagent.googlecalendar")
class GoogleCalendarBeanConfig
