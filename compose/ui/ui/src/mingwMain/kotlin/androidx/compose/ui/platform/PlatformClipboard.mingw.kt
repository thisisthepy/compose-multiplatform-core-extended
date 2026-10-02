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
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKStringFromUtf16
import platform.windows.CF_UNICODETEXT
import platform.windows.CloseClipboard
import platform.windows.EmptyClipboard
import platform.windows.GMEM_MOVEABLE
import platform.windows.GetClipboardData
import platform.windows.GlobalAlloc
import platform.windows.GlobalFree
import platform.windows.GlobalLock
import platform.windows.GlobalUnlock
import platform.windows.IsClipboardFormatAvailable
import platform.windows.OpenClipboard
import platform.windows.SetClipboardData

/**
 * The clipboard, as Windows keeps it: one per session, text in UTF-16.
 *
 * Text only. The clipboard can carry more, and anything that wants more should reach past
 * this to the window it belongs to.
 */
@OptIn(ExperimentalForeignApi::class)
private object Helpers {
    private val unicodeText = CF_UNICODETEXT.toUInt()

    fun read(): String? {
        if (IsClipboardFormatAvailable(unicodeText) == 0) return null
        // Another program can hold the clipboard open; nothing to read until it lets go.
        if (OpenClipboard(null) == 0) return null
        try {
            val handle = GetClipboardData(unicodeText) ?: return null
            val text = GlobalLock(handle)?.reinterpret<UShortVar>() ?: return null
            try {
                return text.toKStringFromUtf16()
            } finally {
                GlobalUnlock(handle)
            }
        } finally {
            CloseClipboard()
        }
    }

    fun write(text: String) {
        if (OpenClipboard(null) == 0) return
        try {
            EmptyClipboard()
            if (text.isEmpty()) return
            // Moveable memory, terminated, handed over: once SetClipboardData succeeds the
            // system owns it and freeing it here would free the clipboard's copy.
            val units = text.length + 1
            val handle = GlobalAlloc(GMEM_MOVEABLE.toUInt(), (units * 2).toULong()) ?: return
            val target = GlobalLock(handle)?.reinterpret<UShortVar>()
            if (target == null) {
                GlobalFree(handle)
                return
            }
            for (index in text.indices) target[index] = text[index].code.toUShort()
            target[text.length] = 0u
            GlobalUnlock(handle)
            if (SetClipboardData(unicodeText, handle) == null) GlobalFree(handle)
        } finally {
            CloseClipboard()
        }
    }

    fun clear() = write("")
}

/**
 * What this platform hands back as its own clipboard.
 *
 * Windows has no clipboard object to hand back; the clipboard is reached through calls,
 * so this is the reader and writer above.
 */
actual class NativeClipboard internal constructor() {
    fun readText(): String? = Helpers.read()

    fun writeText(text: String) = Helpers.write(text)

    fun clear() = Helpers.clear()
}

private val theClipboard = NativeClipboard()

private class WindowsPlatformClipboardManager : ClipboardManager {
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

private class WindowsPlatformClipboard : Clipboard {
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
    WindowsPlatformClipboardManager()

internal actual fun createPlatformClipboard(): Clipboard = WindowsPlatformClipboard()

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
