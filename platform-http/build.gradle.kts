plugins {
    id("nothingeats.kotlin-library")
    id("nothingeats.kotlinx-serialization")
}

dependencies {
    api(project(":platform-core"))
    api(platform(libs.ktor.bom))
    api(libs.ktor.server.core)
    api(libs.kotlinx.serialization.json)
    api(libs.ktor.server.auth)
    api(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.call.logging)

    testImplementation(project(":platform-testing"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
}
