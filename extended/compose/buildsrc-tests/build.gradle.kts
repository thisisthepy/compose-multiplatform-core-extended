// See settings.gradle.kts. The test sources stay where upstream keeps them, in buildSrc-tests.
plugins {
    kotlin("jvm") version "2.3.20"
}

val root = rootDir.resolve("../../..").canonicalFile
val buildSrcLibs = root.resolve("out/buildSrc")
val publicJar = buildSrcLibs.resolve("public/build/libs/public.jar")
val privateJar = buildSrcLibs.resolve("private/build/libs/private.jar")

val testSources = root.resolve("buildSrc-tests/src/test/java")

kotlin {
    compilerOptions {
        // The tests call buildSrc's internal functions, as they do inside :buildSrc-tests.
        freeCompilerArgs.add("-Xfriend-paths=${privateJar.path},${publicJar.path}")
    }
}

sourceSets {
    test {
        // The tests this build runs. A test added under org/jetbrains is picked up by itself.
        kotlin.setSrcDirs(listOf(testSources))
        kotlin.include("org/jetbrains/**/*.kt", "androidx/build/VersionTest.kt")
    }
}

dependencies {
    testImplementation(files(publicJar, privateJar))
    testImplementation(gradleApi())
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // JetBrainsPublicationTest finds the repository from the working directory.
    workingDir = root
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    doFirst {
        check(publicJar.isFile && privateJar.isFile) {
            "No buildSrc jars in $buildSrcLibs: run the fork's build once first (the script does)."
        }
    }
}
