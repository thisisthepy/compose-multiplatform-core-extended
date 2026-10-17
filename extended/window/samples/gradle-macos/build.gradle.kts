// A Kotlin/Native macOS application built by Gradle that hosts Compose content in the fork's
// AppKit and Metal window, org.thisisthepy.compose.window:native-macos. The window modules are
// built with Amper; a consumer needs only their Maven coordinates.
//
//   ./gradlew runDebugExecutableMacosArm64
//
// Kotlin has to be at least the version the window klibs were compiled with (their .module
// file names it under kotlin-stdlib): a klib from a newer compiler does not link.
plugins {
    kotlin("multiplatform") version "2.4.10"
    kotlin("plugin.compose") version "2.4.10"
}

val compose = "1.11.1"
// Material 3 has its own version line. JetBrains publishes material3-macosarm64 on Maven
// Central; the fork does not build it for macOS.
val material3 = "1.11.0-alpha07"
val window = "0.1.0"

kotlin {
    macosArm64 {
        binaries.executable {
            entryPoint = "hello.main"
        }
    }
    sourceSets.macosMain.dependencies {
        implementation("org.thisisthepy.compose.window:common:$window")
        implementation("org.thisisthepy.compose.window:native-macos:$window")
        implementation("org.jetbrains.compose.runtime:runtime:$compose")
        implementation("org.jetbrains.compose.foundation:foundation:$compose")
        implementation("org.jetbrains.compose.ui:ui:$compose")
        implementation("org.jetbrains.compose.material3:material3:$material3")
    }
}
