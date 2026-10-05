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

package androidx.compose.foundation.text.modifiers

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A new maximum width lays text out again only where the width it is laid out at changes:
 * text narrower than both the old and the new maximum keeps the paragraph it has.
 */
class LayoutCacheWidthChangeTest {
    private val density = Density(1f)
    private val style = TextStyle(fontSize = 14.sp)
    private val resolver = createFontFamilyResolver()

    private fun simple(text: String) =
        ParagraphLayoutCache(text, style, resolver).also { it.density = density }

    private fun multi(text: String) =
        MultiParagraphLayoutCache(AnnotatedString(text), style, resolver).also { it.density = density }

    @Test
    fun shortTextKeepsItsParagraphWhenTheMaximumGrows() {
        val cache = simple("Short")
        assertTrue(cache.layoutWithConstraints(Constraints(maxWidth = 1000), LayoutDirection.Ltr))
        val first = cache.paragraph
        val size = cache.layoutSize
        assertFalse(cache.layoutWithConstraints(Constraints(maxWidth = 1200), LayoutDirection.Ltr))
        assertSame(first, cache.paragraph)
        assertEquals(size, cache.layoutSize)
        assertFalse(cache.layoutWithConstraints(Constraints(maxWidth = 900), LayoutDirection.Ltr))
        assertSame(first, cache.paragraph)
    }

    @Test
    fun textWiderThanTheNewMaximumIsLaidOutAgain() {
        val cache = simple("A sentence long enough to wrap at a narrow width")
        cache.layoutWithConstraints(Constraints(maxWidth = 2000), LayoutDirection.Ltr)
        val first = cache.paragraph
        assertTrue(cache.layoutWithConstraints(Constraints(maxWidth = 40), LayoutDirection.Ltr))
        assertNotSame(first, cache.paragraph)
    }

    @Test
    fun aFixedWidthThatChangesIsLaidOutAgain() {
        val cache = simple("Short")
        cache.layoutWithConstraints(Constraints.fixedWidth(500), LayoutDirection.Ltr)
        val first = cache.paragraph
        assertTrue(cache.layoutWithConstraints(Constraints.fixedWidth(600), LayoutDirection.Ltr))
        assertNotSame(first, cache.paragraph)
        assertEquals(600, cache.layoutSize.width)
    }

    @Test
    fun annotatedShortTextKeepsItsParagraphWhenTheMaximumGrows() {
        val cache = multi("Short")
        cache.layoutWithConstraints(Constraints(maxWidth = 1000), LayoutDirection.Ltr)
        val first = cache.textLayoutResult.multiParagraph
        val width = cache.textLayoutResult.size.width
        cache.layoutWithConstraints(Constraints(maxWidth = 1200), LayoutDirection.Ltr)
        assertSame(first, cache.textLayoutResult.multiParagraph)
        assertEquals(width, cache.textLayoutResult.size.width)
        assertEquals(Constraints(maxWidth = 1200), cache.textLayoutResult.layoutInput.constraints)
    }

    @Test
    fun annotatedTextWiderThanTheNewMaximumIsLaidOutAgain() {
        val cache = multi("A sentence long enough to wrap at a narrow width")
        cache.layoutWithConstraints(Constraints(maxWidth = 2000), LayoutDirection.Ltr)
        val first = cache.textLayoutResult.multiParagraph
        cache.layoutWithConstraints(Constraints(maxWidth = 40), LayoutDirection.Ltr)
        assertNotSame(first, cache.textLayoutResult.multiParagraph)
    }
}
