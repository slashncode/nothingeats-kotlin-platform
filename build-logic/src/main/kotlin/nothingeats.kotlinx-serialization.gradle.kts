// kotlinx.serialization compiler plugin plus the JSON runtime.
plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"(libs.findLibrary("kotlinx-serialization-json").get())
}
