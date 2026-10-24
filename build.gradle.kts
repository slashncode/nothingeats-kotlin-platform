// Every module shares one version, taken from the version catalog.
allprojects {
    group = "io.github.slashncode.nothingeats"
    version = rootProject.extensions
        .getByType<VersionCatalogsExtension>()
        .named("libs")
        .findVersion("nothingeats-platform")
        .get()
        .requiredVersion
}
