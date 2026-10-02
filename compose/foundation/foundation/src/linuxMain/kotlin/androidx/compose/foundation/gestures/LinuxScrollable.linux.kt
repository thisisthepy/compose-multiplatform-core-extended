/*
 * Copyright 2024 The Android Open Source Project
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
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

internal actual fun CompositionLocalConsumerModifierNode.platformScrollConfig(): ScrollConfig =
    LinuxScrollConfig

/**
 * How far a turn of the wheel scrolls.
 *
 * The platforms that can tell a wheel from a trackpad ask their own event which it was,
 * because a trackpad reports in pixels and a wheel in notches. There is nothing here to
 * ask: X11 reports a wheel as a button press and Wayland reports an axis, and what reaches
 * this point is already a delta either way. So one distance per notch, which is what every
 * other program on this platform does.
 */
private object LinuxScrollConfig : ScrollConfig {
    override fun Density.calculateMouseWheelScroll(event: PointerEvent, bounds: IntSize): Offset {
        val notch = 64.dp.toPx()
        val change = event.changes.firstOrNull() ?: return Offset.Zero
        return Offset(
            x = change.scrollDelta.x * notch,
            y = change.scrollDelta.y * notch,
        )
    }
}
