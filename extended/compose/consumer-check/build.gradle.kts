// Resolves this fork's Compose and window modules for every target they are published for, the
// way a consumer's Gradle build does, and fails if anything of JetBrains' Compose that carries
// classes comes with them: next to the fork's (org.thisisthepy.compose) it would be the same
// classes twice, which no resolver merges because the groups differ.
//
// Usage: gradle -p extended/compose/consumer-check checkResolution [-PforkVersion=1.11.1-ext.1]
//        [-Ptargets=jvm,macosArm64,linuxX64,linuxArm64,mingwX64] [-Pwindow=true]
//
// The Compose runtime that ships is Google's androidx.compose.runtime, at the version JetBrains'
// own Compose maps to (artifactRedirection.version.androidx.compose in gradle.properties: 1.11.2
// for JetBrains' 1.11.1, whose org.jetbrains.compose.runtime:runtime depends on exactly that).
// The fork changes nothing under compose/runtime relative to the JetBrains release it starts
// from, so its org.thisisthepy.compose.runtime:runtime and runtime-saveable are stubs with no
// classes that point there, as JetBrains' are. Both stubs are allowed, JetBrains' because its
// lifecycle and savedstate libraries ask for it by that name. The check pins that coordinate:
// the runtime's classes (the JVM's Composer, the package androidx.compose.runtime in a klib)
// must come from androidx.compose.runtime at that version and from nothing else, so a copy of
// the runtime in the fork's group or JetBrains' fails it.
import java.util.zip.ZipFile

plugins {
    kotlin("multiplatform") version "2.3.20"
}

val forkVersion = providers.gradleProperty("forkVersion").orNull ?: "1.11.1-ext.1"
val wantedTargets = (providers.gradleProperty("targets").orNull ?: "jvm,macosArm64,linuxX64,linuxArm64,mingwX64")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
val withWindow = providers.gradleProperty("window").orNull?.toBoolean() ?: true
// The androidx runtime version the fork's runtime stubs redirect to.
val runtimeVersion = providers.gradleProperty("runtimeVersion").orNull
    ?: rootDir.resolve("../../../gradle.properties").takeIf { it.isFile }?.readLines()
        ?.firstOrNull { it.startsWith("artifactRedirection.version.androidx.compose=") }?.substringAfter("=")?.trim()
    ?: error("no androidx runtime version: pass -PruntimeVersion or run from the fork's checkout")

kotlin {
    if ("jvm" in wantedTargets) jvm()
    if ("macosArm64" in wantedTargets) macosArm64()
    if ("linuxX64" in wantedTargets) linuxX64()
    if ("linuxArm64" in wantedTargets) linuxArm64()
    if ("mingwX64" in wantedTargets) mingwX64()

    sourceSets.commonMain.dependencies {
        implementation("org.thisisthepy.compose.foundation:foundation:$forkVersion")
        implementation("org.thisisthepy.compose.ui:ui:$forkVersion")
        implementation("org.thisisthepy.compose.material3:material3:$forkVersion")
        if (withWindow) implementation("org.thisisthepy.compose.window:common:$forkVersion")
    }
    if (withWindow && "jvm" in wantedTargets) sourceSets.jvmMain.dependencies {
        implementation("org.thisisthepy.compose.window:scene:$forkVersion")
        implementation("org.thisisthepy.compose.window:graalvm-macos:$forkVersion")
        implementation("org.thisisthepy.compose.window:graalvm-linux:$forkVersion")
        implementation("org.thisisthepy.compose.desktop:desktop:$forkVersion")
        implementation("org.thisisthepy.compose.skiko:skiko-awt:$forkVersion")
    }
    if (withWindow && "macosArm64" in wantedTargets) sourceSets.macosArm64Main.dependencies {
        implementation("org.thisisthepy.compose.window:scene:$forkVersion")
        implementation("org.thisisthepy.compose.window:native-macos:$forkVersion")
    }
}

// JetBrains' Compose that carries classes: foundation, ui, animation, material, material3 and
// the components (components-resources brings the rest with it).
val forbidden = Regex("""^org\.jetbrains\.compose\.(foundation|ui|animation|material|material3|material3\.adaptive|components|desktop)(\..*)?$""")

configurations.configureEach {
    // The fork's skiko-awt stands in for JetBrains' (a capability of it); keep the higher one.
    resolutionStrategy.capabilitiesResolution.withCapability("org.jetbrains.skiko", "skiko-awt") { selectHighestVersion() }
}

tasks.register("checkResolution") {
    val names = wantedTargets.flatMap { target ->
        when (target) {
            "jvm" -> listOf("jvmRuntimeClasspath", "jvmCompileClasspath")
            else -> listOf("${target}CompileKlibraries")
        }
    }
    // The shared source sets' metadata, under whichever name this Kotlin Gradle plugin gives it.
    val optional = listOf("allSourceSetsCompileDependenciesMetadata", "commonMainResolvableDependenciesMetadata")
    doLast {
        val problems = mutableListOf<String>()
        for (name in names + optional.filter { configurations.findByName(it) != null }) {
            val configuration = configurations.findByName(name) ?: run { problems += "no configuration $name"; continue }
            val components = configuration.incoming.resolutionResult.allComponents
            val failures = configuration.incoming.resolutionResult.allDependencies
                .filterIsInstance<org.gradle.api.artifacts.result.UnresolvedDependencyResult>()
            failures.forEach { problems += "$name: cannot resolve ${it.requested.displayName}: ${it.failure.message}" }
            components.mapNotNull { it.moduleVersion }.forEach { module ->
                if (forbidden.matches(module.group)) problems += "$name: JetBrains' ${module.group}:${module.name}:${module.version}"
            }
            println("$name: ${components.size} components")
        }
        // One Compose runtime, and no class twice. Both runtime coordinates a consumer meets
        // (JetBrains' org.jetbrains.compose.runtime and the fork's org.thisisthepy.compose.runtime)
        // are stubs that point at androidx.compose.runtime; the classes must come from exactly one
        // jar, androidx.compose.runtime:runtime-desktop, and no androidx.compose class from two.
        if ("jvm" in wantedTargets) {
            val artifacts = configurations.getByName("jvmRuntimeClasspath").incoming.artifacts.artifacts
            val owners = mutableMapOf<String, MutableSet<String>>()
            for (artifact in artifacts) {
                val file = artifact.file
                if (!file.name.endsWith(".jar")) continue
                ZipFile(file).use { zip ->
                    for (entry in zip.entries()) {
                        val name = entry.name
                        if (name.startsWith("androidx/compose/") && name.endsWith(".class") && !name.startsWith("META-INF")) {
                            owners.getOrPut(name) { mutableSetOf() } += artifact.id.componentIdentifier.displayName
                        }
                    }
                }
            }
            owners.filterValues { it.size > 1 }.entries.take(20).forEach { (name, from) ->
                problems += "jvmRuntimeClasspath: $name comes from ${from.joinToString()}"
            }
            val runtime = owners["androidx/compose/runtime/Composer.class"].orEmpty()
            val expected = "androidx.compose.runtime:runtime-desktop:$runtimeVersion"
            if (runtime != setOf(expected)) {
                problems += "jvmRuntimeClasspath: the Compose runtime (androidx/compose/runtime/Composer.class) comes from ${runtime.ifEmpty { setOf("nothing") }.joinToString()}, not from $expected alone"
            } else {
                println("jvmRuntimeClasspath: one Compose runtime, from ${runtime.single()}")
            }
        }
        // The same on every Kotlin/Native target: androidx.compose.runtime:runtime-<target> at the
        // pinned version, and the package androidx.compose.runtime in no klib outside that group.
        for (target in wantedTargets.filter { it != "jvm" }) {
            val name = "${target}CompileKlibraries"
            val configuration = configurations.findByName(name) ?: continue
            val runtimes = configuration.incoming.resolutionResult.allComponents.mapNotNull { it.moduleVersion }
                .filter { it.group == "androidx.compose.runtime" && it.name == "runtime-${target.lowercase()}" }
            if (runtimes.size != 1 || runtimes.single().version != runtimeVersion) {
                problems += "$name: androidx.compose.runtime:runtime-${target.lowercase()} resolves as ${runtimes.joinToString { it.version }.ifEmpty { "nothing" }}, not $runtimeVersion"
            }
            val owners = mutableSetOf<String>()
            for (artifact in configuration.incoming.artifacts.artifacts) {
                val file = artifact.file
                if (!file.isFile || !file.name.endsWith(".klib")) continue
                ZipFile(file).use { zip ->
                    if (zip.entries().asSequence().any { it.name.startsWith("default/linkdata/package_androidx.compose.runtime/") }) {
                        owners += artifact.id.componentIdentifier.displayName
                    }
                }
            }
            val strangers = owners.filter { !it.startsWith("androidx.compose.runtime:") }
            if (owners.none { it.startsWith("androidx.compose.runtime:runtime-${target.lowercase()}:") }) {
                // The runtime's own klib must show the package, or this looks in the wrong place.
                problems += "$name: no klib holds default/linkdata/package_androidx.compose.runtime/, not even androidx.compose.runtime's"
            } else if (strangers.isNotEmpty()) {
                problems += "$name: the package androidx.compose.runtime also comes from ${strangers.joinToString()}"
            } else {
                println("$name: one Compose runtime, androidx.compose.runtime:runtime-${target.lowercase()}:$runtimeVersion")
            }
        }
        if (problems.isNotEmpty()) {
            problems.distinct().forEach { System.err.println(it) }
            throw GradleException("${problems.distinct().size} problem(s) resolving this fork's artifacts (above)")
        }
        println("ok: no JetBrains Compose with classes in ${names.joinToString()}")
    }
}
