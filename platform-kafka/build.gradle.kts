plugins {
    id("nothingeats.kotlin-library")
    id("nothingeats.kotlinx-serialization")
}

dependencies {
    api(project(":platform-core"))
    api(project(":platform-persistence"))
    api(libs.kafka.clients)
    api(libs.kotlinx.serialization.json)
    implementation(libs.exposed.java.time)

    testImplementation(project(":platform-testing"))
    testImplementation(libs.kotlinx.coroutines.test)
}
