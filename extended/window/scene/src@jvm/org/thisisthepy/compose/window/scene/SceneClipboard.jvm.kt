@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package org.thisisthepy.compose.window.scene

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.text.AnnotatedString
import java.awt.datatransfer.DataFlavor
import org.thisisthepy.compose.window.WindowPlatform

internal actual fun createSceneClipboard(platform: WindowPlatform, fallback: Clipboard): Clipboard =
    JvmSceneClipboard(platform)

internal actual fun plainTextClipEntry(text: String): ClipEntry = ClipEntry(text)

internal actual fun clipEntryPlainText(clipEntry: ClipEntry?): String? =
    when (val native = clipEntry?.nativeClipEntry) {
        null -> null
        is String -> native
        is AnnotatedString -> native.text
        else -> clipEntry.asAwtTransferable?.let {
            runCatching { it.getTransferData(DataFlavor.stringFlavor) as? String }.getOrNull()
        }
    }

private class JvmSceneClipboard(private val platform: WindowPlatform) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? =
        platform.readClipboardText()?.takeIf { it.isNotEmpty() }?.let(::plainTextClipEntry)

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        platform.writeClipboardText(clipEntryPlainText(clipEntry) ?: "")
    }

    // Exposing the current text as nativeClipboard lets Compose's desktop context menu
    // check clipboard availability synchronously without touching java.awt.
    override val nativeClipboard: NativeClipboard
        get() = platform.readClipboardText() ?: ""
}
