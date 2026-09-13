package org.jep21s.meetupflowagent.gradle.plugins

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.provider.Property
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.StopExecutionException

abstract class DockerBuildTask : DefaultTask() {

  init {
    super.setGroup("docker")
  }

  @get:Input
  abstract val dockerFile: Property<String>

  @get:Input
  abstract val imageName: Property<String>

  @get:Input
  abstract val imageTag: Property<String>

  @get:Input
  abstract val buildContext: Property<String>

  @get:Input
  @get:Optional
  abstract val buildArgs: MapProperty<String, String>

  @get:Input
  @get:Optional
  abstract val noCache: Property<Boolean>

  @get:Input
  @get:Optional
  abstract val removeIntermediateContainers: Property<Boolean>

  @TaskAction
  fun build() {
    val dockerfilePath = "${buildContext.get()}/${dockerFile.get()}"
    val fullImageName = "${imageName.get()}:${imageTag.get()}"

    logger.lifecycle("Building Docker image: $fullImageName")

    val command = mutableListOf<String>("docker", "build")
    command.add("-t")
    command.add(fullImageName)
    command.add("-f")
    command.add(dockerfilePath)

    if (noCache.get()) {
      command.add("--no-cache")
    }

    if (removeIntermediateContainers.get()) {
      command.add("--rm")
    }

    buildArgs.get().forEach { (key, value) ->
      command.add("--build-arg")
      command.add("$key=$value")
    }

    command.add(buildContext.get())

    // ProcessBuilder вместо project.exec{}: Project.exec удалён в Gradle 9.
    val process = ProcessBuilder(command)
      .directory(project.rootDir)
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    if (exitCode != 0) {
      logger.error("Docker build failed:\n$output")
      throw StopExecutionException("Docker build failed with exit code $exitCode")
    }

    logger.lifecycle("Docker image built successfully: $fullImageName")
    logger.quiet(output)
  }
}
