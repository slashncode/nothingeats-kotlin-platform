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

// posthog-ktor (ktor-posthog's engine) from its sibling checkout until it is on Maven Central; CI
// checks it out next to the platform. Services that include the platform get it through here.
val posthogKtorDir = file("../posthog-ktor").canonicalFile
check(posthogKtorDir.exists()) { "posthog-ktor must be checked out next to the platform: ${posthogKtorDir.path}" }
includeBuild(posthogKtorDir)

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
