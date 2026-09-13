plugins {
  id("org.gradle.kotlin.kotlin-dsl") version "6.7.11"
}

gradlePlugin {
  plugins {
    register("build-jvm") {
      id = "build-jvm"
      implementationClass = "org.jep21s.meetupflowagent.gradle.plugins.BuildPluginJvm"
    }
    register("idea-custom-plugin") {
      id = "idea-custom-plugin"
      implementationClass = "org.jep21s.meetupflowagent.gradle.plugins.IdeaCustomPlugin"
    }
    register("build-docker") {
      id = "build-docker"
      implementationClass = "org.jep21s.meetupflowagent.gradle.plugins.DockerPlugin"
    }
    register("build-koin") {
      id = "build-koin"
      implementationClass = "org.jep21s.meetupflowagent.gradle.plugins.BuildKoinPlugin"
    }
    register("konvert") {
      id = "konvert"
      implementationClass = "org.jep21s.meetupflowagent.gradle.plugins.KonvertPlugin"
    }
  }
}

repositories {
  mavenCentral()
  gradlePluginPortal()
}

dependencies {
  implementation(libs.plugin.kotlin)
  implementation(libs.koin.gradle.plugin)
}
