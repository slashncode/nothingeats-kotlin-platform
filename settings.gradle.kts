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

// posthog-ktor comes from Maven Central. To try an unreleased change of it here (and in services
// through the platform), build it from a checkout instead: -PposthogKtorDir=../posthog-ktor
providers.gradleProperty("posthogKtorDir").orNull?.let { includeBuild(it) }

include(
    "platform-bom",
    "version-catalog",
    "platform-core",
    "platform-http",
    "platform-persistence",
    "platform-kafka",
    "platform-valkey",
    "platform-grpc",
    "ktor-posthog",
    "platform-testing",
)
