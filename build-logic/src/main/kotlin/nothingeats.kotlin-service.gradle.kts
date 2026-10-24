// The runnable module of a service (`:app`): composition root with kotlin-inject,
// JSON logging at runtime, and a multi-arch distroless image built by Jib.
plugins {
    id("nothingeats.kotlin-base")
    id("com.google.devtools.ksp")
    id("com.google.cloud.tools.jib")
    application
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"(libs.findLibrary("kotlin-inject-runtime").get())
    "ksp"(libs.findLibrary("kotlin-inject-compiler").get())
    "runtimeOnly"(libs.findLibrary("logback-classic").get())
    "runtimeOnly"(libs.findLibrary("logstash-encoder").get())
}

jib {
    from {
        image = "gcr.io/distroless/java25-debian13:nonroot"
        platforms {
            platform {
                architecture = "amd64"
                os = "linux"
            }
            platform {
                architecture = "arm64"
                os = "linux"
            }
        }
    }
    to {
        image = "ghcr.io/slashncode/${rootProject.name}"
    }
    container {
        jvmFlags = listOf("-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError")
        ports = listOf("8080")
        creationTime = "USE_CURRENT_TIMESTAMP"
    }
}

// Readable logs when running locally and in tests; the image keeps the JSON logback.xml.
tasks.named<JavaExec>("run") {
    systemProperty("logback.configurationFile", "logback-plain.xml")
}
tasks.withType<Test>().configureEach {
    systemProperty("logback.configurationFile", "logback-plain.xml")
}

// Jib reads Task.project at execution time; only its tasks opt out of the configuration cache.
tasks.matching { it.name.startsWith("jib") }.configureEach {
    notCompatibleWithConfigurationCache("Jib uses Task.project at execution time")
}
