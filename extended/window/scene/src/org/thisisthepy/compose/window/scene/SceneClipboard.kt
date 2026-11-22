package org.thisisthepy.compose.window.scene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import org.thisisthepy.compose.window.WindowPlatform

/**
 * Provides [LocalClipboard] and [LocalClipboardManager] backed by [WindowPlatform.readClipboardText]
 * and [WindowPlatform.writeClipboardText].
 */
@Composable
internal fun ProvideSceneClipboard(platform: WindowPlatform, content: @Composable () -> Unit) {
    val fallback = LocalClipboard.current
    val clipboard = remember(platform, fallback) { createSceneClipboard(platform, fallback) }
    @Suppress("DEPRECATION")
    val clipboardManager = remember(platform) { SceneClipboardManager(platform) }
    @Suppress("DEPRECATION")
    CompositionLocalProvider(
        LocalClipboard provides clipboard,
        LocalClipboardManager provides clipboardManager,
        content = content,
    )
}

internal expect fun createSceneClipboard(platform: WindowPlatform, fallback: Clipboard): Clipboard

internal expect fun plainTextClipEntry(text: String): ClipEntry

internal expect fun clipEntryPlainText(clipEntry: ClipEntry?): String?

@Suppress("DEPRECATION")
internal class SceneClipboardManager(private val platform: WindowPlatform) : ClipboardManager {
    override fun getText(): AnnotatedString? =
        platform.readClipboardText()?.takeIf { it.isNotEmpty() }?.let(::AnnotatedString)

    override fun setText(annotatedString: AnnotatedString) {
        platform.writeClipboardText(annotatedString.text)
    }

    override fun hasText(): Boolean = !platform.readClipboardText().isNullOrEmpty()

    override fun getClip(): ClipEntry? =
        platform.readClipboardText()?.takeIf { it.isNotEmpty() }?.let(::plainTextClipEntry)

    override fun setClip(clipEntry: ClipEntry?) {
        platform.writeClipboardText(clipEntryPlainText(clipEntry) ?: "")
    }
}
