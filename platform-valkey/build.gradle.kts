plugins {
    id("nothingeats.kotlin-library")
}

dependencies {
    api(project(":platform-core"))
    api(libs.lettuce.core)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":platform-testing"))
    testImplementation(libs.kotlinx.coroutines.test)
}
