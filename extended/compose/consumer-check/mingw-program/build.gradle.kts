// Links and runs a small mingwX64 program that draws with Compose's graphics into a Skia bitmap
// and shapes Korean through Compose's text (see Main.kt), the way a consumer's Gradle build
// would: the fork's Compose and skiko for mingwX64 come from Maven, and
// org.thisisthepy.kotlin.native.desktop links the executable with MSVC, adding skiko's C++ half,
// Skia and the ICU data from skiko-mingwx64's msvc zip. Nothing is taken from a checkout, and
// the program is one file.
//
// Usage (Windows, with MSVC installed):
//   gradle -p extended/compose/consumer-check/mingw-program linkReleaseExecutableMingwX64 \
//       [-PforkVersion=1.11.1-ext.1]
//   build/bin/mingwX64/releaseExecutable/mingw-program.exe
val forkVersion = providers.gradleProperty("forkVersion").orNull ?: "1.11.1-ext.1"

plugins {
    kotlin("multiplatform") version "2.3.20"
    id("org.thisisthepy.kotlin.native.desktop")
}

kotlin {
    mingwX64 {
        binaries {
            executable {
                baseName = "mingw-program"
                entryPoint = "check.main"
            }
        }
    }
    sourceSets.mingwX64Main.dependencies {
        implementation("org.thisisthepy.compose.ui:ui-graphics:$forkVersion")
        implementation("org.thisisthepy.compose.ui:ui-text:$forkVersion")
    }
}
