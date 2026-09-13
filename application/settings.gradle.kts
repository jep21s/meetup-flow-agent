pluginManagement {
    includeBuild("../gradle-plugin")
    plugins {
        id("build-jvm") apply false
        id("idea-custom-plugin") apply false
        id("build-koin") apply false
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

rootProject.name = "application"
include("main")
