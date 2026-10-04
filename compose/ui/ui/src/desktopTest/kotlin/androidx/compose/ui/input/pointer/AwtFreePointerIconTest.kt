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

package androidx.compose.ui.input.pointer

import androidx.compose.ui.internal.ExtendedPointerIcons
import androidx.compose.ui.internal.PointerIconSink
import androidx.compose.ui.internal.StandardPointerIconKind
import androidx.compose.ui.internal.standardKind
import java.awt.Cursor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

object AwtFreePointerIconProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val kinds = listOf(
            PointerIcon.Default, PointerIcon.Crosshair, PointerIcon.Text, PointerIcon.Hand
        ).map { it.standardKind }
        println("kinds=$kinds")
        ExtendedPointerIcons.sink = PointerIconSink { }
    }
}

class AwtFreePointerIconTest {
    @Test
    fun standardIconsReportTheirKind() {
        assertEquals(StandardPointerIconKind.Hand, PointerIcon.Hand.standardKind)
        assertEquals(StandardPointerIconKind.Text, PointerIcon.Text.standardKind)
        assertEquals(StandardPointerIconKind.Default, PointerIcon.Default.standardKind)
        assertEquals(StandardPointerIconKind.Crosshair, PointerIcon.Crosshair.standardKind)
    }

    @Test
    fun awtCursorsStayEqualToTheStandardIcons() {
        assertEquals(PointerIcon.Hand, PointerIcon(Cursor(Cursor.HAND_CURSOR)))
        assertEquals(PointerIcon(Cursor(Cursor.HAND_CURSOR)), PointerIcon.Hand)
        assertEquals(PointerIcon.Hand.hashCode(), PointerIcon(Cursor(Cursor.HAND_CURSOR)).hashCode())
        assertNotEquals(PointerIcon.Hand, PointerIcon(Cursor(Cursor.TEXT_CURSOR)))
        assertEquals(StandardPointerIconKind.Hand, PointerIcon(Cursor(Cursor.HAND_CURSOR)).standardKind)
    }

    @Test
    fun noAwtClassIsLoadedOnTheAwtFreePath() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java,
            "-Xlog:class+load=info:stdout",
            "-cp", System.getProperty("java.class.path"),
            AwtFreePointerIconProbe::class.java.name,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        assertTrue(output.contains("kinds=[Default, Crosshair, Text, Hand]"), output)
        val awt = output.lines().filter { Regex("""\bjava\.awt\.""").containsMatchIn(it) }
        assertTrue(awt.isEmpty(), "java.awt classes were loaded: $awt")
    }
}
