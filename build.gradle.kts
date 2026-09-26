plugins {
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
}

qupathExtension {
    name = "qupath-extension-claude"
    group = "io.github.qupath"
    version = "0.1.4"
    description = "Claude Code integration for QuPath"
    automaticModule = "io.github.qupath.extension.claude"
}

dependencies {
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)
}
