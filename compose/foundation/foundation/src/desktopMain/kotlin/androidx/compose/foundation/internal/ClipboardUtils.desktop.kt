/*
 * Copyright 2025 The Android Open Source Project
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

@file:OptIn(ExperimentalComposeUiApi::class)

package androidx.compose.foundation.internal

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString

// A plain-text ClipEntry is answered here. Anything else is handed to AwtClipEntry.desktop.kt,
// the only file that loads java.awt.datatransfer classes.

internal actual suspend fun ClipEntry.readText(): String? =
    plainTextOrNull() ?: readTransferableText()

internal actual suspend fun ClipEntry.readAnnotatedString(): AnnotatedString? =
    plainTextOrNull()?.let { AnnotatedString(it) } ?: readTransferableAnnotatedString()

internal actual fun AnnotatedString?.toClipEntry(): ClipEntry? {
    if (this == null) return null
    return toTransferableClipEntry()
}

internal fun ClipEntry?.hasAnnotatedString(): Boolean {
    if (this == null) return false
    if (isPlainText()) return false
    return hasTransferableAnnotatedString()
}

internal actual fun ClipEntry?.hasText(): Boolean {
    if (this == null) return false
    if (isPlainText()) return true
    return hasTransferableText()
}

internal actual fun Clipboard.isReadSupported(): Boolean = true
internal actual fun Clipboard.isWriteSupported(): Boolean = true

// True only for the AWT system clipboard. The superclass chain is compared by name, so asking
// an embedder's clipboard never loads a java.awt.datatransfer class.
private fun Any.isAwtClipboard(): Boolean {
    var type: Class<*>? = javaClass
    while (type != null) {
        if (type.name == "java.awt.datatransfer.Clipboard") return true
        type = type.superclass
    }
    return false
}

// Here we rely on the NativeClipboard directly instead of using ClipEntry,
// because getClipEntry is a suspend function, but in ContextMenu.desktop.kt we have older code
// expecting a synchronous execution. An embedder clipboard whose nativeClipboard is a String
// exposes its current plain text there; any other non-AWT value reports no text.
internal fun Clipboard.nativeClipboardHasText(): Boolean {
    val native = nativeClipboard
    if (native is String) return native.isNotEmpty()
    if (!native.isAwtClipboard()) return false
    return awtNativeClipboardHasText()
}

/** Whether the clipboard holds anything, read through getClipEntry for a non-AWT clipboard. */
internal suspend fun Clipboard.hasAnyData(): Boolean {
    if (!nativeClipboard.isAwtClipboard()) return getClipEntry() != null
    return awtNativeClipboardHasData()
}
