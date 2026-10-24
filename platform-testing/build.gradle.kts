plugins {
    id("nothingeats.kotlin-library")
}

dependencies {
    api(project(":platform-persistence"))
    api(libs.testcontainers.postgresql)
    implementation(libs.postgresql)
}
