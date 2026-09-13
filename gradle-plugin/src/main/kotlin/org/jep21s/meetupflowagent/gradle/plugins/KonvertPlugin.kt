package org.jep21s.meetupflowagent.gradle.plugins

import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.logging.Logging
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

/**
 * Konvert (KSP codegen) для маппинга DTO ↔ модели.
 *
 * Требование к модулю: применить `id("com.google.devtools.ksp") version "2.3.2"` РЯДОМ с этим
 * плагином — KSP применяется потребителем, а не здесь (иначе конфликт порядка применения).
 *
 * Пост-обработка: после каждой KotlinCompile заменяет в сгенерированных Konvert файлах
 * non-null-assertions на requireNotNull() с именем поля в сообщении об ошибке.
 */
@Suppress("unused")
class KonvertPlugin : Plugin<Project> {
  private val logger = Logging.getLogger(this::class.java)

  override fun apply(project: Project) {
    configureDependencies(project)

    project.afterEvaluate {
      tasks.withType(KotlinCompile::class.java)
        .configureEach {
          doLast { processKonvertFiles(project) }
        }
    }
  }

  private fun configureDependencies(project: Project) {
    val libs: VersionCatalog = project.extensions.getByType<VersionCatalogsExtension>().named("libs")
    project.dependencies {
      add("implementation", libs.findLibrary("konvert-api").get())
      add("ksp", libs.findLibrary("konvert-lib").get())
      add("implementation", "org.jep21s.meetupflowagent.libs:lib-konvert")
    }

    logger.lifecycle("Konvert plugin: added konvert dependencies")
  }

  private fun processKonvertFiles(project: Project) = with(project) {
    val generatedDir = file("build/generated/ksp")
    if (!generatedDir.exists()) return

    val customImport = "import org.jep21s.meetupflowagent.lib.konvert.requireNotNull"

    generatedDir.walk()
      .filter { it.isFile && it.extension == "kt" }
      .forEach { file: File ->
        var content = file.readText()

        // Пропускаем файлы без !!
        if (!content.contains("!!")) return@forEach

        // Добавляем импорт если нужно
        if (!content.contains(customImport)) {
          content = addImportSafely(content, customImport)
        }

        // Заменяем !! на requireNotNull
        val pattern = """([\w\[\]().]+?(?:\.\w+)*)!!""".toRegex()
        content = pattern.replace(content) { match ->
          val expr = match.groupValues[1]
          "$expr.requireNotNull(fieldName = \"$expr\")"
        }

        // Заменяем окончания функций на requireNotNull
        val patternFunction = """}!!""".toRegex()
        content = patternFunction.replace(content) { _ ->
          "}.requireNotNull()"
        }

        file.writeText(content)
      }
  }

  fun addImportSafely(content: String, importToAdd: String): String {
    val lines = content.lines().toMutableList()

    // Ищем существующие импорты
    val importLines = lines.filter { it.startsWith("import ") }

    // Если такой импорт уже есть - удаляем
    val existingSimilarImport = importLines.find {
      it == importToAdd
    }

    if (existingSimilarImport != null) {
      lines.remove(existingSimilarImport)
    }

    // Добавляем новый импорт в правильное место
    val lastImportIndex = lines.indexOfLast { it.startsWith("import ") }
    if (lastImportIndex != -1) {
      lines.add(lastImportIndex + 1, importToAdd)
    } else {
      // Ищем package declaration
      val packageIndex = lines.indexOfFirst { it.startsWith("package ") }
      if (packageIndex != -1) {
        lines.add(packageIndex + 1, importToAdd)
      } else {
        lines.add(0, importToAdd)
      }
    }

    return lines.joinToString("\n")
  }
}
