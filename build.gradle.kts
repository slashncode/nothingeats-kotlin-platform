// Every module shares one version, taken from the version catalog, and resolves against the
// platform's own BOM like every service does (the dependency graph GitHub checks includes them).
allprojects {
    group = "io.github.slashncode.nothingeats"
    version = rootProject.extensions
        .getByType<VersionCatalogsExtension>()
        .named("libs")
        .findVersion("nothingeats-platform")
        .get()
        .requiredVersion
}

subprojects {
    if (name != "platform-bom") {
        pluginManager.withPlugin("java") {
            dependencies { "implementation"(platform(project(":platform-bom"))) }
        }
    }
}
