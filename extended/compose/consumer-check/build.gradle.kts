// Resolves this fork's Compose and window modules for every target they are published for, the
// way a consumer's Gradle build does, and fails if anything of JetBrains' Compose that carries
// classes comes with them: next to the fork's (org.thisisthepy.compose) it would be the same
// classes twice, which no resolver merges because the groups differ.
//
// Usage: gradle -p extended/compose/consumer-check checkResolution [-PforkVersion=1.11.1-ext.1]
//        [-Ptargets=jvm,macosArm64,linuxX64,linuxArm64,mingwX64] [-Pwindow=true]
//
// JetBrains' org.jetbrains.compose.runtime is allowed: its runtime and runtime-saveable are stubs
// with no classes that point at androidx.compose.runtime, as the fork's are, and JetBrains'
// lifecycle and savedstate libraries ask for them by that name.
import java.util.zip.ZipFile

plugins {
    kotlin("multiplatform") version "2.3.20"
}

val forkVersion = providers.gradleProperty("forkVersion").orNull ?: "1.11.1-ext.1"
val wantedTargets = (providers.gradleProperty("targets").orNull ?: "jvm,macosArm64,linuxX64,linuxArm64,mingwX64")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
val withWindow = providers.gradleProperty("window").orNull?.toBoolean() ?: true

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
            if (runtime.size != 1 || runtime.none { it.startsWith("androidx.compose.runtime:runtime") }) {
                problems += "jvmRuntimeClasspath: the Compose runtime (androidx/compose/runtime/Composer.class) comes from ${runtime.ifEmpty { setOf("nothing") }.joinToString()}, not from androidx.compose.runtime:runtime alone"
            } else {
                println("jvmRuntimeClasspath: one Compose runtime, from ${runtime.single()}")
            }
        }
        if (problems.isNotEmpty()) {
            problems.distinct().forEach { System.err.println(it) }
            throw GradleException("${problems.distinct().size} problem(s) resolving this fork's artifacts (above)")
        }
        println("ok: no JetBrains Compose with classes in ${names.joinToString()}")
    }
}
