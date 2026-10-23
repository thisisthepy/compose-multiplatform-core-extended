// A consumer of what extended/publish-local-all.sh published, resolved the way a Gradle build
// resolves it (see build.gradle.kts). Not part of the fork's build.
pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { mavenLocal(); mavenCentral(); google() }
}
rootProject.name = "consumer-check"
