// A published Kotlin library (platform modules, contract artifacts).
plugins {
    id("nothingeats.kotlin-base")
    id("nothingeats.publishing")
    `java-library`
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
