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

@file:OptIn(ExperimentalComposeUiApi::class)

package androidx.compose.foundation.internal

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Reads a plain-text ClipEntry in a fresh JVM, so earlier tests cannot have loaded AWT classes. */
object AwtFreeClipEntryProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val entry = ClipEntry("hello")
        check(entry.hasText())
        check(runBlocking { entry.readText() } == "hello")
        check(runBlocking { entry.readAnnotatedString() }?.text == "hello")
        check(!entry.hasAnnotatedString())
        check(runBlocking { ClipEntry("").readText() } == "")
        println("probe-ok")
    }
}

class AwtFreeClipEntryTest {
    @Test
    fun plainTextEntryRoundTripsInProcess() = runBlocking {
        val entry = ClipEntry("plain")
        assertTrue(entry.hasText())
        assertFalse(entry.hasAnnotatedString())
        assertEquals("plain", entry.readText())
        assertEquals("plain", entry.readAnnotatedString()?.text)
    }

    @Test
    fun plainTextEntryLoadsNoAwtClass() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java,
            "-verbose:class",
            "-Djava.awt.headless=true",
            "-cp", System.getProperty("java.class.path"),
            AwtFreeClipEntryProbe::class.java.name,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        assertTrue(output.contains("probe-ok"), output)
        val loaded = output.lineSequence()
            .filter { it.startsWith("[") && it.contains("java.awt.") }
            .toList()
        assertTrue(loaded.isEmpty(), "AWT classes were loaded:\n" + loaded.joinToString("\n"))
    }
}
