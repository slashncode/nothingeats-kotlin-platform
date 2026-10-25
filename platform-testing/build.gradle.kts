plugins {
    id("nothingeats.kotlin-library")
}

dependencies {
    api(project(":platform-persistence"))
    api(project(":platform-http"))
    api(project(":platform-valkey"))
    api(libs.testcontainers.postgresql)
    api(libs.testcontainers.kafka)
    implementation(libs.postgresql)
    implementation(libs.kafka.clients)
}
