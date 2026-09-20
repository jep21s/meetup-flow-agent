plugins {
  id("build-jvm")
  id("idea-custom-plugin")
  id("build-koin")
  alias(libs.plugins.koin.compiler)
  application
}

kotlin {
  jvmToolchain(25)
}

val mainFile = "org.jep21s.meetupflowagent.MainKt"
application { mainClass.set(mainFile) }

dependencies {
  implementation(kotlin("stdlib"))
  // Вся логика извлечения (agent/llm/guardrails/domain/flow/db/scheduler/...) —
  // в отдельном модуле; main — только REST-слой (Main, config, route)
  implementation(projects.meetupInfoExtractor)
  // telegram-слой: решения по апдейтам/адресация (endpoint направляет сюда)
  implementation(projects.telegram)
  // outbox-транспорт google_calendar: анонсы в Google Calendar (сервисный аккаунт)
  implementation(projects.googleCalendar)
  // Стартеры из libs build — GA-координаты без версии (composite substitution)
  implementation("org.jep21s.meetupflowagent.libs:jackson-starter")
  implementation("org.jep21s.meetupflowagent.libs:logging-starter")
  implementation("org.jep21s.meetupflowagent.libs:config-starter")
  implementation(libs.bundles.kotlinx.coroutines)
  implementation(libs.koin.ktor)
  implementation(libs.bundles.ktor.server)
}

tasks.register<Jar>("fatJar") {
  archiveClassifier.set("all")
  from(sourceSets.main.get().output)
  dependsOn(configurations.runtimeClasspath)
  from({ configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) } })
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  manifest { attributes["Main-Class"] = mainFile }
}

// main — только прод-код REST-слоя: тесты живут в application/test (В33)
