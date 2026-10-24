plugins {
    id("nothingeats.kotlin-library")
}

dependencies {
    api(project(":platform-core"))
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)

    testImplementation(project(":platform-testing"))
}
