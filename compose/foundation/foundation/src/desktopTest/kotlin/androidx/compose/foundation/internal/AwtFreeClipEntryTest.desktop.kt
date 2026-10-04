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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.InternalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runInternalSkikoComposeUiTest
import androidx.compose.ui.test.withKeysDown
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.AnnotatedString
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
        if (args.firstOrNull() == "textfield-copy") {
            AwtFreeTextFieldCopyProbe.run()
            return
        }
        val entry = ClipEntry("hello")
        check(entry.hasText())
        check(runBlocking { entry.readText() } == "hello")
        check(runBlocking { entry.readAnnotatedString() }?.text == "hello")
        check(!entry.hasAnnotatedString())
        check(runBlocking { ClipEntry("").readText() } == "")
        val clipboard = object : androidx.compose.ui.platform.Clipboard {
            override val nativeClipboard: androidx.compose.ui.platform.NativeClipboard = "text"
            override suspend fun getClipEntry(): ClipEntry? = ClipEntry("text")
            override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
        }
        check(clipboard.nativeClipboardHasText())
        check(runBlocking { clipboard.hasAnyData() })
        check(AnnotatedString("copied").toClipEntry()?.let { runBlocking { it.readText() } } == "copied")
        check(AnnotatedString("copied").toClipEntry()?.hasAnnotatedString() == true)
        println("probe-ok")
    }
}

/** Select all and copy in a BasicTextField hosted by a scene with no window, using Compose key events. */
@OptIn(InternalTestApi::class, ExperimentalTestApi::class)
object AwtFreeTextFieldCopyProbe {
    fun run() {
        var stored: ClipEntry? = null
        val clipboard = object : Clipboard {
            override val nativeClipboard: NativeClipboard = ""
            override suspend fun getClipEntry(): ClipEntry? = stored
            override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                stored = clipEntry
            }
        }
        runInternalSkikoComposeUiTest {
            val state = TextFieldState("copy me")
            val focusRequester = FocusRequester()
            setContent {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    BasicTextField(
                        state = state,
                        modifier = Modifier.focusRequester(focusRequester).testTag("field"),
                    )
                }
            }
            runOnIdle { focusRequester.requestFocus() }
            waitForIdle()
            onNodeWithTag("field").performKeyInput {
                withKeysDown(listOf(Key.CtrlLeft)) { pressKey(Key.A) }
                withKeysDown(listOf(Key.CtrlLeft)) { pressKey(Key.C) }
            }
            waitForIdle()
        }
        val copied = stored?.let { runBlocking { it.readText() } }
        check(copied == "copy me") { "clipboard held: $copied" }
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
    fun plainTextEntryLoadsNoAwtClass() = noAwtClassLoaded()

    @Test
    fun textFieldCopyLoadsNoAwtClass() = noAwtClassLoaded("textfield-copy")

    private fun noAwtClassLoaded(vararg args: String) {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java,
            "-verbose:class",
            "-Djava.awt.headless=true",
            "-cp", System.getProperty("java.class.path"),
            AwtFreeClipEntryProbe::class.java.name,
            *args,
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
