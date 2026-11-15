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

import androidx.compose.ui.appkit.appkitEventOrNull
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

internal actual fun CompositionLocalConsumerModifierNode.platformScrollConfig(): ScrollConfig =
    MacOsScrollConfig

private object MacOsScrollConfig : ScrollConfig {
    // See https://developer.apple.com/documentation/appkit/nsevent/1535387-scrollingdeltay
    override fun Density.calculateMouseWheelScroll(event: PointerEvent, bounds: IntSize): Offset {
        val e = event.appkitEventOrNull ?: return travelScroll(event)

        // The multiplier value was derived from desktop MacOSCocoaConfig
        val multiplier = if (e.hasPreciseScrollingDeltas) 1.0F else LineDistance.toPx()

        return Offset(
            x = e.scrollingDeltaX.toFloat() * multiplier,
            y = e.scrollingDeltaY.toFloat() * multiplier,
        )
    }

    /**
     * The scroll of an event that carries no NSEvent, only its travel in the pointer change's
     * scrollDelta. A window that does not hand Compose its NSEvent (one with no AWT, which
     * records each event as plain numbers) sends the travel as AWT does: in lines, with a
     * positive y asking for what is below, the opposite of AppKit's scrollingDeltaY, which
     * such a window negates. A trackpad's points arrive divided by ten, so ten points are one
     * line. One line is [LineDistance], the distance an NSScrollView moves for a line, so a
     * wheel moves as far as it does in any AppKit view and a trackpad moves the content as
     * far as the fingers went.
     */
    private fun Density.travelScroll(event: PointerEvent): Offset {
        var travel = Offset.Zero
        for (change in event.changes) travel += change.scrollDelta
        return -travel * LineDistance.toPx()
    }
}

/** How far one line of a wheel scrolls, as NSScrollView's default lineScroll of 10 points. */
private val LineDistance = 10.dp
