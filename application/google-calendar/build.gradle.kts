plugins {
  id("build-jvm")
  id("idea-custom-plugin")
  id("build-koin")
  alias(libs.plugins.koin.compiler)
}

kotlin {
  jvmToolchain(25)
}

dependencies {
  implementation(kotlin("stdlib"))
  // Стартеры из libs build — GA-координаты без версии (composite substitution)
  implementation("org.jep21s.meetupflowagent.libs:jackson-starter")
  implementation("org.jep21s.meetupflowagent.libs:logging-starter")
  implementation("org.jep21s.meetupflowagent.libs:config-starter")

  implementation(libs.bundles.kotlinx.coroutines)

  // HTTP-вызовы Google API (OAuth2-токен + события Calendar API v3)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)

  // SPI OutboxTransport + канонический payload публикации
  implementation(projects.meetupInfoExtractor)
}

// Доставка анонсов в Google Calendar (outbox-транспорт google_calendar): OAuth2
// сервисного аккаунта (RS256 подпись JWT штатным java.security, без внешних
// зависимостей) + REST Calendar API v3 через ktor-клиент. Ключ — секрет в ENV
// (GOOGLE_CALENDAR_CREDENTIALS_JSON), calendarId назначения — в destinations.config.
