/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.androidx.build

import androidx.build.AndroidXDependency
import androidx.build.Version
import androidx.build.multiplatformExtension
import androidx.build.uptodatedness.cacheEvenIfNoOutputs
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.setProperty

/**
 * Task for verifying the library dependency-stability-suffix rule (A library is only as stable as
 * its least stable dependency)
 */
@CacheableTask
abstract class JetBrainsVerifyDependencyVersionsTask : DefaultTask() {

    init {
        group = "Compose Multiplatform"
        description = "Task for verifying the library dependency-stability-suffix rule"
    }

    /** The version whose stability the rule reads: the line's upstream one ([stabilityVersionOf]). */
    @get:Input
    abstract val version: Property<String>

    /** The version the project publishes as, which the failure names. */
    @get:Input
    abstract val publishedVersion: Property<String>

    @get:Input
    val androidXDependencySet: SetProperty<AndroidXDependency> = project.objects.setProperty()

    /**
     * Iterate through the dependencies of the project and ensure none of them are of an inferior
     * release. This means that a beta project should not have any alpha dependencies, an rc project
     * should not have any alpha or beta dependencies and a stable version should only depend on
     * other stable versions. Dependencies defined with testCompile and friends along with
     * androidTestImplementation and similar are excluded from this verification.
     */
    @TaskAction
    fun verifyDependencyVersions() {
        androidXDependencySet.get().forEach { dependency ->
            dependencyStabilityError(version.get(), publishedVersion.get(), dependency)?.let {
                throw GradleException(it)
            }
        }
    }
}

/**
 * The version whose stability the rule reads for a project versioned [projectVersion]. Every line
 * publishes on the fork's one version (`1.11.1-ext.5`), which reads as stable, while the sources
 * of the Lifecycle line, say, are `2.11.0-beta01` and redirect to that androidx beta on Android
 * and the JVM. [Version] keeps the upstream version and prints the published one, so this is the
 * upstream version with the fork's release, as upstream reads it.
 */
internal fun stabilityVersionOf(projectVersion: Any): String =
    (projectVersion as? Version)?.copy(publishedAs = null)?.toString() ?: projectVersion.toString()

/**
 * Why a project at [stabilityVersion] (published as [publishedVersion]) may not take
 * [dependency], or null when it may: a dependency must be at least as stable as the project.
 */
internal fun dependencyStabilityError(
    stabilityVersion: String,
    publishedVersion: String,
    dependency: AndroidXDependency,
): String? {
    val projectReleasePhase = releasePhase(stabilityVersion)
    if (projectReleasePhase < 0) {
        return "Project has unexpected release phase $stabilityVersion"
    }
    val dependencyReleasePhase = releasePhase(dependency.version)
    if (dependencyReleasePhase < 0) {
        return "Dependency ${dependency.group}:${dependency.name}" +
            ":${dependency.version} has unexpected release phase ${dependency.version}"
    }
    if (dependencyReleasePhase < projectReleasePhase) {
        return "Project with version $publishedVersion may " +
            "not take a dependency on less-stable artifact ${dependency.group}:" +
            "${dependency.name}:${dependency.version} for configuration " +
            "${dependency.configurationName}. Dependency versions must be at least as " +
            "stable as the project version ($stabilityVersion upstream)."
    }
    return null
}

private fun releasePhase(versionString: String): Int {
    val version = Version(versionString)
    return when {
        version.isStable() -> 4
        version.isRC() -> 3
        version.isBeta() -> 2
        version.isAlpha() -> 1
        version.isSnapshot() -> 0
        else -> -1
    }
}

internal fun Project.configureDependencyVerification() {
    // Verify only what is publishing
    val component = JetBrainsPublication.projectPathToComponent[path] ?: return

    tasks.register(
        "jbVerifyDependencyVersions",
        JetBrainsVerifyDependencyVersionsTask::class.java
    ) { task ->
        task.version.set(project.provider { stabilityVersionOf(project.version) })
        task.publishedVersion.set(project.provider { project.version.toString() })
        task.androidXDependencySet.set(
            project.provider {
                multiplatformExtension!!
                    .targets
                    .filter { target ->
                        component.supportedPlatforms.any {
                            it.matches(target.name) && !hasRedirection(it)
                        }
                    }
                    .flatMap { target ->
                        target.compilations
                            .filter { !it.name.contains("test", ignoreCase = true) }
                            .flatMap { compilation ->
                                listOf(
                                    compilation.defaultSourceSet.implementationConfigurationName,
                                    compilation.defaultSourceSet.apiConfigurationName,
                                    compilation.defaultSourceSet.runtimeOnlyConfigurationName
                                )
                            }
                    }
                    .asSequence()
                    .map { project.configurations.getByName(it) }
                    .flatMap { configuration ->
                        configuration.allDependencies
                            .filter { it.group != null && it.version != null }
                            .map { dependency ->
                                AndroidXDependency(
                                    dependency.group!!,
                                    dependency.name,
                                    dependency.version!!,
                                    configuration.name,
                                )
                            }
                    }
                    .toList()
            }
        )
        task.cacheEvenIfNoOutputs()
    }
}
