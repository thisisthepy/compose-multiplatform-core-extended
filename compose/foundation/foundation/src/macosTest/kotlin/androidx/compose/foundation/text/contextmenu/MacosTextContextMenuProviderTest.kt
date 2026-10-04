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

@file:OptIn(ExperimentalForeignApi::class, ExperimentalTestApi::class)

package androidx.compose.foundation.text.contextmenu

import androidx.compose.foundation.text.contextmenu.internal.ProvideDefaultPlatformTextContextMenuProviders
import androidx.compose.foundation.text.contextmenu.internal.menuLocation
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuDropdownProvider
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runSkikoComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowStyleMaskTitled
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake

class MacosTextContextMenuProviderTest {
    private object AppProvider : TextContextMenuProvider {
        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) = Unit
    }

    @Test
    fun fr33_override_provider_wins_over_the_system_menu() = runSkikoComposeUiTest {
        var seen: TextContextMenuProvider? = null
        setContent {
            CompositionLocalProvider(LocalTextContextMenuDropdownProvider provides AppProvider) {
                ProvideDefaultPlatformTextContextMenuProviders(Modifier) {
                    seen = LocalTextContextMenuDropdownProvider.current
                }
            }
        }
        waitForIdle()
        assertSame(AppProvider, seen)
    }

    @Test
    fun fr33_toolbar_override_leaves_no_system_menu() = runSkikoComposeUiTest {
        var dropdown: TextContextMenuProvider? = null
        var toolbar: TextContextMenuProvider? = null
        setContent {
            CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides AppProvider) {
                ProvideDefaultPlatformTextContextMenuProviders(Modifier) {
                    dropdown = LocalTextContextMenuDropdownProvider.current
                    toolbar = LocalTextContextMenuToolbarProvider.current
                }
            }
        }
        waitForIdle()
        assertSame(AppProvider, toolbar)
        assertNull(dropdown)
    }

    @Test
    fun fr33_system_menu_is_the_default_without_an_override() = runSkikoComposeUiTest {
        var seen: TextContextMenuProvider? = null
        setContent {
            ProvideDefaultPlatformTextContextMenuProviders(Modifier) {
                seen = LocalTextContextMenuDropdownProvider.current
            }
        }
        waitForIdle()
        assertNotNull(seen)
        assertNotSame(AppProvider, seen)
    }

    @Test
    fun fr33_menu_location_equals_the_pointer() {
        val window = NSWindow(
            contentRect = CGRectMake(100.0, 100.0, 400.0, 300.0),
            styleMask = NSWindowStyleMaskTitled,
            backing = NSBackingStoreBuffered,
            defer = true,
        )
        val view = window.contentView!!
        val onScreen = CGPointMake(150.0, 250.0)
        // The content view starts at the content rect's corner, so the pointer is 50 right of
        // and 150 above it.
        menuLocation(onScreen, view).useContents {
            assertEquals(50.0, x)
            assertEquals(150.0, y)
        }
    }
}
