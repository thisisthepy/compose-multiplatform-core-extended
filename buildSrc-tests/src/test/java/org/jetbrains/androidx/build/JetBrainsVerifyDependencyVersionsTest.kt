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

import androidx.build.AndroidXDependency
import androidx.build.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * A library is only as stable as its least stable dependency. Every line here publishes on the
 * fork's one version (`1.11.1-ext.5`), and the upstream version its sources are says how stable
 * it is, so the rule reads the upstream version.
 */
@RunWith(JUnit4::class)
class JetBrainsVerifyDependencyVersionsTest {

    /** What `changeMavenCoordinatesToJetBrains` gives a line's projects as their version. */
    private fun lineVersion(upstream: String, published: String = "1.11.1-ext.5") =
        Version(upstream).copy(forkRelease = Version(published).forkRelease, publishedAs = published)

    private fun dependency(version: String) =
        AndroidXDependency("androidx.lifecycle", "lifecycle-common", version, "commonMainApi")

    @Test
    fun aBetaLineMayDependOnTheBetaItRedirectsTo() {
        val lifecycle = lineVersion("2.11.0-beta01")
        assertNull(dependencyStabilityError(stabilityVersionOf(lifecycle), lifecycle.toString(), dependency("2.11.0-beta01")))
    }

    @Test
    fun aBetaLineMayNotDependOnAnAlpha() {
        val lifecycle = lineVersion("2.11.0-beta01")
        assertNotNull(dependencyStabilityError(stabilityVersionOf(lifecycle), lifecycle.toString(), dependency("2.11.0-alpha01")))
    }

    @Test
    fun aStableLineMayNotDependOnABeta() {
        val compose = lineVersion("1.11.1")
        val error = dependencyStabilityError(stabilityVersionOf(compose), compose.toString(), dependency("2.11.0-beta01"))
        assertNotNull(error)
        // The message names the version the line publishes as, which is what a reader sees.
        assertEquals(true, error!!.startsWith("Project with version 1.11.1-ext.5 may not"))
    }

    @Test
    fun theStabilityVersionIsTheUpstreamOneWithTheForkRelease() {
        assertEquals("2.11.0-beta01-ext.5", stabilityVersionOf(lineVersion("2.11.0-beta01")))
        assertEquals("1.11.1-ext.5", stabilityVersionOf(lineVersion("1.11.1")))
        // A project outside the published lines keeps a plain version.
        assertEquals("1.0.0-alpha01", stabilityVersionOf("1.0.0-alpha01"))
    }
}
