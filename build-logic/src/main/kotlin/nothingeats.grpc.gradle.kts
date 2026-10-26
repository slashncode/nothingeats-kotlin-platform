// Code generation for the proto files in src/<sourceSet>/proto: Java messages with the Kotlin DSL,
// grpc-java stubs and grpc-kotlin coroutine stubs. Apply it to a service's `grpc-api` module
// (the producer's contract) or to tests that need a proto of their own.
import com.google.protobuf.gradle.id

plugins {
    `java-library`
    id("com.google.protobuf")
}

val libs = the<VersionCatalogsExtension>().named("libs")

fun version(name: String) = libs.findVersion(name).get().requiredVersion

dependencies {
    "api"(platform(libs.findLibrary("grpc-bom").get()))
    "api"(libs.findLibrary("grpc-protobuf").get())
    "api"(libs.findLibrary("grpc-stub").get())
    "api"(libs.findLibrary("grpc-kotlin-stub").get())
    "api"(libs.findLibrary("protobuf-kotlin").get())
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${version("protobuf")}" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:${version("grpc")}" }
        id("grpckt") { artifact = "io.grpc:protoc-gen-grpc-kotlin:${version("grpc-kotlin")}:jdk8@jar" }
    }
    generateProtoTasks {
        all().configureEach {
            plugins {
                id("grpc")
                id("grpckt")
            }
            builtins { id("kotlin") }
        }
    }
}
