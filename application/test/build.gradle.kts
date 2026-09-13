plugins {
  id("build-jvm")
  id("idea-custom-plugin")
}

kotlin {
  jvmToolchain(25)
}

dependencies {
  implementation(kotlin("stdlib"))
  // Тестируем прод-код через public API (internal из main здесь не виден)
  implementation(projects.main)
  // Стартеры нужны явно: implementation-депы main не транзитивны
  implementation("org.jep21s.meetupflowagent.libs:config-starter")
  implementation("org.jep21s.meetupflowagent.libs:jackson-starter")
  implementation("org.jep21s.meetupflowagent.libs:logging-starter")

  implementation(libs.bundles.kotlinx.coroutines)
  implementation(libs.bundles.ktor.server)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.content.negotiation)
  implementation(platform(libs.koin.bom))
  implementation(libs.koin.core)

  // Testsupport (src/main этого модуля) поднимает Testcontainers-базу с Exposed+Hikari
  implementation(platform(libs.exposed.bom))
  implementation(libs.exposed.core)
  implementation(libs.exposed.jdbc)
  implementation(libs.exposed.java.time)
  implementation(libs.exposed.json)
  implementation(libs.hikaricp)
  implementation(libs.postgresql.driver)
  implementation(libs.liquibase.core)
  implementation(libs.picocli)
  implementation(libs.junit.kotlin)
  implementation(libs.mockk)
  implementation(platform(libs.testcontainers.bom))
  implementation(libs.testcontainers.postgresql)

  testImplementation(libs.bundles.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.turbine)
  testImplementation(libs.mockk)
  testImplementation(libs.test.ktor.server.host)
  testImplementation(libs.test.ktor.client.mock)
  testImplementation(libs.wiremock.standalone)
  testImplementation(platform(libs.testcontainers.bom))
  testImplementation(libs.testcontainers.postgresql)
  testImplementation(libs.json.schema.validator)
  // Логи в тестах
  testRuntimeOnly(libs.logback)
}

tasks.test {
  // Клиенты требуют непустые ключи даже с MockEngine
  environment("LLM_API_KEY", "test-key")
  environment("EMBEDDING_API_KEY", "test-embedding-key")
  environment("EMBEDDING_FOLDER_ID", "test-folder")
  jvmArgs = listOf(
    "--add-opens=java.base/java.time=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.math=ALL-UNNAMED")
  useJUnitPlatform()
}
