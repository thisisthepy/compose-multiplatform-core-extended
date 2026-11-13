package org.thisisthepy.compose.window.scene

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset

/**
 * On Kotlin/Native a field's menu is Compose's common one, whose state the field keeps to
 * itself, so there is nothing to open it through. The scene presses for it, and a press there
 * opens the menu where it is and leaves the selection alone.
 */
internal actual class FieldMenuOpener actual constructor() {
    @Composable
    actual fun Provide(content: @Composable () -> Unit) = content()

    actual fun open(at: Offset): Boolean = false
}
