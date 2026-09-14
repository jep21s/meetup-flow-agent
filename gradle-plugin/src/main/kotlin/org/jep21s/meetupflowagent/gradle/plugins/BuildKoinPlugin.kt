package org.jep21s.meetupflowagent.gradle.plugins

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.*
import org.koin.gradle.KoinPlugin

@Suppress("unused")
internal class BuildKoinPlugin : KoinPlugin() {
  override fun apply(project: Project) = with(project) {
    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    configureDependencies(libs)
  }

  private fun Project.configureDependencies(libs: VersionCatalog) {
    dependencies {
      add("implementation", platform(libs.findLibrary("koin-bom").get()))
      add("implementation", libs.findLibrary("koin-core").get())
      add("implementation", libs.findLibrary("koin-annotations").get())
      add("implementation", libs.findLibrary("koin-logger").get())
      add("testImplementation", libs.findLibrary("koin-test").get())
      add("testImplementation", libs.findLibrary("koin-test-junit5").get())
    }
  }
}
