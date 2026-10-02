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

package androidx.compose.ui.text.intl

import androidx.compose.runtime.Immutable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * A language tag, as the environment gives it.
 *
 * There is no system-wide object to ask here the way there is on the platforms that have
 * one. What a Linux desktop has is the locale environment variables, which is what every
 * other program on the machine reads, so it is what this reads.
 */
@Deprecated(
    message = "Use the language tag directly instead",
    replaceWith = ReplaceWith("kotlin.String"),
)
typealias PlatformLocale = String

@OptIn(ExperimentalForeignApi::class)
private fun environmentLanguageTag(): String {
    // In the order the C library reads them: an override, then the one for messages,
    // then the general setting. "C" and "POSIX" mean no locale was chosen.
    for (name in listOf("LC_ALL", "LC_MESSAGES", "LANG")) {
        val value = getenv(name)?.toKString() ?: continue
        val tag = value.substringBefore('.').substringBefore('@').replace('_', '-')
        if (tag.isNotEmpty() && tag != "C" && tag != "POSIX") return tag
    }
    return "en-US"
}

internal actual fun createPlatformLocaleDelegate(): PlatformLocaleDelegate =
    object : PlatformLocaleDelegate {
        override val current: LocaleList
            get() = LocaleList(listOf(Locale(environmentLanguageTag())))
    }

@Immutable
actual class Locale actual constructor(languageTag: String) {
    // The tag is the whole of what this platform has: there is no object to hold beside
    // it, so the two are one field rather than a field and a copy of it.
    internal val platformLocale: String = languageTag

    actual val language: String
        get() = platformLocale.substringBefore('-')

    // A four letter subtag is a script, which is the only shape one can have. Anything
    // shorter is a region and anything longer is a variant, and neither is asked for here.
    actual val script: String
        get() = platformLocale.split('-').firstOrNull { it.length == 4 } ?: ""

    actual val region: String
        get() = platformLocale.split('-').drop(1)
            .firstOrNull { part -> part.length == 2 && part.all { it.isUpperCase() } }
            ?: "US"

    actual fun toLanguageTag(): String = platformLocale

    actual override operator fun equals(other: Any?): Boolean {
        if (other == null) return false
        if (other !is Locale) return false
        if (this === other) return true
        return toLanguageTag() == other.toLanguageTag()
    }

    actual override fun hashCode(): Int = toLanguageTag().hashCode()

    actual override fun toString(): String = toLanguageTag()

    actual companion object {
        actual val current: Locale
            get() = platformLocaleDelegate.current[0]
    }

}

// The languages written right to left, by their language subtag. A short list rather than
// a table lookup, because there is no table to look in without a locale library and these
// are all of them.
private val rightToLeft = setOf("ar", "dv", "fa", "he", "iw", "ps", "sd", "ug", "ur", "yi")

internal actual fun Locale.isRtl(): Boolean = language in rightToLeft
