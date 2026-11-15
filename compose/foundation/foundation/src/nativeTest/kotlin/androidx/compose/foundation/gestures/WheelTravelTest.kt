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

package androidx.compose.foundation.gestures

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The direction and distance of a wheel on the Linux and Windows windows with no AWT, whose
 * configs (LinuxScrollConfig, WindowsScrollConfig) hand the travel to these.
 *
 * The travel is what every window layer sends (WindowEvent.SCROLL in the fork's window
 * modules): lines, a positive y asking for what is below and a positive x for what is to the
 * right. Compose's scroll config answers the other way: a negative result moves a
 * verticalScroll forward, its position growing, as desktop Compose answers for AWT's wheel
 * rotation. Run here on macOS, because the same native source set reaches Linux and Windows.
 */
class WheelTravelTest {
    private val bounds = IntSize(300, 400)

    // Desktop LinuxGnomeConfig: a line moves the square root of the scrolled side, in pixels.
    // X11 sends three lines a notch, which is AWT's scrollAmount there.

    @Test
    fun linux_what_is_below_moves_a_vertical_scroll_forward() {
        val scroll = (linuxWheelScroll(Offset(0f, 3f), bounds))
        assertEquals(Offset(0f, -3f * sqrt(400f)), scroll)
    }

    @Test
    fun linux_what_is_above_moves_it_back() {
        val scroll = (linuxWheelScroll(Offset(0f, -3f), bounds))
        assertEquals(Offset(0f, 3f * sqrt(400f)), scroll)
    }

    @Test
    fun linux_what_is_to_the_right_moves_a_horizontal_scroll_forward() {
        val scroll = (linuxWheelScroll(Offset(3f, 0f), bounds))
        assertEquals(Offset(-3f * sqrt(300f), 0f), scroll)
    }

    // Desktop WindowsWinUIConfig: a line is a twentieth of the scrolled side. The window has
    // already multiplied the notches by the lines a notch moves in the mouse settings, which is
    // AWT's scrollAmount there, so the travel is counted in lines once.

    @Test
    fun windows_what_is_below_moves_a_vertical_scroll_forward() {
        // One notch toward the reader, at the default three lines a notch.
        assertEquals(Offset(0f, -3f * 400f / 20f), windowsWheelScroll(Offset(0f, 3f), bounds))
    }

    @Test
    fun windows_what_is_above_moves_it_back() {
        assertEquals(Offset(0f, 3f * 400f / 20f), windowsWheelScroll(Offset(0f, -3f), bounds))
        // A precision touchpad's quarter notch, at one line a notch.
        assertEquals(Offset(0f, 0.25f * 400f / 20f), windowsWheelScroll(Offset(0f, -0.25f), bounds))
    }

    @Test
    fun windows_what_is_to_the_right_moves_a_horizontal_scroll_forward() {
        assertEquals(Offset(-3f * 300f / 20f, 0f), windowsWheelScroll(Offset(3f, 0f), bounds))
    }
}
