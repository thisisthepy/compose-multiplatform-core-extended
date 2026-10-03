/*
 * Copyright 2025 The Android Open Source Project
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

import androidx.build.Version
import org.gradle.api.Project
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.jetbrains.androidx.build.JetBrainsPublication.isLibraryRegistered

private const val ARGUMENT_PREFIX = "jetbrains.publication.version."

/**
 * Set to `true` for a local build that is not a release: every version gets `-dev`, so what
 * it leaves in a local Maven repository cannot be mistaken for, or shadow, the release of the
 * same number.
 */
private const val DEVELOPMENT_BUILD_PROPERTY = "extended.publication.dev"

private fun Project.parseJetBrainsVersions(): JetBrainsVersions {
    val development = findProperty(DEVELOPMENT_BUILD_PROPERTY)?.toString() == "true"
    return JetBrainsVersions(
        properties.keys
            .filter { it.startsWith(ARGUMENT_PREFIX) }
            .associate { propertyName ->
                val library = propertyName.replace(ARGUMENT_PREFIX, "")
                require(isLibraryRegistered(library)) {
                    "$propertyName points to a non registered library in the " +
                        "JetBrainsPublication class"
                }
                val declared = project.properties[propertyName] as String
                require(Version(declared).forkRelease != null) {
                    "$propertyName is $declared, which is a version JetBrains could publish too. " +
                        "This fork publishes as <upstream version>-ext.<N>, for example " +
                        "1.11.1-ext.1, or -ext.<N>-dev for a local build (extended/COORDINATES.md)."
                }
                val version =
                    if (development && !Version(declared).isForkDevelopmentBuild()) "$declared-dev"
                    else declared
                library to version
            }
    )
}

abstract class JetBrainsVersionsService :
    BuildService<JetBrainsVersionsService.Params> {

    interface Params : BuildServiceParameters {
        var versions: JetBrainsVersions
    }

    companion object {
        fun versions(project: Project): JetBrainsVersions {
            val service = project.rootProject.gradle.sharedServices.registerIfAbsent(
                "JetBrainsVersionsService",
                JetBrainsVersionsService::class.java
            ) { spec ->
                spec.parameters.versions = project.rootProject.parseJetBrainsVersions()
            }
            return service.get().parameters.versions
        }
    }
}
