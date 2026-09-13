import java.io.ByteArrayOutputStream

plugins {
  alias(libs.plugins.kotlin.jvm) apply false
}

group = "org.jep21s.meetupflowagent"
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

// Версия артефакта = git-тег текущего коммита; без тегов — 1.0-SNAPSHOT.
fun getGitVersion(): String {
  return try {
    val tagOutput = ByteArrayOutputStream()
    exec {
      commandLine("git", "tag", "--points-at", "HEAD")
      workingDir = rootDir
      standardOutput = tagOutput
      errorOutput = ByteArrayOutputStream()
      isIgnoreExitValue = true
    }

    val tags = tagOutput.toString()
      .lines()
      .map { it.trim() }
      .filter { it.isNotEmpty() }

    if (tags.isEmpty()) {
      return "1.0-SNAPSHOT"
    }

    tags.first()
  } catch (e: Exception) {
    "1.0-SNAPSHOT"
  }
}
