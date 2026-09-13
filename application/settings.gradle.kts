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

// Toolchain JDK 25: локально JDK нет — foojay скачивает (в корневом settings это
// не работает для sub-build'ов: у каждого свой pluginManagement-контекст).
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "application"
include("main")
include("test")
include("evals")
