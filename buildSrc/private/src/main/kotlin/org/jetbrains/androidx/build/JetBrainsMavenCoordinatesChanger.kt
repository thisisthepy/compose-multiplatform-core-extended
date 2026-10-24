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
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

private const val UPSTREAM_VERSION_PREFIX = "extended.upstream.version."

/**
 * Appends the upstream version a line's sources are to the description of every POM this
 * project publishes, so the one version line the fork ships on loses nothing.
 */
private fun Project.recordUpstreamVersion(library: String, upstream: String) {
    plugins.withId("maven-publish") {
        extensions.getByType(PublishingExtension::class.java).publications
            .withType(MavenPublication::class.java)
            .configureEach { publication ->
                publication.pom.withXml { xml ->
                    val root = xml.asNode()
                    val note = "Upstream version: $upstream ($library line)."
                    val existing = root.children().filterIsInstance<groovy.util.Node>()
                        // A plain name or a namespaced one ("{http://maven.apache.org/POM/4.0.0}description").
                        .firstOrNull { it.name().toString().substringAfterLast('}') == "description" }
                    if (existing == null) {
                        root.appendNode("description", note)
                    } else {
                        existing.setValue("${existing.text().trimEnd()} $note".trim())
                    }
                }
            }
    }
}

fun Project.changeMavenCoordinatesToJetBrains() {
    // we are interested in changing coordinates only for what we publish
    val component = JetBrainsPublication.projectPathToComponent[path] ?: return
    val versions = JetBrainsVersionsService.versions(project)

    val group = JetBrainsPublication.mavenGroupFor(path)
    val published = versions.versionOf(component.library())
    // Every line publishes on the fork's one version; the upstream version its sources are
    // decides what the version means to the build (stable, alpha, final API), and is written
    // into the POM's description.
    val upstream = findProperty(UPSTREAM_VERSION_PREFIX + component.library())?.toString()
    val version =
        if (upstream == null) Version(published)
        else Version(upstream).copy(forkRelease = Version(published).forkRelease, publishedAs = published)
    this.group = group
    this.version = version
    if (upstream != null) {
        recordUpstreamVersion(component.library(), upstream)
    }

    afterEvaluate {
        check(this.group == group) {
            "The $path group is changed after evaluation from $group to ${this.group}. Check if it is overridden inside build.gradle and remove it"
        }
        check(this.version == version) {
            "The $path version is changed after evaluation from $version to ${this.version}. Check if it is overridden inside build.gradle and remove it"
        }
    }
}
