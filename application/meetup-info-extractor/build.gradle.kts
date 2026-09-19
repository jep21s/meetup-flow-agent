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

  // HTTP-клиенты LLM / эмбеддингов / уведомлений прокси
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.client.content.negotiation)
  implementation(libs.ktor.serialization.jackson)
  implementation(libs.jsoup)

  // БД: Postgres + pgvector (Exposed только маппит таблицы; DDL — Liquibase)
  implementation(platform(libs.exposed.bom))
  implementation(libs.exposed.core)
  implementation(libs.exposed.jdbc)
  implementation(libs.exposed.java.time)
  implementation(libs.exposed.json)
  implementation(libs.hikaricp)
  implementation(libs.liquibase.core)
  // liquibase-core помечает picocli как optional, но рантайм Liquibase 5 требует его (CommandScope)
  implementation(libs.picocli)
  implementation(libs.postgresql.driver)

  implementation(libs.micrometer.registry.prometheus)
  implementation(platform(libs.opentelemetry.bom))
  implementation(libs.opentelemetry.api)
  implementation(libs.opentelemetry.sdk)
  implementation(libs.opentelemetry.exporter.otlp)
}

// Вся логика извлечения митапов; REST-слой — в application/main (projects.main)
