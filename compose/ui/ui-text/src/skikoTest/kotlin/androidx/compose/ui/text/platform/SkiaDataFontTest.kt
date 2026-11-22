/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.compose.ui.text.platform

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.jetbrains.skia.Data

class SkiaDataFontTest {

    @Test
    fun aFontMadeFromSkiaDataLoadsFromThatData() {
        val data = Data.makeFromBytes(byteArrayOf(1, 2, 3))
        val font = Font("shared", data, FontWeight.Bold, FontStyle.Italic) as LoadedFont
        // The typeface is made from the same data, not from a copy of it.
        assertSame(data, font.skiaData())
        assertContentEquals(byteArrayOf(1, 2, 3), font.data)
        assertEquals(FontWeight.Bold, font.weight)
        assertEquals(FontStyle.Italic, font.style)
    }

    @Test
    fun aFontMadeFromBytesStillLoadsFromACopyOfThem() {
        val font = Font("copied", byteArrayOf(1, 2, 3)) as LoadedFont
        assertContentEquals(byteArrayOf(1, 2, 3), font.skiaData().bytes)
    }
}
