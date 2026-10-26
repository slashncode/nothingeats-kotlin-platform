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
