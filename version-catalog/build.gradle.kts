// Publishes gradle/libs.versions.toml so services share exactly the same versions.
plugins {
    `version-catalog`
    id("nothingeats.publishing")
}

catalog {
    versionCatalog {
        from(files("../gradle/libs.versions.toml"))
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["versionCatalog"])
        }
    }
}
