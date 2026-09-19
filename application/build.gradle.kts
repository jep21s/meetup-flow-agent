
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

// Версия артефакта:
//   ветка ≠ main → <ветка>-<hash8>[-dirty]     (тест-сборка: префикс feature/ срезается,
//     недопустимые символы заменяются на "-"; -dirty = есть незакоммиченные изменения)
//   main + тег на текущем коммите → X.Y.Z      (прод-сборка; теги ставит workflow git-finalize)
//   иначе → 1.0-SNAPSHOT.
// ProcessBuilder вместо project.exec{}: в Gradle 9.x exec-лямбда потеряла receiver.
fun getGitVersion(): String {
  fun git(vararg args: String): String = try {
    ProcessBuilder("git", *args)
      .directory(rootDir)
      .start()
      .inputStream.bufferedReader().readText().trim()
  } catch (e: Exception) {
    ""
  }

  return try {
    val branch = git("branch", "--show-current")
    if (branch.isNotEmpty() && branch != "main") {
      val name = branch.removePrefix("feature/")
        .replace(Regex("[^A-Za-z0-9._-]"), "-")
      val hash = git("rev-parse", "--short=8", "HEAD")
      val dirty = if (git("diff", "--name-only", "HEAD").isEmpty()) "" else "-dirty"
      return "$name-$hash$dirty"
    }

    git("tag", "--points-at", "HEAD")
      .lines()
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .firstOrNull() ?: "1.0-SNAPSHOT"
  } catch (e: Exception) {
    "1.0-SNAPSHOT"
  }
}
