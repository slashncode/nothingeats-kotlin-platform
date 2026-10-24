plugins {
    id("nothingeats.kotlin-library")
}

dependencies {
    api(platform(libs.kotlinx.coroutines.bom))
    api(libs.kotlinx.coroutines.core)
    api(libs.hoplite.core)
    api(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
}
