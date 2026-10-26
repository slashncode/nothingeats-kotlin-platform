plugins {
    id("nothingeats.kotlin-library")
    // For the test service's proto; services use the same plugin in their grpc-api module.
    id("nothingeats.grpc")
}

dependencies {
    api(project(":platform-core"))
    // JwtConfig, AuthenticatedUser and token verification are shared with HTTP.
    api(project(":platform-http"))
    api(libs.grpc.services)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":platform-testing"))
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.kotlinx.coroutines.test)
}
