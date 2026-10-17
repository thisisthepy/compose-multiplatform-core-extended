/*
 * Copyright 2021 The Android Open Source Project
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

import androidx.compose.ui.internal.StandardPointerIcon
import androidx.compose.ui.internal.StandardPointerIconKind
import java.awt.Cursor

internal class AwtCursor(val cursor: Cursor) : PointerIcon {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AwtCursor && other !is StandardPointerIcon) return false

        if (other is StandardPointerIcon) return other == this
        other as AwtCursor

        // AwtCursor doesn't implement equals
        if (cursor.type != other.cursor.type) return false

        // All custom cursors have the type CUSTOM_CURSOR, so we can only use the type if it's
        // not CUSTOM_CURSOR
        if (cursor.type == Cursor.CUSTOM_CURSOR) return cursor === other.cursor

        return true
    }

    override fun hashCode(): Int {
        // AwtCursor doesn't implement hashCode
        // Aso, all custom cursors have the type CUSTOM_CURSOR, so we can only use the type if it's
        // not CUSTOM_CURSOR
        val type = cursor.type
        return if (type == Cursor.CUSTOM_CURSOR) System.identityHashCode(cursor) else type.hashCode()
    }

    override fun toString(): String {
        return "AwtCursor(cursor=$cursor)"
    }
}

/**
 * Creates [PointerIcon] from [Cursor]
 */
fun PointerIcon(cursor: Cursor): PointerIcon = AwtCursor(cursor)

internal actual val pointerIconDefault: PointerIcon = StandardPointerIcon(StandardPointerIconKind.Default)
internal actual val pointerIconCrosshair: PointerIcon = StandardPointerIcon(StandardPointerIconKind.Crosshair)
internal actual val pointerIconText: PointerIcon = StandardPointerIcon(StandardPointerIconKind.Text)
internal actual val pointerIconHand: PointerIcon = StandardPointerIcon(StandardPointerIconKind.Hand)
