plugins {
    `kotlin-dsl`
    `maven-publish`
}

group = "io.github.slashncode.nothingeats"
version = libs.versions.nothingeats.platform.get()

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
    implementation(libs.kover.gradle.plugin)
    implementation(libs.ktlint.gradle.plugin)
    implementation(libs.jib.gradle.plugin)
    implementation(libs.protobuf.gradle.plugin)

    // What the plugins bring along, raised to versions without known vulnerabilities. It only runs
    // at build time, but it is part of the dependency graph CI submits to GitHub.
    implementation(platform(libs.jackson2.bom))
    constraints {
        implementation(libs.freemarker) { because("Kover's HTML reports bring 2.3.32 (GHSA-27j2-h3m2-8237)") }
        implementation(libs.commons.lang3) { because("Jib brings 3.14.0") }
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/slashncode/nothingeats-kotlin-platform")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
