plugins {
    id("nothingeats.kotlin-library")
}

dependencies {
    api(project(":platform-core"))
    api(project(":platform-http"))
    implementation(libs.posthog.server)
    implementation(libs.ktor.server.call.id)

    testImplementation(project(":platform-testing"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.server.netty)
}
