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

package androidx.compose.ui.internal

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.awtEventOrNull
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.setMain

/** Runs in its own JVM: the property is read once, when the dispatcher is first asked for work. */
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
object CoroutinesMainProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        var seen = 0
        Dispatchers.setMain(object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                seen++
            }
        })
        ExtendedMainDispatcher.dispatcher.dispatch(EmptyCoroutineContext, Runnable { })
        check(seen == 1) { "Dispatchers.Main saw $seen" }
        println("probe-ok")
    }
}

/** With the toolkit switched off, the pieces an embedder reaches answer without loading java.awt. */
@OptIn(InternalComposeUiApi::class)
object ExtendedAwtProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        check(!ExtendedAwt.available) { "compose.awt=false was not read" }
        val name = androidx.compose.ui.input.key.Key.A.toString()
        check(name.startsWith("Key: ")) { name }
        val event = androidx.compose.ui.input.key.KeyEvent(
            androidx.compose.ui.input.key.Key.A,
            androidx.compose.ui.input.key.KeyEventType.KeyDown,
        )
        check(event.awtEventOrNull == null) { "an embedder's key event is not an AWT one" }
        println("probe-ok")
    }
}

@OptIn(InternalComposeUiApi::class)
class AwtFreeMainDispatcherTest {
    @Test
    fun withTheToolkitSwitchedOffKeyNamesLoadNoAwtClass() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java,
            "-verbose:class",
            "-Djava.awt.headless=true",
            "-Dcompose.awt=false",
            "-cp", System.getProperty("java.class.path"),
            ExtendedAwtProbe::class.java.name,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        assertEquals(true, output.contains("probe-ok"), output)
        val awt = output.lineSequence().filter { it.startsWith("[") && it.contains("java.awt.") }.toList()
        assertEquals(emptyList<String>(), awt)
    }

    @Test
    fun theCoroutinesMainPropertySendsTheWorkToDispatchersMainAndLoadsNoToolkit() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java,
            "-verbose:class",
            "-Djava.awt.headless=true",
            "-Dcompose.main.dispatcher=coroutines",
            // Without Swing's provider, as an embedder that has none runs: finding it loads Swing.
            "-cp", System.getProperty("java.class.path").split(File.pathSeparator)
                .filterNot { it.contains("kotlinx-coroutines-swing") }
                .joinToString(File.pathSeparator),
            CoroutinesMainProbe::class.java.name,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        assertEquals(true, output.contains("probe-ok"), output)
        val awt = output.lineSequence().filter { it.startsWith("[") && it.contains("java.awt.") }.toList()
        assertEquals(emptyList<String>(), awt)
    }

    @Test
    fun anInstalledDispatcherTakesTheMainThreadWork() {
        val seen = mutableListOf<Runnable>()
        val mine = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                seen.add(block)
            }
        }
        ExtendedMainDispatcher.override = mine
        try {
            val work = Runnable { }
            ExtendedMainDispatcher.dispatcher.dispatch(EmptyCoroutineContext, work)
            assertEquals(1, seen.size)
            assertSame(work, seen[0])
            assertEquals(true, ExtendedMainDispatcher.dispatcher.isDispatchNeeded(EmptyCoroutineContext))
        } finally {
            ExtendedMainDispatcher.override = null
        }
    }
}
