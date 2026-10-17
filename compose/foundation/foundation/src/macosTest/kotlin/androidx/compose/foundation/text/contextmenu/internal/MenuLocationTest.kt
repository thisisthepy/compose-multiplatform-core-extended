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

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package androidx.compose.foundation.text.contextmenu.internal

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.cinterop.useContents
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSView
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowStyleMaskBorderless
import platform.CoreGraphics.CGPointMake
import platform.Foundation.NSMakeRect

class MenuLocationTest {

    private class FlippedView : NSView(NSMakeRect(0.0, 0.0, 400.0, 300.0)) {
        override fun isFlipped() = true
    }

    private fun windowAt(x: Double, y: Double, content: NSView): NSWindow {
        val window = NSWindow(
            contentRect = NSMakeRect(x, y, 400.0, 300.0),
            styleMask = NSWindowStyleMaskBorderless,
            backing = NSBackingStoreBuffered,
            defer = true,
        )
        window.contentView = content
        return window
    }

    @Test
    fun the_menu_opens_at_the_pointer_in_a_view_that_counts_upward() {
        val view = NSView(NSMakeRect(0.0, 0.0, 400.0, 300.0))
        windowAt(100.0, 200.0, view)
        // 40 points in from the window's left, 60 up from its bottom.
        val where = menuLocationInView(view, CGPointMake(140.0, 260.0), Offset.Zero)
        where.useContents {
            assertEquals(40.0, x)
            assertEquals(60.0, y)
        }
    }

    @Test
    fun the_menu_opens_at_the_pointer_in_a_flipped_view() {
        val view = FlippedView()
        windowAt(100.0, 200.0, view)
        // 60 up from the bottom of a 300 point window is 240 down from its top.
        val where = menuLocationInView(view, CGPointMake(140.0, 260.0), Offset.Zero)
        where.useContents {
            assertEquals(40.0, x)
            assertEquals(240.0, y)
        }
    }

    @Test
    fun a_view_in_no_window_falls_back_to_the_position_it_was_given() {
        val where = menuLocationInView(
            NSView(NSMakeRect(0.0, 0.0, 400.0, 300.0)),
            CGPointMake(0.0, 0.0),
            Offset(10f, 20f),
        )
        where.useContents {
            assertEquals(10.0, x)
            assertEquals(280.0, y)
        }
    }
}
