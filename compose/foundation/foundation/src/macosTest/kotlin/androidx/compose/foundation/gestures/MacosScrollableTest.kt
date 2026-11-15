/*
 * Copyright 2022 The Android Open Source Project
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

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AppKit.NSEvent
import platform.CoreGraphics.CGEventCreateScrollWheelEvent2
import platform.CoreGraphics.CGScrollEventUnit
import platform.CoreGraphics.kCGScrollEventUnitLine
import platform.CoreGraphics.kCGScrollEventUnitPixel

@OptIn(InternalComposeUiApi::class)
@ExperimentalTestApi
class MacosScrollableTest {
    private val density = Density(2f)
    private val scrollLine = density.density * 10f

    @Test
    fun `scroll by pixels vertically`() = runSkikoComposeUiTest(density = density) {
        val context = TestColumn()
        setContent {
            Box(
                Modifier
                    .scrollable(
                        orientation = Orientation.Vertical,
                        state = context.controller()
                    )
                    .size(10.dp, 20.dp)
            )
        }

        sendScrollingEvent(deltaY = 10, unit = kCGScrollEventUnitPixel)

        waitForIdle()
        assertEquals(10F, context.offset, 0.1F)
    }

    @Test
    fun `scroll by lines vertically`() = runSkikoComposeUiTest(density = density) {
        val context = TestColumn()

        setContent {
            Box(
                Modifier
                    .scrollable(
                        orientation = Orientation.Vertical,
                        state = context.controller()
                    )
                    .size(10.dp, 20.dp)
            )
        }

        sendScrollingEvent(deltaY = 3, unit = kCGScrollEventUnitLine)

        waitForIdle()
        assertEquals(3F * scrollLine, context.offset, 0.1F)
    }

    @Test
    fun `scroll by pixels horizontally`() = runSkikoComposeUiTest(density = density) {
        val context = TestColumn()

        setContent {
            Box(
                Modifier
                    .scrollable(
                        orientation = Orientation.Horizontal,
                        state = context.controller()
                    )
                    .size(10.dp, 20.dp)
            )
        }

        sendScrollingEvent(deltaX = 5, unit = kCGScrollEventUnitPixel)

        waitForIdle()
        assertEquals(5F, context.offset, 0.1F)
    }

    @Test
    fun `scroll by lines horizontally`() = runSkikoComposeUiTest(density = density) {
        val context = TestColumn()

        setContent {
            Box(
                Modifier
                    .scrollable(
                        orientation = Orientation.Horizontal,
                        state = context.controller()
                    )
                    .size(10.dp, 20.dp)
            )
        }

        sendScrollingEvent(deltaX = 2, unit = kCGScrollEventUnitLine)

        waitForIdle()
        assertEquals(2F * scrollLine, context.offset, 0.1F)
    }

    // A window with no AWT and no NSEvent to hand over (the fork's Kotlin/Native window and its
    // GraalVM one) sends only the travel, in the scene's convention: a line is one, ten points
    // of a trackpad are one, and a positive y asks for what is below. So -3 is AppKit's
    // scrollingDeltaY of +3 lines, and -1 its +10 points of a trackpad.

    @Test
    fun `scroll by lines vertically from a window that sends only the travel`() =
        runSkikoComposeUiTest(density = density) {
            val context = TestColumn()
            setContent {
                Box(
                    Modifier
                        .scrollable(orientation = Orientation.Vertical, state = context.controller())
                        .size(10.dp, 20.dp)
                )
            }

            sendTravel(Offset(0f, -3f))

            waitForIdle()
            assertEquals(3F * scrollLine, context.offset, 0.1F)
        }

    @Test
    fun `scroll by trackpad points vertically from a window that sends only the travel`() =
        runSkikoComposeUiTest(density = density) {
            val context = TestColumn()
            setContent {
                Box(
                    Modifier
                        .scrollable(orientation = Orientation.Vertical, state = context.controller())
                        .size(10.dp, 20.dp)
                )
            }

            // Ten points of a trackpad move the content ten points, as in any AppKit view.
            sendTravel(Offset(0f, -1f))

            waitForIdle()
            assertEquals(10F * density.density, context.offset, 0.1F)
        }

    @Test
    fun `scroll by lines horizontally from a window that sends only the travel`() =
        runSkikoComposeUiTest(density = density) {
            val context = TestColumn()
            setContent {
                Box(
                    Modifier
                        .scrollable(orientation = Orientation.Horizontal, state = context.controller())
                        .size(10.dp, 20.dp)
                )
            }

            sendTravel(Offset(-2f, 0f))

            waitForIdle()
            assertEquals(2F * scrollLine, context.offset, 0.1F)
        }

    @Test
    fun `a wheel asking for what is below moves a verticalScroll column down`() =
        runSkikoComposeUiTest(density = density) {
            val state = ScrollState(0)
            setContent {
                Column(Modifier.size(10.dp, 20.dp).verticalScroll(state)) {
                    Box(Modifier.size(10.dp, 200.dp))
                }
            }

            // AppKit's scrollingDeltaY of -2 lines: the wheel turned toward the reader with
            // natural scrolling off, or two fingers pushed up with it on. Content moves up and
            // what is below comes into view, so the scroll position grows.
            sendTravel(Offset(0f, 2f))
            waitForIdle()
            assertEquals((2F * scrollLine).toInt(), state.value)

            // And back up again.
            sendTravel(Offset(0f, -1f))
            waitForIdle()
            assertEquals(scrollLine.toInt(), state.value)
        }

    private fun SkikoComposeUiTest.sendTravel(travel: Offset) {
        scene.sendPointerEvent(
            eventType = PointerEventType.Scroll,
            position = Offset(1f, 1f),
            scrollDelta = travel,
        )
    }

    private fun SkikoComposeUiTest.sendScrollingEvent(
        deltaX: Int = 0,
        deltaY: Int = 0,
        unit: CGScrollEventUnit,
    ) {
        scene.sendPointerEvent(
            eventType = PointerEventType.Scroll,
            position = Offset.Zero,
            scrollDelta = Offset(x = deltaX.toFloat(), y = deltaY.toFloat()),
            nativeEvent = scrollingEvent(deltaX = deltaX, deltaY = deltaY, unit = unit),
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun scrollingEvent(
        deltaX: Int,
        deltaY: Int,
        unit: CGScrollEventUnit,
    ) = NSEvent.eventWithCGEvent(
        CGEventCreateScrollWheelEvent2(
            source = null,
            units = unit,
            wheelCount = 2U,
            wheel1 = deltaY,
            wheel2 = deltaX,
            wheel3 = 0,
        )
    )

    private class TestColumn {
        var offset = 0f
            private set

        @Composable
        fun controller() = ScrollableState(::consumeScrollDelta)

        private fun consumeScrollDelta(delta: Float): Float {
            offset += delta
            return delta
        }
    }
}
