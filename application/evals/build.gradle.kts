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
  implementation("org.jep21s.meetupflowagent.libs:config-starter")
  implementation("org.jep21s.meetupflowagent.libs:jackson-starter")
  implementation("org.jep21s.meetupflowagent.libs:logging-starter")

  implementation(libs.bundles.kotlinx.coroutines)
  implementation(libs.bundles.ktor.server)

  testImplementation(libs.bundles.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.turbine)
  testImplementation(libs.mockk)
  testImplementation(libs.test.ktor.server.host)
  testImplementation(libs.test.ktor.client.mock)
  testRuntimeOnly(libs.logback)
}

tasks.test {
  jvmArgs = listOf(
    "--add-opens=java.base/java.time=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.math=ALL-UNNAMED")
  // evals не входят в дефолтную проверку — только через таску eval
  useJUnitPlatform {
    excludeTags("eval")
  }
}

// Оценка качества на golden set с реальной моделью (ключи из .env).
// При пустых ключах тесты @Tag("eval") корректно пропускаются.
tasks.register<Test>("eval") {
  group = "verification"
  description = "Оценка качества агента на golden set с реальной LLM (@Tag(\"eval\"))"
  testClassesDirs = sourceSets["test"].output.classesDirs
  classpath = sourceSets["test"].runtimeClasspath
  useJUnitPlatform {
    includeTags("eval")
  }
  jvmArgs = listOf(
    "--add-opens=java.base/java.time=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.math=ALL-UNNAMED")
}
