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

package androidx.compose.ui.text.platform

import androidx.compose.ui.text.PlatformStringDelegate
import androidx.compose.ui.text.intl.Locale

/**
 * Changing the case of a string, without a locale library to ask.
 *
 * The platforms that have one hand the locale to it, because a few languages disagree with
 * the default: Turkish dots its capital i, and Lithuanian keeps the dot on a lowercase one.
 * Kotlin's own case mapping is the Unicode default and knows none of that, so those
 * languages are wrong here in the same way they are wrong anywhere that does not special
 * case them. Everything else is right.
 */
internal class LinuxStringDelegate : PlatformStringDelegate {
    override fun toUpperCase(string: String, locale: Locale): String = string.uppercase()

    override fun toLowerCase(string: String, locale: Locale): String = string.lowercase()

    override fun capitalize(string: String, locale: Locale): String =
        string.replaceFirstChar { if (it.isLowerCase()) it.uppercase() else it.toString() }

    override fun decapitalize(string: String, locale: Locale): String =
        string.replaceFirstChar { it.lowercase() }
}

internal actual fun ActualStringDelegate(): PlatformStringDelegate = LinuxStringDelegate()
