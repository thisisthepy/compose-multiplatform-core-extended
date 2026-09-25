/*
 * Copyright 2024 The Android Open Source Project
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

package androidx.compose.ui.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.AnnotatedString
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen

/**
 * The clipboard, reached the way the rest of the desktop reaches it.
 *
 * There is no one clipboard here. X11 keeps the selection in whichever client owns it and
 * Wayland keeps it in the compositor, so a program that wants it asks a helper: `wl-copy`
 * under Wayland, `xclip` or `xsel` under X11. Which of them is installed is the machine's
 * business and not this module's, so each is tried in turn and the first that answers
 * wins.
 *
 * Text only. The helpers can carry more, and anything that wants more should reach past
 * this to whatever the window it belongs to is talking to.
 */
@OptIn(ExperimentalForeignApi::class)
private object Helpers {
    // Wayland first: a session that has both usually wants the Wayland one, because the
    // X11 tools then talk to a compatibility layer rather than to the session itself.
    private val readers = listOf(
        "wl-paste --no-newline",
        "xclip -selection clipboard -o",
        "xsel --clipboard --output",
    )
    private val writers = listOf(
        "wl-copy",
        "xclip -selection clipboard -i",
        "xsel --clipboard --input",
    )

    fun read(): String? {
        for (command in readers) {
            val pipe = popen("$command 2>/dev/null", "r") ?: continue
            val text = buildString {
                val buffer = ByteArray(4096)
                while (true) {
                    val line = fgets(buffer.refTo(0), buffer.size, pipe) ?: break
                    append(line.toKString())
                }
            }
            val status = pclose(pipe)
            if (status == 0 && text.isNotEmpty()) return text
        }
        return null
    }

    fun write(text: String) {
        for (command in writers) {
            val pipe = popen("$command 2>/dev/null", "w") ?: continue
            platform.posix.fputs(text, pipe)
            if (pclose(pipe) == 0) return
        }
    }

    fun clear() = write("")
}

/**
 * What this platform hands back as its own clipboard.
 *
 * The platforms with one object to point at point at it. There is none here, so this is
 * the reader and writer above, and anything wanting the real thing goes through the
 * window's own connection to the display.
 */
actual class NativeClipboard internal constructor() {
    fun readText(): String? = Helpers.read()

    fun writeText(text: String) = Helpers.write(text)

    fun clear() = Helpers.clear()
}

private val theClipboard = NativeClipboard()

private class LinuxPlatformClipboardManager : ClipboardManager {
    override fun getText(): AnnotatedString? = Helpers.read()?.let { AnnotatedString(it) }

    override fun setText(annotatedString: AnnotatedString) = Helpers.write(annotatedString.text)

    override fun hasText(): Boolean = !Helpers.read().isNullOrEmpty()

    override fun getClip(): ClipEntry? = Helpers.read()?.let { ClipEntry.withPlainText(it) }

    @Suppress("GetterSetterNames")
    override fun setClip(clipEntry: ClipEntry?) {
        val text = clipEntry?.plainText
        if (text == null) Helpers.clear() else Helpers.write(text)
    }
}

private class LinuxPlatformClipboard : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? =
        Helpers.read()?.takeIf { it.isNotEmpty() }?.let { ClipEntry.withPlainText(it) }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val text = clipEntry?.plainText
        if (text == null) Helpers.clear() else Helpers.write(text)
    }

    override val nativeClipboard: NativeClipboard
        get() = theClipboard
}

@Suppress("DEPRECATION")
internal actual fun createPlatformClipboardManager(): ClipboardManager =
    LinuxPlatformClipboardManager()

internal actual fun createPlatformClipboard(): Clipboard = LinuxPlatformClipboard()

/**
 * What the clipboard is carrying. Text, for as long as the helpers above are how it is
 * reached; anything else goes through [Clipboard.nativeClipboard].
 */
actual class ClipEntry internal constructor() {

    actual val clipMetadata: ClipMetadata
        get() = TODO("ClipMetadata is not implemented. Consider using nativeClipboard")

    internal var plainText: String? = null

    @ExperimentalComposeUiApi
    fun getPlainText(): String? = plainText

    companion object {
        @ExperimentalComposeUiApi
        fun withPlainText(text: String): ClipEntry = ClipEntry().apply { plainText = text }
    }
}
