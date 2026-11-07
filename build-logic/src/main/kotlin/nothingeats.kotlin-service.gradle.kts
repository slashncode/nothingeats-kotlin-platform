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
        jvmFlags =
            listOf(
                // Heap and everything beside it must fit the container limit together. Under load
                // (k6, 3,000 requests a second) a service's JVM uses about 210 MiB beside the heap:
                // metaspace ~60, code cache ~50, compiler arenas up to 40, symbols, thread stacks,
                // malloc. At 60 % of 512 MiB that was just over the limit and the kernel killed it;
                // at 50 % it peaked at 464 MiB. (At 75 % a burst of Argon2 hashes got identity
                // OOM-killed at 512 MiB, back in phase 1.)
                "-XX:MaxRAMPercentage=50",
                "-XX:MaxDirectMemorySize=64m",
                "-XX:+ExitOnOutOfMemoryError",
                // Netty loads its native transport; JDK 25 warns (and will later refuse) without this.
                "--enable-native-access=ALL-UNNAMED",
            )
        // glibc gives every thread that mallocs its own arena (up to 8 per core: 112 on a
        // 14-core host), memory the JVM's native memory tracking never sees; two are plenty.
        environment = mapOf("MALLOC_ARENA_MAX" to "2")
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
