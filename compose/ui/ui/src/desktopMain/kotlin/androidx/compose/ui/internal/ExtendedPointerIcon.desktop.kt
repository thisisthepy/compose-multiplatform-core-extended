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
import androidx.compose.ui.input.pointer.AwtCursor
import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

/**
 * Fork-only: the four pointer icons that Compose defines without a platform cursor.
 */
@InternalComposeUiApi
enum class StandardPointerIconKind(internal val awtType: Int) {
    // The values are java.awt.Cursor's type constants, written out so that reading a kind
    // never initialises java.awt.Cursor.
    Default(0),
    Crosshair(1),
    Text(2),
    Hand(12),
}

/**
 * Fork-only: the kind of this icon when it is one of [PointerIcon.Default], [PointerIcon.Crosshair],
 * [PointerIcon.Text] or [PointerIcon.Hand], or null for any other icon (such as one made from a
 * `java.awt.Cursor`). Reading it touches no `java.awt` class.
 */
@InternalComposeUiApi
val PointerIcon.standardKind: StandardPointerIconKind?
    get() = when (this) {
        is StandardPointerIcon -> kind
        // An icon made from a java.awt.Cursor cannot exist where the toolkit is switched off.
        is AwtCursor -> if (ExtendedAwt.available) StandardPointerIconKind.entries.firstOrNull { it.awtType == cursor.type } else null
        else -> null
    }

/**
 * Fork-only: receives every pointer icon change instead of the AWT component. An embedder that
 * draws the scene into a window of its own sets it before the scene is created. While it is null
 * (the default) the icon is applied to the AWT content component as before.
 */
@InternalComposeUiApi
fun interface PointerIconSink {
    fun setPointerIcon(icon: PointerIcon)
}

@InternalComposeUiApi
object ExtendedPointerIcons {
    @Volatile
    var sink: PointerIconSink? = null
}

internal class StandardPointerIcon(val kind: StandardPointerIconKind) : PointerIcon {
    fun toAwtCursor(): Cursor = Cursor(kind.awtType)

    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        other is StandardPointerIcon -> kind == other.kind
        // Cursor(HAND_CURSOR) made by the application still equals PointerIcon.Hand.
        ExtendedAwt.available && other is AwtCursor -> other.cursor.type == kind.awtType
        else -> false
    }

    override fun hashCode(): Int = kind.awtType.hashCode()

    override fun toString(): String = "StandardPointerIcon($kind)"
}
