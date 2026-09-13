// Корневой оркестратор composite builds.
// ВАЖНО: gradle-plugin здесь НЕ подключается — каждый sub-build подключает его сам
// через pluginManagement { includeBuild("../gradle-plugin") } в своём settings.gradle.kts.
pluginManagement {
  val kotlinVersion: String by settings
  plugins {
    kotlin("jvm") version kotlinVersion
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "meetup-flow-agent"

includeBuild("application")
includeBuild("libs")
