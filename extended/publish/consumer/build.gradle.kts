plugins {
    kotlin("multiplatform") version "2.3.20"
}

val bundle = providers.gradleProperty("bundle").get()
// The suffix every fork version must carry: -dev.<commit> for a dev build, or the release
// version itself, so that one stray reference to another version fails the check.
val suffix = providers.gradleProperty("suffix").get()

repositories {
    // The bundle first and only for the fork's groups, so nothing under org.thisisthepy can
    // come from anywhere else.
    exclusiveContent {
        forRepository { maven { url = uri(file(bundle)) } }
        filter { includeGroupByRegex("org\\.thisisthepy(\\..*)?") }
    }
    mavenCentral()
    google()
}

kotlin {
    jvm()
    linuxX64()
    sourceSets {
        jvmMain.dependencies { implementation(providers.gradleProperty("jvm").get()) }
        linuxX64Main.dependencies { implementation(providers.gradleProperty("linuxX64").get()) }
    }
}

// Compose groups whose JetBrains copy carries classes: one of them next to the fork's puts the
// same classes on the path twice. The runtime and the internal annotation and collection
// libraries are stubs that point at androidx, as the fork's are, and may come from JetBrains.
val jetbrainsStubs = setOf(
    "org.jetbrains.compose.runtime",
    "org.jetbrains.compose.annotation-internal",
    "org.jetbrains.compose.collection-internal",
)

tasks.register("checkResolution") {
    val configurations = listOf("jvmRuntimeClasspath", "linuxX64CompileKlibraries").map { configurations.getByName(it) }
    doLast {
        val problems = mutableListOf<String>()
        for (configuration in configurations) {
            val components = configuration.incoming.resolutionResult.allComponents
                .mapNotNull { it.moduleVersion }
            val fork = components.filter { it.group.startsWith("org.thisisthepy") }
            if (fork.isEmpty()) problems += "${configuration.name}: resolved nothing from the fork"
            for (module in fork) {
                println("${configuration.name}: ${module.group}:${module.name}:${module.version}")
                if (!module.version.endsWith(suffix)) {
                    problems += "${configuration.name}: ${module.group}:${module.name}:${module.version} does not end in $suffix"
                }
            }
            for (module in components) {
                if (module.group.startsWith("org.jetbrains.compose") && module.group !in jetbrainsStubs) {
                    problems += "${configuration.name}: JetBrains' ${module.group}:${module.name}:${module.version} came in beside the fork"
                }
            }
            // Resolving the files too proves every file the metadata names is there.
            configuration.incoming.artifactView { lenient(false) }.files.files
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n"))
    }
}
