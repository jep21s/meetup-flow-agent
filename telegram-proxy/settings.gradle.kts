// Sub-build telegram-proxy (аналог application/): gradle-plugin подключается
// здесь, catalog — общий из корневого репо.
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

// Toolchain JDK 25: у sub-build'а свой pluginManagement-контекст — foojay здесь.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "telegram-proxy"
include("main")
