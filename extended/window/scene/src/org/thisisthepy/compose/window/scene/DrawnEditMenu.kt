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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.thisisthepy.compose.window.ContextMenuItem
import org.thisisthepy.compose.window.SystemTheme
import org.thisisthepy.compose.window.WindowPlatform
import org.thisisthepy.compose.window.systemEditMenu

/** Color palette for [DrawnEditMenu] under light or dark system themes. */
internal data class DrawnEditMenuColors(
    val background: Color,
    val border: Color,
    val separator: Color,
    val text: Color,
    val disabledText: Color,
)

/** Returns the [DrawnEditMenu] palette matching [theme]. */
internal fun drawnEditMenuColors(theme: SystemTheme): DrawnEditMenuColors = when (theme) {
    SystemTheme.Dark -> DrawnEditMenuColors(
        background = Color(0xFF2B2B2B),
        border = Color(0xFF4A4A4A),
        separator = Color(0xFF3F3F3F),
        text = Color(0xFFE6E6E6),
        disabledText = Color(0xFF7A7A7A),
    )

    SystemTheme.Light -> DrawnEditMenuColors(
        background = Color(0xFFF4F4F4),
        border = Color(0xFFBBBBBB),
        separator = Color(0xFFCCCCCC),
        text = Color(0xFF1A1A1A),
        disabledText = Color(0xFF999999),
    )
}

/**
 * Builds the edit menu entries for [DrawnEditMenu], disabling Paste when the platform
 * clipboard holds no text.
 */
internal fun drawnEditMenuItems(platform: WindowPlatform): List<ContextMenuItem> =
    systemEditMenu(clipboardHasText = !platform.readClipboardText().isNullOrEmpty())

/**
 * Edit menu drawn inside the scene at [at] (pixels from the top left), reflecting clipboard
 * availability for Paste and [LocalSystemTheme] for dark and light colors.
 */
@Composable
internal fun DrawnEditMenu(
    at: IntOffset?,
    platform: WindowPlatform,
    onChosen: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (at == null) return
    val colors = drawnEditMenuColors(LocalSystemTheme.current)
    val items = drawnEditMenuItems(platform)
    Box(Modifier.fillMaxSize().clickable(onClick = onDismiss)) {
        Column(
            Modifier
                .offset { at }
                .width(140.dp)
                .background(colors.background)
                .border(1.dp, colors.border),
        ) {
            for (item in items) {
                BasicText(
                    text = item.label,
                    style = TextStyle(color = if (item.enabled) colors.text else colors.disabledText),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = item.enabled) { onChosen(item.id) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                if (item.separatorAfter) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.separator))
                }
            }
        }
    }
}
