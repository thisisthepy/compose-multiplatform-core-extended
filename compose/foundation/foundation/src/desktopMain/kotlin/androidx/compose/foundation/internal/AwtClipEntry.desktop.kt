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

import androidx.annotation.VisibleForTesting
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.platform.awtClipboard
import androidx.compose.ui.text.AnnotatedString
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Everything that touches java.awt.datatransfer lives in this file, so the class holding the
// plain-text path (ClipboardUtils.desktop.kt) never has to load a Transferable or DataFlavor.

// This implementation detail is used by Jewel.
// When removing it, please provide an alternative of retrieving an annotated string,
// and notify a Jewel developer that they need to change the implementation.
private val annotatedStringFlavor: DataFlavor by lazy(LazyThreadSafetyMode.PUBLICATION) {
    DataFlavor(AnnotatedString::class.java, "AnnotatedString")
}

internal suspend fun ClipEntry.readTransferableText(): String? {
    if (!hasTransferableText()) return null

    val transferable = asAwtTransferable
    return withContext(Dispatchers.IO) {
        try {
            transferable?.getTransferData(DataFlavor.stringFlavor) as? String
        } catch (_: IOException) {
            // the data is no longer available in the requested flavor
            null
        }
    }
}

internal suspend fun ClipEntry.readTransferableAnnotatedString(): AnnotatedString? {
    if (!hasTransferableAnnotatedString()) {
        if (!hasTransferableText()) return null
        return readTransferableText()?.let { AnnotatedString(it) }
    }

    val transferable = asAwtTransferable
    return withContext(Dispatchers.IO) {
        try {
            transferable?.getTransferData(annotatedStringFlavor) as? AnnotatedString
        } catch (_: IOException) {
            // the data is no longer available in the requested flavor
            null
        }
    }
}

internal fun AnnotatedString.toTransferableClipEntry(): ClipEntry =
    ClipEntry(AnnotatedStringTransferable(this))

internal fun ClipEntry.hasTransferableAnnotatedString(): Boolean {
    val transferable = asAwtTransferable ?: return false
    return transferable.isDataFlavorSupported(annotatedStringFlavor)
}

internal fun ClipEntry.hasTransferableText(): Boolean {
    val transferable = asAwtTransferable ?: return false
    return transferable.isDataFlavorSupported(DataFlavor.stringFlavor)
}

// Here we rely on the NativeClipboard directly instead of using ClipEntry,
// because getClipEntry is a suspend function, but in ContextMenu.desktop.kt we have older code
// expecting a synchronous execution.
internal fun Clipboard.awtNativeClipboardHasText(): Boolean {
    val awtClipboard = awtClipboard ?: return false
    return awtClipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)
}

internal fun Clipboard.awtNativeClipboardHasData(): Boolean =
    awtClipboard?.availableDataFlavors?.isNotEmpty() ?: false

// Derived from StringSelection
@VisibleForTesting
internal class AnnotatedStringTransferable(
    private val data: AnnotatedString
) : Transferable, ClipboardOwner {
    override fun getTransferDataFlavors(): Array<DataFlavor?> = supportedFlavors

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
        flavor in supportedFlavors

    override fun getTransferData(flavor: DataFlavor): Any =
        when (flavor) {
            annotatedStringFlavor -> data
            DataFlavor.stringFlavor -> data.text
            else -> throw UnsupportedFlavorException(flavor)
        }

    override fun lostOwnership(clipboard: java.awt.datatransfer.Clipboard?, contents: Transferable?) {
        // Empty
    }

    companion object {
        private val supportedFlavors = arrayOf(annotatedStringFlavor, DataFlavor.stringFlavor)
    }
}
