pluginManagement {
    includeBuild("../gradle-plugin")
    plugins {
        id("build-jvm") apply false
        id("konvert") apply false
        id("idea-custom-plugin") apply false
    }
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "libs"
include("lib-konvert")
include("jackson-starter")
include("logging-starter")
include("config-starter")
