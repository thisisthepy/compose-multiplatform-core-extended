@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import kotlinx.cinterop.useContents
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.ElementRole
import platform.AppKit.NSApplication
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSView
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowStyleMaskTitled
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSMakeRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * A control's accessibility frame is where it is drawn. AppKit takes the frame in screen
 * points from the bottom left of the primary display with y counting up; the scene places
 * controls from the top left of the window with y counting down. One flip too few or too many
 * puts a control at the top of the window at its bottom, as far from the bottom edge as it is
 * drawn from the top (DarkPyonix/compose-rust#937).
 */
class AccessibilityFrameTest {
    private val top = AccessibleElement(ElementRole.BUTTON, 10f, 0f, 80f, 30f, "Top", id = 1)
    private val bottom = AccessibleElement(ElementRole.BUTTON, 10f, 270f, 80f, 30f, "Bottom", id = 2)

    @Test
    fun a_control_at_the_top_of_the_content_ends_at_the_contents_top_edge() {
        // Content 400x300 whose bottom left is at 100,100: its top edge is at 400.
        assertEquals(AxScreenRect(110.0, 370.0, 80.0, 30.0), axScreenRect(10.0, 0.0, 80.0, 30.0, 100.0, 100.0, 300.0))
    }

    @Test
    fun a_control_at_the_bottom_of_the_content_starts_at_the_contents_bottom_edge() {
        assertEquals(AxScreenRect(110.0, 100.0, 80.0, 30.0), axScreenRect(10.0, 270.0, 80.0, 30.0, 100.0, 100.0, 300.0))
    }

    @Test
    fun a_window_on_a_display_above_or_below_the_primary_one_keeps_its_controls_in_place() {
        // Above the primary display AppKit's y is past the primary's height; below it, negative.
        assertEquals(AxScreenRect(110.0, 1470.0, 80.0, 30.0), axScreenRect(10.0, 0.0, 80.0, 30.0, 100.0, 1200.0, 300.0))
        assertEquals(AxScreenRect(110.0, -900.0, 80.0, 30.0), axScreenRect(10.0, 270.0, 80.0, 30.0, 100.0, -900.0, 300.0))
    }

    @Test
    fun the_button_in_the_issue_reads_where_it_is_drawn() {
        // The window of #937: 1440x900 at 560,135 from the top left of a primary display 1440
        // tall, so AppKit puts its bottom at 1440 - 1035 = 405. The button is drawn 134,10
        // inside it, 117x32. A reader's AXPosition is the primary's height less the frame's top.
        val primaryHeight = 1440.0
        val frame = axScreenRect(134.0, 10.0, 117.0, 32.0, 560.0, primaryHeight - 1035.0, 900.0)
        assertEquals(694.0, frame.x)
        assertEquals(145.0, primaryHeight - (frame.y + frame.height), "drawn 10 under the window's top at 135")
    }

    private fun window(content: NSView): NSWindow =
        NSWindow(
            contentRect = NSMakeRect(100.0, 100.0, 400.0, 300.0),
            styleMask = NSWindowStyleMaskTitled,
            backing = NSBackingStoreBuffered,
            defer = true,
        ).also { it.contentView = content }

    private fun bottomLeftOf(element: AxElement): Pair<Double, Double> =
        element.accessibilityFrame().useContents { origin.x to origin.y }

    @Test
    fun an_unflipped_host_view_still_counts_the_scenes_y_down_from_the_top() {
        // The Kotlin/Native window hands its backdrop, an unflipped NSVisualEffectView, to the
        // tree. Its content is 400x300 at 100,100 on the screen.
        val view = NSView(NSMakeRect(0.0, 0.0, 400.0, 300.0))
        val window = window(view)
        val tree = AppKitAccessibilityTree(view) { _, _, _ -> true }
        tree.update(listOf(top, bottom))
        val content = window.convertRectToScreen(view.convertRect(view.bounds, toView = null))
        val (left, base) = content.useContents { origin.x to origin.y }
        assertEquals(left + 10.0 to base + 270.0, bottomLeftOf(tree.all.first { it.element.id == 1 }), "top")
        assertEquals(left + 10.0 to base, bottomLeftOf(tree.all.first { it.element.id == 2 }), "bottom")
    }

    @Test
    fun the_windows_reader_frames_are_where_its_view_draws_them() {
        // The window's own tree, against where its flipped drawing view puts the same
        // rectangles on the screen. The backdrop becomes the content view and the drawing view
        // its subview only in initWindow (setContent calls it); before that neither is in the
        // window, and the two sides of this comparison would be in different coordinates.
        NSApplication.sharedApplication()
        val window = MacosWindow(name = "ax frames", width = 400, height = 300)
        try {
            window.initWindow()
            assertSame(window.window, window.view.window, "the drawing view is in the window")
            assertSame(window.view.superview, window.window.contentView, "and inside the backdrop")
            window.describeFrom(listOf(top, bottom))
            for (element in listOf(top, bottom)) {
                val local = CGRectMake(
                    element.x.toDouble(),
                    element.y.toDouble(),
                    element.width.toDouble(),
                    element.height.toDouble(),
                )
                val drawn = window.window.convertRectToScreen(window.view.convertRect(local, toView = null))
                    .useContents { origin.x to origin.y }
                val node = window.reader.all.first { it.element.id == element.id }
                assertEquals(drawn, bottomLeftOf(node), element.label)
            }
        } finally {
            window.close()
        }
    }
}
