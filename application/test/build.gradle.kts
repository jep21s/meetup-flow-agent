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
  // KtorOpenAiLlmClient требует непустой llm.apiKey даже с MockEngine
  environment("LLM_API_KEY", "test-key")
  jvmArgs = listOf(
    "--add-opens=java.base/java.time=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.math=ALL-UNNAMED")
  useJUnitPlatform()
}
