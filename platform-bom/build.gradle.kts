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
    api(platform(libs.grpc.bom))
    // Newer than what Ktor (java-jwt) and logstash-encoder ask for: fixed vulnerabilities.
    api(platform(libs.jackson2.bom))
    api(platform(libs.jackson3.bom))
    api(platform(libs.guava.bom))
    constraints {
        api(project(":platform-core"))
        api(project(":platform-http"))
        api(project(":platform-persistence"))
        api(project(":platform-kafka"))
        api(project(":platform-valkey"))
        api(project(":platform-grpc"))
        api(libs.grpc.kotlin.stub)
        api(libs.protobuf.kotlin)
        api(project(":ktor-posthog"))
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
        api(libs.lz4.java) { because("kafka-clients brings 1.11.2, which has known vulnerabilities") }
        api(libs.snappy.java) { because("kafka-clients brings 1.1.10.7, which has known vulnerabilities") }
        api(libs.lettuce.core)
        api(libs.posthog.server)
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["javaPlatform"])
        }
    }
}
