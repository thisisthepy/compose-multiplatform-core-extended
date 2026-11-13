package org.thisisthepy.compose.window.scene

import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * On the JVM the text context menu comes from `LocalTextContextMenu`, whose area holds the
 * menu's state. The one given here is the one that was there, wrapped: it is unchanged in
 * everything it shows and only remembers where each field's area is, so the scene can open
 * the menu of the area under the caret by setting its state. A press would do it too, but a press
 * where nothing is selected selects the word under it on the JVM, and the keyboard is not
 * meant to move the selection.
 *
 * An application that gives its own `LocalTextContextMenu` inside the content replaces this
 * one; the scene then falls back to the press.
 */
@OptIn(ExperimentalFoundationApi::class)
internal actual class FieldMenuOpener actual constructor() {

    private class Area(val state: ContextMenuState) {
        var coordinates: LayoutCoordinates? = null
    }

    private val areas = ArrayList<Area>()

    private inner class Tracking(private val inner: TextContextMenu) : TextContextMenu {
        @Composable
        override fun Area(
            textManager: TextContextMenu.TextManager,
            state: ContextMenuState,
            content: @Composable () -> Unit,
        ) {
            val area = remember(state) { Area(state) }
            DisposableEffect(area) {
                areas += area
                onDispose { areas -= area }
            }
            inner.Area(textManager, state) {
                Box(Modifier.onGloballyPositioned { area.coordinates = it }, propagateMinConstraints = true) {
                    content()
                }
            }
        }
    }

    @Composable
    actual fun Provide(content: @Composable () -> Unit) {
        val inner = LocalTextContextMenu.current
        val tracking = remember(inner) { Tracking(inner) }
        CompositionLocalProvider(LocalTextContextMenu provides tracking, content = content)
    }

    // The caret is inside the field that has it, so the area under it is that field's. The
    // innermost wins, which is the last composed of those that hold the point.
    actual fun open(at: Offset): Boolean {
        for (area in areas.asReversed()) {
            val coordinates = area.coordinates?.takeIf { it.isAttached } ?: continue
            if (!coordinates.boundsInWindow().contains(at)) continue
            area.state.status = ContextMenuState.Status.Open(Rect(coordinates.windowToLocal(at), 0f))
            return true
        }
        return false
    }
}
