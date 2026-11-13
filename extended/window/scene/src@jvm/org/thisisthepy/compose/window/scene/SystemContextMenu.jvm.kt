package org.thisisthepy.compose.window.scene

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/**
 * Compose's context menus on the JVM are drawn through `LocalContextMenuRepresentation`: a
 * text field's default `LocalTextContextMenu` builds Cut, Copy, Paste and Select All and hands
 * them there. The one given here hands them to the platform instead of drawing them. Compose
 * reads the innermost representation, so an application that gives its own, or a text
 * context menu of its own that draws, shows that one alone.
 */
@Composable
internal actual fun ProvideSystemContextMenu(menus: SystemMenus, content: @Composable () -> Unit) {
    val representation = remember(menus) { SystemContextMenuRepresentation(menus) }
    CompositionLocalProvider(LocalContextMenuRepresentation provides representation, content = content)
}

private class SystemContextMenuRepresentation(private val menus: SystemMenus) : ContextMenuRepresentation {
    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        val status = state.status
        if (status !is ContextMenuState.Status.Open) return
        LaunchedEffect(status) {
            val offered = items()
            if (offered.isEmpty()) {
                state.status = ContextMenuState.Status.Closed
                return@LaunchedEffect
            }
            menus.ask(
                SystemMenuRequest(systemMenuItems(offered.map { it.label to it.enabled })) { chosen ->
                    // Closed before the entry runs, so an entry that opens something else
                    // does so with this menu gone.
                    if (state.status == status) state.status = ContextMenuState.Status.Closed
                    offered.getOrNull(chosen)?.takeIf { it.enabled }?.onClick?.invoke()
                },
            )
        }
    }
}
