package org.thisisthepy.compose.window.scene

import androidx.compose.runtime.Composable
import org.thisisthepy.compose.window.ContextMenuItem
import org.thisisthepy.compose.window.WindowPlatform

// The context menu of a platform whose menus are the system's own
// (WindowPlatform.systemContextMenu). What is in the menu stays Compose's: the entries a field
// or a selection builds, in its order, enabled as it says. Only the drawing is the system's,
// so a right click puts up one menu and not the system's beside Compose's drawn one.

/**
 * A menu Compose asked for: the entries, numbered from 0 in the order Compose gave them, and
 * what to do with the number of the one chosen, -1 for none.
 */
internal class SystemMenuRequest(
    val items: List<ContextMenuItem>,
    val onChosen: (Int) -> Unit,
)

/**
 * The one menu between Compose and the platform. Compose's side [ask]s from inside the
 * composition's work; the host [take]s it once the frame is drawn and hands it to
 * [WindowPlatform.showContextMenu], outside any of Compose's work, because the system's menu
 * runs a loop of its own until it closes. The platform's answer is given to [answer].
 */
internal class SystemMenus {
    private var waiting: SystemMenuRequest? = null
    private var shown: SystemMenuRequest? = null

    fun ask(request: SystemMenuRequest) {
        waiting?.onChosen?.invoke(NONE_CHOSEN)
        waiting = request
    }

    /** The menu to put up now, or null. A menu still up that never answered counts as dismissed. */
    fun take(): SystemMenuRequest? {
        val next = waiting ?: return null
        waiting = null
        shown?.onChosen?.invoke(NONE_CHOSEN)
        shown = next
        return next
    }

    /** Hands the platform's answer to the menu that is up. False where no menu is up. */
    fun answer(id: Int): Boolean {
        val current = shown ?: return false
        shown = null
        current.onChosen(id)
        return true
    }

    companion object {
        const val NONE_CHOSEN = -1
    }
}

/** The entries of a system menu for Compose's entries, given as each one's label and whether it can run. */
internal fun systemMenuItems(entries: List<Pair<String, Boolean>>): List<ContextMenuItem> =
    entries.mapIndexed { index, (label, enabled) -> ContextMenuItem(index, label, enabled) }

/**
 * Gives the content a context menu that [menus] puts up through the platform, where Compose
 * would otherwise draw one. On the JVM that is `LocalContextMenuRepresentation`, which a text
 * field's menu and every `ContextMenuArea` are drawn through, and which an application that
 * provides its own replaces. On Kotlin/Native nothing is given: on macOS Compose puts up the
 * system's menu itself.
 */
@Composable
internal expect fun ProvideSystemContextMenu(menus: SystemMenus, content: @Composable () -> Unit)
