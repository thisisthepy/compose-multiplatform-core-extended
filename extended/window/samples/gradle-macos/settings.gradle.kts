pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        // The window modules: `./kotlin publish mavenLocal` in extended/window.
        mavenLocal()
        // Compose and Material 3 for macosArm64, as JetBrains publishes them.
        mavenCentral()
        google()
    }
}
rootProject.name = "gradle-macos"
