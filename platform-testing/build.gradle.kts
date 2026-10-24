plugins {
    id("nothingeats.kotlin-library")
}

dependencies {
    api(project(":platform-persistence"))
    api(libs.testcontainers.postgresql)
    api(libs.testcontainers.kafka)
    implementation(libs.postgresql)
    implementation(libs.kafka.clients)
}
