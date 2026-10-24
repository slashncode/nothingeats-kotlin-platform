// One BOM aligns all platform modules (single version) and the libraries they expose.
plugins {
    `java-platform`
    id("nothingeats.publishing")
}

javaPlatform {
    allowDependencies()
}

dependencies {
    api(platform(libs.ktor.bom))
    api(platform(libs.kotlinx.coroutines.bom))
    constraints {
        api(project(":platform-core"))
        api(project(":platform-http"))
        api(project(":platform-persistence"))
        api(project(":platform-kafka"))
        api(project(":platform-testing"))
        api(libs.kotlinx.serialization.json)
        api(libs.hoplite.core)
        api(libs.exposed.core)
        api(libs.exposed.jdbc)
        api(libs.exposed.java.time)
        api(libs.hikari)
        api(libs.postgresql)
        api(libs.flyway.core)
        api(libs.flyway.postgresql)
        api(libs.slf4j.api)
        api(libs.logback.classic)
        api(libs.logstash.encoder)
        api(libs.testcontainers.postgresql)
        api(libs.testcontainers.kafka)
        api(libs.kafka.clients)
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["javaPlatform"])
        }
    }
}
