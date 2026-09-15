
plugins {
  alias(libs.plugins.kotlin.jvm) apply false
}

group = "org.jep21s.meetupflowagent.telegramproxy"
version = getGitVersion()

allprojects {
  repositories {
    mavenCentral()
  }
}

subprojects {
  group = rootProject.group
  version = rootProject.version
}

// Версия артефакта = git-тег текущего коммита; без тегов — 1.0-SNAPSHOT
// (как в application/build.gradle.kts).
fun getGitVersion(): String {
  return try {
    val tags = ProcessBuilder("git", "tag", "--points-at", "HEAD")
      .directory(rootDir)
      .start()
      .inputStream.bufferedReader().readText()
      .lines()
      .map { it.trim() }
      .filter { it.isNotEmpty() }

    tags.firstOrNull() ?: "1.0-SNAPSHOT"
  } catch (e: Exception) {
    "1.0-SNAPSHOT"
  }
}
