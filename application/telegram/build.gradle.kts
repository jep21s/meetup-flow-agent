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

  // Парсинг DTO Update из telegram-proxy
  implementation(libs.telegrambots)

  // HTTP-вызовы прокси (отправка в Telegram) — отправка/чтение состояния
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.client.content.negotiation)

  // Репозитории/флоу/уведомления логики извлечения
  implementation(projects.meetupInfoExtractor)

  // Exposed для собственного репозитория telegram_questions (implementation не транзитивен)
  implementation(platform(libs.exposed.bom))
  implementation(libs.exposed.core)
  implementation(libs.exposed.jdbc)
  implementation(libs.exposed.java.time)
  implementation(libs.exposed.json)
}

// Telegram-слой основного сервиса: решения о маршрутизации апдейтов и адресации
// исходящих. Сам telegram-proxy (Railway) — тупая труба без логики.
