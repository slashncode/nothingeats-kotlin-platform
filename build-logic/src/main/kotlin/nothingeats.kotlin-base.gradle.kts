// Shared setup for every Kotlin module in NothingEats: toolchain, compiler flags,
// lint, coverage and the Kotest/MockK test stack.
plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlinx.kover")
    id("org.jlleitschuh.gradle.ktlint")
}

val libs = the<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(libs.findVersion("java").get().requiredVersion.toInt())
    compilerOptions {
        allWarningsAsErrors = true
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

ktlint {
    // Generated code (KSP, protobuf) is not ours to format.
    filter {
        exclude { it.file.path.contains("${File.separator}build${File.separator}generated${File.separator}") }
    }
}

// ktlint itself logs with logback 1.3, which has known vulnerabilities: use the services' version.
dependencies {
    constraints {
        add("ktlint", libs.findLibrary("logback-classic").get())
        add("ktlint", libs.findLibrary("logback-core").get())
    }
}

dependencies {
    "testImplementation"(libs.findLibrary("kotest-runner-junit5").get())
    "testImplementation"(libs.findLibrary("kotest-assertions-core").get())
    "testImplementation"(libs.findLibrary("mockk").get())
    "testRuntimeOnly"(libs.findLibrary("logback-classic").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
