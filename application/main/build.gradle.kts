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
  // Стартеры из libs build — GA-координаты без версии (composite substitution)
  implementation("org.jep21s.meetupflowagent.libs:jackson-starter")
  implementation("org.jep21s.meetupflowagent.libs:logging-starter")
  implementation("org.jep21s.meetupflowagent.libs:config-starter")
  // Внутренние модули application — через projects.<name> (typesafe accessors)
  implementation(libs.bundles.kotlinx.coroutines)
  implementation(libs.koin.ktor)
  implementation(libs.bundles.ktor.server)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.client.content.negotiation)
  implementation(libs.jsoup)

  // БД: Postgres + pgvector (Exposed только маппит таблицы; DDL — Liquibase, §6)
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
}

tasks.register<Jar>("fatJar") {
  archiveClassifier.set("all")
  from(sourceSets.main.get().output)
  dependsOn(configurations.runtimeClasspath)
  from({ configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) } })
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  manifest { attributes["Main-Class"] = mainFile }
}

// main — только прод-код: тесты живут в application/test (В33)
