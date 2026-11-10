plugins {
    id("nothingeats.kotlin-library")
}

dependencies {
    api(project(":platform-core"))
    api(project(":platform-http"))
    api(libs.posthog.ktor)
    implementation(libs.ktor.server.call.id)

    testImplementation(project(":platform-testing"))
    testImplementation(libs.posthog.ktor.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.server.netty)
    testImplementation(libs.logback.classic)
}
