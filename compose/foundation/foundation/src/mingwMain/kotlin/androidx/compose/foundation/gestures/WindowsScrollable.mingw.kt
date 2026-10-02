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
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.windows.SPI_GETWHEELSCROLLLINES
import platform.windows.SystemParametersInfoW
import platform.windows.UINTVar

internal actual fun CompositionLocalConsumerModifierNode.platformScrollConfig(): ScrollConfig =
    WindowsScrollConfig

/**
 * How far a turn of the wheel scrolls.
 *
 * The same answer Compose gives on Windows under the JVM, which was measured against the
 * Start menu: a twentieth of the scrolled area per line, times the number of lines a notch
 * is set to move in the system's mouse settings. Reading that setting is what makes a
 * reader who turned it up or down get what they asked for here too.
 */
private object WindowsScrollConfig : ScrollConfig {
    override fun Density.calculateMouseWheelScroll(event: PointerEvent, bounds: IntSize): Offset {
        val change = event.changes.firstOrNull() ?: return Offset.Zero
        val lines = linesPerNotch()
        return Offset(
            x = change.scrollDelta.x * (bounds.width / 20f) * lines,
            y = change.scrollDelta.y * (bounds.height / 20f) * lines,
        )
    }
}

/** The system's lines per notch, or three, which is the default, if it cannot be read. */
@OptIn(ExperimentalForeignApi::class)
private fun linesPerNotch(): Float = memScoped {
    val lines = alloc<UINTVar>()
    if (SystemParametersInfoW(SPI_GETWHEELSCROLLLINES.toUInt(), 0u, lines.ptr, 0u) != 0) {
        lines.value.toFloat()
    } else {
        3f
    }
}
