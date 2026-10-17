package org.thisisthepy.compose.window.scene

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.thisisthepy.compose.window.WindowPlatform
import org.thisisthepy.compose.window.editMenuItems

/**
 * True where the platform layer puts the edit menu up itself: the AppKit layers do, on a
 * right click. The X11 and Windows layers have no native menu, so the scene draws one.
 */
internal fun WindowPlatform.hasNativeEditMenu(): Boolean = usesCommandKey(name)

/**
 * The edit menu drawn as part of the scene, at [at] (pixels from the top left), built from
 * the same list the native menus use. Choosing an entry reports its id through [onChosen],
 * which the host turns into the shortcut the entry is named after; a press elsewhere
 * closes it.
 */
@Composable
internal fun DrawnEditMenu(at: IntOffset?, onChosen: (Int) -> Unit, onDismiss: () -> Unit) {
    if (at == null) return
    val items = editMenuItems(canCut = true, canCopy = true, canPaste = true, canSelectAll = true)
    Box(Modifier.fillMaxSize().clickable(onClick = onDismiss)) {
        Column(
            Modifier
                .offset { at }
                .width(140.dp)
                .background(Color(0xFFF4F4F4))
                .border(1.dp, Color(0xFFBBBBBB)),
        ) {
            for (item in items) {
                BasicText(
                    item.label,
                    Modifier.fillMaxWidth().clickable { onChosen(item.id) }.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                if (item.separatorAfter) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFCCCCCC)))
            }
        }
    }
}
