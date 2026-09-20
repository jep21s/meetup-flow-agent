plugins {
  id("build-jvm")
  id("idea-custom-plugin")
}

kotlin {
  jvmToolchain(25)
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation(projects.main)
  // логика извлечения вынесена в отдельный модуль (implementation main не транзитивен)
  implementation(projects.meetupInfoExtractor)
  implementation(projects.telegram)
  implementation(projects.googleCalendar)
  implementation("org.jep21s.meetupflowagent.libs:config-starter")
  implementation("org.jep21s.meetupflowagent.libs:jackson-starter")
  implementation("org.jep21s.meetupflowagent.libs:logging-starter")

  implementation(libs.bundles.kotlinx.coroutines)
  implementation(libs.bundles.ktor.server)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)
  implementation(platform(libs.exposed.bom))
  implementation(libs.exposed.core)
  implementation(libs.exposed.jdbc)
  implementation(libs.exposed.java.time)
  implementation(libs.exposed.json)
  implementation(libs.hikaricp)
  implementation(libs.postgresql.driver)
  implementation(libs.micrometer.registry.prometheus)

  testImplementation(libs.bundles.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.mockk)
  testImplementation(libs.wiremock.standalone)
  testRuntimeOnly(libs.logback)
}

tasks.test {
  environment("LLM_API_KEY", System.getenv("LLM_API_KEY") ?: "test-key")
  jvmArgs = listOf(
    "--add-opens=java.base/java.time=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.math=ALL-UNNAMED")
  // e2e не входит в дефолтную проверку — только через таску e2e
  useJUnitPlatform {
    excludeTags("e2e")
  }
}

// Сквозные тесты полного цикла с реальными LLM/эмбеддингами (В37).
// Запуск: source .env → ./gradlew :application:e2e:e2e; без ключей — graceful skip.
tasks.register<Test>("e2e") {
  group = "verification"
  description = "E2E сценарии с реальными LLM/эмбеддингами (@Tag(\"e2e\"))"
  testClassesDirs = sourceSets["test"].output.classesDirs
  classpath = sourceSets["test"].runtimeClasspath
  useJUnitPlatform {
    includeTags("e2e")
  }
  jvmArgs = listOf(
    "--add-opens=java.base/java.time=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.math=ALL-UNNAMED")
}
