// A Windows program built from what extended/publish-local-all.sh published and nothing else:
// Compose for mingwX64, skiko for mingwX64 and its C++ half, and the plugin that links them with
// MSVC, all from the local Maven repository (see build.gradle.kts). Not part of the fork's build.
pluginManagement {
    repositories { mavenLocal(); gradlePluginPortal(); mavenCentral() }
    plugins {
        // Published with the fork, at its one version.
        id("org.thisisthepy.kotlin.native.desktop") version (providers.gradleProperty("forkVersion").orNull ?: "1.11.1-ext.2")
    }
}
dependencyResolutionManagement {
    repositories { mavenLocal(); mavenCentral(); google() }
}
rootProject.name = "mingw-program"
