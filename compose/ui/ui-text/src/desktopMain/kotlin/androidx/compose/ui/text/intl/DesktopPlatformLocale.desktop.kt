/*
 * Copyright 2020 The Android Open Source Project
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

import java.util.Locale as JavaLocale

internal actual fun createPlatformLocaleDelegate() = object : PlatformLocaleDelegate {
    override val current: LocaleList
        get() = LocaleList(listOf(Locale(JavaLocale.getDefault())))
}

// Same list as java.awt.ComponentOrientation.getOrientation(Locale) in the JDK
// (src/java.desktop/share/classes/java/awt/ComponentOrientation.java): the languages
// "iw", "ar", "fa" and "ur" are right to left, everything else is left to right. That method
// is not called here because loading java.awt.ComponentOrientation pulls in AWT.
// Two additions: since JDK 17 Locale.getLanguage() reports Hebrew as "he" and Yiddish as "yi", which
// the JDK list may not carry under those codes, so they are listed next to "iw" and "ji".
private val rtlLanguages = setOf("iw", "he", "ar", "fa", "ur", "yi", "ji")

internal actual fun Locale.isRtl(): Boolean = platformLocale.language in rtlLanguages
