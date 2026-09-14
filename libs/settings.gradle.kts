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

// Toolchain JDK 25: foojay auto-provisioning (см. комментарий в application/settings.gradle.kts).
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "libs"
include("lib-konvert")
include("jackson-starter")
include("logging-starter")
include("config-starter")
