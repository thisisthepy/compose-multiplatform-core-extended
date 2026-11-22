@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package org.thisisthepy.compose.window.scene

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard
import org.thisisthepy.compose.window.WindowPlatform

internal actual fun createSceneClipboard(platform: WindowPlatform, fallback: Clipboard): Clipboard =
    LinuxSceneClipboard(platform, fallback)

internal actual fun plainTextClipEntry(text: String): ClipEntry = ClipEntry.withPlainText(text)

internal actual fun clipEntryPlainText(clipEntry: ClipEntry?): String? = clipEntry?.getPlainText()

private class LinuxSceneClipboard(
    private val platform: WindowPlatform,
    private val fallback: Clipboard,
) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? =
        platform.readClipboardText()?.takeIf { it.isNotEmpty() }?.let(::plainTextClipEntry)

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        platform.writeClipboardText(clipEntryPlainText(clipEntry) ?: "")
    }

    override val nativeClipboard: NativeClipboard
        get() = fallback.nativeClipboard
}
