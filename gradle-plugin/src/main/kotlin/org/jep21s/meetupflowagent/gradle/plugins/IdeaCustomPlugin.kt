package org.jep21s.meetupflowagent.gradle.plugins

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.plugins.ide.idea.model.IdeaModel

@Suppress("unused")
internal class IdeaCustomPlugin : Plugin<Project> {
  override fun apply(project: Project) = with(project) {
    pluginManager.apply("idea")
    pluginManager.withPlugin("idea") {
      extensions.configure<IdeaModel>() {
        module {
          isDownloadJavadoc = true
          isDownloadSources = true
        }
      }
    }
  }
}
