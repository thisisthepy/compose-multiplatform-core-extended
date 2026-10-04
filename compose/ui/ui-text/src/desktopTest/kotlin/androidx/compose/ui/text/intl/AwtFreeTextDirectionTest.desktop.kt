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

package androidx.compose.ui.text.intl

import java.awt.ComponentOrientation
import java.util.Locale as JavaLocale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// This test loads java.awt on purpose, to compare. The no-AWT probe never runs it.
// Hebrew is left out of the comparison: the JDK list misses "he" on JDK 17 and later.
class AwtFreeTextDirectionTest {
    private val locales = listOf(
        "en", "en-US", "fr", "de", "ja", "zh-CN", "ko", "ru", "hi", "th", "tr",
        "ar", "ar-EG", "fa", "fa-IR", "ur", "ur-PK",
        "yi", "ps", "sd", "ug", "dv", "ku", "ku-Arab", "es-419", "pt-BR",
    )

    @Test
    fun rtl_matches_ComponentOrientation_for_every_locale() {
        for (tag in locales) {
            val java = JavaLocale.forLanguageTag(tag)
            val expected = !ComponentOrientation.getOrientation(java).isLeftToRight
            assertEquals(expected, Locale(java).isRtl(), "locale $tag")
        }
    }

    @Test
    fun hebrew_is_rtl_under_both_language_codes() {
        assertTrue(Locale(JavaLocale("iw", "IL")).isRtl())
        assertTrue(Locale(JavaLocale("he", "IL")).isRtl())
    }
}
