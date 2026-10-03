/*
 * Copyright 2026 The Android Open Source Project
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

import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * What this fork publishes under. extended/COORDINATES.md holds the same table for people;
 * this holds it for the build, so the two cannot drift without a test saying so.
 */
@RunWith(JUnit4::class)
class JetBrainsPublicationTest {

    @Test
    fun everyPublishedGroupIsUnderTheForkPrefix() {
        for (path in JetBrainsPublication.projectPathToComponent.keys) {
            val group = JetBrainsPublication.mavenGroupFor(path)
            assertTrue(
                "$path is published as $group, which is not under org.thisisthepy.compose",
                group.startsWith("org.thisisthepy.compose."),
            )
        }
    }

    @Test
    fun composeGroupsKeepTheirNameUnderTheForkPrefix() {
        assertEquals("org.thisisthepy.compose.ui", JetBrainsPublication.mavenGroupFor(":compose:ui:ui"))
        assertEquals(
            "org.thisisthepy.compose.foundation",
            JetBrainsPublication.mavenGroupFor(":compose:foundation:foundation-layout"),
        )
        assertEquals(
            "org.thisisthepy.compose.material3.adaptive",
            JetBrainsPublication.mavenGroupFor(":compose:material3:adaptive:adaptive-layout"),
        )
        assertEquals(
            "org.thisisthepy.compose.desktop",
            JetBrainsPublication.mavenGroupFor(":compose:desktop:desktop"),
        )
    }

    @Test
    fun androidxLibrariesGoUnderTheForkAndroidxPrefix() {
        assertEquals(
            "org.thisisthepy.compose.androidx.lifecycle",
            JetBrainsPublication.mavenGroupFor(":lifecycle:lifecycle-runtime-compose"),
        )
        assertEquals(
            "org.thisisthepy.compose.androidx.navigationevent",
            JetBrainsPublication.mavenGroupFor(":navigationevent:navigationevent-compose"),
        )
        assertEquals(
            "org.thisisthepy.compose.androidx.window",
            JetBrainsPublication.mavenGroupFor(":window:window-core"),
        )
    }

    @Test
    fun annotationAndCollectionKeepTheirInternalGroups() {
        assertEquals(
            "org.thisisthepy.compose.annotation-internal",
            JetBrainsPublication.mavenGroupFor(":annotation:annotation"),
        )
        assertEquals(
            "org.thisisthepy.compose.collection-internal",
            JetBrainsPublication.mavenGroupFor(":collection:collection"),
        )
    }

    @Test
    fun everyPublishedGroupMapsBackToItsProject() {
        for (path in JetBrainsPublication.projectPathToComponent.keys) {
            val group = JetBrainsPublication.mavenGroupFor(path)
            val name = path.substringAfterLast(":")
            assertEquals(
                "$group:$name does not map back to $path",
                path,
                JetBrainsPublication.projectPathForCoordinates(group, name),
            )
        }
    }

    @Test
    fun jetBrainsCoordinatesStillMapToTheirProjects() {
        assertEquals(
            ":compose:ui:ui",
            JetBrainsPublication.projectPathForCoordinates("org.jetbrains.compose.ui", "ui"),
        )
        assertEquals(
            ":lifecycle:lifecycle-common",
            JetBrainsPublication.projectPathForCoordinates(
                "org.jetbrains.androidx.lifecycle",
                "lifecycle-common",
            ),
        )
        assertEquals(
            ":collection:collection",
            JetBrainsPublication.projectPathForCoordinates(
                "org.jetbrains.compose.collection-internal",
                "collection",
            ),
        )
    }

    /**
     * The Android variant of every published module points at the androidx artifact, and which
     * one is worked out by rewriting the group with `artifactRedirection.groupIdReplacement`.
     * A replacement that names a group this build no longer publishes under leaves the group as
     * it is, and the Android variant then points at an artifact nobody publishes.
     */
    @Test
    fun groupIdReplacementTurnsEveryForkGroupBackIntoItsAndroidxGroup() {
        val root = repositoryRoot()
        val samples = mapOf(
            "compose" to ":compose:ui:ui",
            "lifecycle" to ":lifecycle:lifecycle-runtime-compose",
            "navigation" to ":navigation:navigation-compose",
            "navigation3" to ":navigation3:navigation3-ui",
            "navigationevent" to ":navigationevent:navigationevent-compose",
            "savedstate" to ":savedstate:savedstate",
            "window" to ":window:window-core",
        )
        for ((directory, path) in samples) {
            val properties = Properties().apply {
                File(root, "$directory/gradle.properties").inputStream().use { load(it) }
            }
            val replacement = properties.getProperty("artifactRedirection.groupIdReplacement")
            val group = JetBrainsPublication.mavenGroupFor(path)
            val redirected =
                group.replace(replacement.substringBefore("->"), replacement.substringAfter("->"))
            val expected = "androidx." + path.removePrefix(":").substringBeforeLast(":").replace(":", ".")
            assertEquals("$directory/gradle.properties redirects $group wrongly", expected, redirected)
        }
    }

    private fun repositoryRoot(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle").isFile && File(directory, "compose/gradle.properties").isFile) {
                return directory
            }
            directory = directory.parentFile
        }
        error("the tests did not run inside a checkout of this repository")
    }
}
