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

internal actual fun CompositionLocalConsumerModifierNode.platformScrollConfig(): ScrollConfig =
    LinuxScrollConfig

/**
 * How far, and which way, a turn of the wheel scrolls.
 *
 * X11 reports a wheel as a button press, which the window sends on as three lines a notch
 * with a positive y for what is below, and there is nothing else here to ask. The distance and
 * the sign are desktop Compose's for Linux (see [linuxWheelScroll]).
 */
private object LinuxScrollConfig : ScrollConfig {
    override fun Density.calculateMouseWheelScroll(event: PointerEvent, bounds: IntSize): Offset {
        var travel = Offset.Zero
        for (change in event.changes) travel += change.scrollDelta
        return linuxWheelScroll(travel, bounds)
    }
}
