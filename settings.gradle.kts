pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "nothingeats-kotlin-platform"

include(
    "platform-bom",
    "version-catalog",
    "platform-core",
    "platform-http",
    "platform-persistence",
    "platform-kafka",
    "platform-testing",
)
