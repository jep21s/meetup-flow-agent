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

  testImplementation(libs.bundles.junit)
  testImplementation(libs.mockk)
  testImplementation(libs.test.ktor.server.host)
}

tasks.register<Jar>("fatJar") {
  archiveClassifier.set("all")
  from(sourceSets.main.get().output)
  dependsOn(configurations.runtimeClasspath)
  from({ configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) } })
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  manifest { attributes["Main-Class"] = mainFile }
}

tasks.test {
  jvmArgs = listOf(
    "--add-opens=java.base/java.time=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.math=ALL-UNNAMED")
  useJUnitPlatform()
}
