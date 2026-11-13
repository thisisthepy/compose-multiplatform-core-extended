@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package org.thisisthepy.compose.window.macos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.cinterop.CValue
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import org.thisisthepy.compose.window.PixelRect
import org.thisisthepy.compose.window.PixelRoundRect
import org.thisisthepy.compose.window.WindowEvent
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSBitmapImageFileType
import platform.AppKit.NSBitmapImageRep
import platform.AppKit.NSColor
import platform.AppKit.NSCursor
import platform.AppKit.NSEvent
import platform.AppKit.NSEventType
import platform.AppKit.NSEventTypeLeftMouseDown
import platform.AppKit.NSEventTypeLeftMouseUp
import platform.AppKit.NSEventTypeMouseMoved
import platform.AppKit.NSView
import platform.AppKit.NSWindow
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSWindowStyleMaskTitled
import platform.AppKit.representationUsingType
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGRect
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeRect
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSTimer
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A plain view of one colour that takes the keyboard when clicked, as a web view does. */
private class SolidView : NSView(NSMakeRect(0.0, 0.0, 10.0, 10.0)) {
    init {
        wantsLayer = true
        layer?.backgroundColor = NSColor.redColor.CGColor
    }

    override fun acceptsFirstResponder() = true

    // Takes the click that also brings the window forward, as a web view does.
    override fun acceptsFirstMouse(event: NSEvent?) = true
}

/**
 * A native view placed in a real window through [MacosNativeViews]: where it shows, what the
 * window's own image shows through a hole in it, where the pointer and the keyboard go, and
 * what the scene is told.
 *
 * The scene is one green colour and the slot's view one red colour, so the window's own image
 * (taken through CGWindowListCreateImage, which needs no permission for the process's own
 * windows) says which one is on top at a pixel. A runner with no screen to capture from skips
 * the image checks and says so; everything else runs there too.
 *
 * `DXC_NATIVE_VIEW_CAPTURE=<file.png>` saves the image the hole check looked at.
 */
class NativeViewSlotTest {
    private class Fixture(val window: MacosWindow, val views: MacosNativeViews, val heard: MutableList<WindowEvent>) {
        val scale: Double get() = window.window.backingScaleFactor
        fun px(points: Int): Int = (points * scale).toInt()
        fun kinds(): List<Int> = heard.map { it.kind }
    }

    private fun turn(seconds: Double) {
        val begun = kotlin.time.TimeSource.Monotonic.markNow()
        while (begun.elapsedNow().inWholeMilliseconds < seconds * 1000) {
            NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.02))
        }
    }

    private fun withSlot(body: (Fixture, SolidView) -> Unit) {
        val application = NSApplication.sharedApplication()
        application.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
        application.finishLaunching()
        application.activateIgnoringOtherApps(true)
        val window = MacosWindow(name = "native view", width = 400, height = 320)
        val timer = NSTimer.timerWithTimeInterval(1.0 / 60.0, repeats = true) { _: NSTimer? -> window.pumpHost(0L) }
        try {
            window.setContent { Box(Modifier.fillMaxSize().background(Color(0xFF00C000))) }
            val heard = ArrayList<WindowEvent>()
            val forward = window.onEvent
            window.onEvent = { event ->
                heard += event
                forward?.invoke(event)
            }
            NSRunLoop.mainRunLoop.addTimer(timer, forMode = NSRunLoopCommonModes)
            window.window.makeKeyAndOrderFront(null)
            turn(0.5)
            val views = window.nativeViews
            val fixture = Fixture(window, views, heard)
            val view = SolidView()
            assertTrue(views.attach(1, view.objcPtr().toLong()), "a plain view can be attached")
            assertFalse(views.attach(2, view.objcPtr().toLong()), "the same view twice cannot")
            assertFalse(views.attach(1, SolidView().objcPtr().toLong()), "the same id twice cannot")
            assertFalse(views.attach(3, 0L), "nor a null handle")
            assertTrue(view.superview != null && view.superview!!.hidden, "attached hidden")
            views.setFrame(1, fixture.px(SLOT_X), fixture.px(SLOT_Y), fixture.px(SLOT_W), fixture.px(SLOT_H))
            views.setVisible(1, true)
            turn(0.3)
            body(fixture, view)
        } finally {
            timer.invalidate()
            window.window.orderOut(null)
        }
    }

    /** A point [x], [y] in content points from the top left, in the window's coordinates. */
    private fun Fixture.inWindow(x: Double, y: Double): CValue<CGPoint> {
        val content = window.window.contentView!!
        val height = content.bounds.useContents { size.height }
        return content.convertPoint(NSMakePoint(x, height - y), toView = null)
    }

    private fun Fixture.mouse(type: NSEventType, x: Double, y: Double): NSEvent =
        NSEvent.mouseEventWithType(
            type = type,
            location = inWindow(x, y),
            modifierFlags = 0u,
            timestamp = NSProcessInfo.processInfo.systemUptime,
            windowNumber = window.window.windowNumber,
            context = null,
            eventNumber = 0L,
            clickCount = 1,
            pressure = if (type == NSEventTypeLeftMouseDown) 1f else 0f,
        )!!

    private fun Fixture.click(x: Double, y: Double) {
        window.window.sendEvent(mouse(NSEventTypeLeftMouseDown, x, y))
        window.window.sendEvent(mouse(NSEventTypeLeftMouseUp, x, y))
    }

    /** What the window's image shows at a point of its content, in points: 'S' scene, 'V' view, '?' neither. */
    private class Image(val pixels: ByteArray, val width: Int, val height: Int, val left: Double, val top: Double, val scale: Double) {
        fun at(x: Int, y: Int): Char {
            val ix = (left + x * scale).toInt()
            val iy = (top + y * scale).toInt()
            if (ix !in 0 until width || iy !in 0 until height) return '?'
            val i = (iy * width + ix) * 4
            val r = pixels[i].toInt() and 0xFF
            val g = pixels[i + 1].toInt() and 0xFF
            val b = pixels[i + 2].toInt() and 0xFF
            return when {
                r > 180 && g < 80 && b < 80 -> 'V'
                g > 140 && r < 80 && b < 80 -> 'S'
                else -> '?'
            }
        }
    }

    private fun Fixture.image(saveTo: String? = null): Image? {
        @Suppress("DEPRECATION")
        val image: CGImageRef = platform.CoreGraphics.CGWindowListCreateImage(
            platform.CoreGraphics.CGRectNull.readValue(),
            1u shl 3,
            window.window.windowNumber.toUInt(),
            (1u shl 0) or (1u shl 3),
        ) ?: return null
        val width = platform.CoreGraphics.CGImageGetWidth(image).toInt()
        val height = platform.CoreGraphics.CGImageGetHeight(image).toInt()
        if (saveTo != null) {
            NSBitmapImageRep(cGImage = image)
                .representationUsingType(NSBitmapImageFileType.NSBitmapImageFileTypePNG, properties = emptyMap<Any?, Any?>())
                ?.writeToFile(saveTo, atomically = true)
        }
        val pixels = ByteArray(width * height * 4)
        pixels.usePinned { pinned ->
            val space = platform.CoreGraphics.CGColorSpaceCreateDeviceRGB()
            val context = platform.CoreGraphics.CGBitmapContextCreate(
                pinned.addressOf(0), width.toULong(), height.toULong(), 8u, (width * 4).toULong(), space,
                platform.CoreGraphics.CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            )
            platform.CoreGraphics.CGContextDrawImage(
                context, platform.CoreGraphics.CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image,
            )
            platform.CoreGraphics.CGContextRelease(context)
            platform.CoreGraphics.CGColorSpaceRelease(space)
        }
        platform.CoreGraphics.CGImageRelease(image)
        val content = window.window.contentView!!
        val rect: CValue<CGRect> = content.convertRect(content.bounds, toView = null)
        val windowHeight = window.window.frame.useContents { size.height }
        val imageScale = width / window.window.frame.useContents { size.width }
        val left = rect.useContents { origin.x } * imageScale
        val top = rect.useContents { windowHeight - (origin.y + size.height) } * imageScale
        val image2 = Image(pixels, width, height, left, top, imageScale)
        // A screen that is locked or absent gives an image with neither colour in it.
        if (image2.at(5, 300) != 'S') return null
        return image2
    }

    @Test
    fun the_view_is_placed_where_set_frame_says_in_points_of_the_backing_scale() = withSlot { f, view ->
        val container = view.superview!!
        val content = f.window.window.contentView!!
        val height = content.bounds.useContents { size.height }
        container.frame.useContents {
            assertEquals(SLOT_X.toDouble(), origin.x, "x: ${f.px(SLOT_X)} pixels at scale ${f.scale}")
            assertEquals(height - SLOT_Y - SLOT_H, origin.y, "y, from the top")
            assertEquals(SLOT_W.toDouble(), size.width)
            assertEquals(SLOT_H.toDouble(), size.height)
        }
        assertSame(content, container.superview, "a sibling of the scene's view in the content view")
        assertEquals(f.views.parentHandle, content.objcPtr().toLong())
        assertTrue(content.subviews.indexOf(container) > content.subviews.indexOf(f.window.view), "above the scene")

        f.views.setFrame(1, f.px(SLOT_X + 10), f.px(SLOT_Y + 5), f.px(SLOT_W - 20), f.px(SLOT_H))
        container.frame.useContents {
            assertEquals((SLOT_X + 10).toDouble(), origin.x)
            assertEquals(height - SLOT_Y - 5 - SLOT_H, origin.y)
            assertEquals((SLOT_W - 20).toDouble(), size.width)
        }
        f.views.setFrame(1, f.px(SLOT_X), f.px(SLOT_Y), f.px(SLOT_W), f.px(SLOT_H))
        turn(0.3)
        val image = f.image()
        if (image == null) {
            println("native view: no image of the window to look at")
            return@withSlot
        }
        assertEquals('V', image.at(SLOT_X + 2, SLOT_Y + 2), "the view's top left corner")
        assertEquals('S', image.at(SLOT_X - 2, SLOT_Y + 2), "left of it the scene")
        assertEquals('S', image.at(SLOT_X + 2, SLOT_Y - 2), "above it the scene")
        assertEquals('V', image.at(SLOT_X + SLOT_W - 2, SLOT_Y + SLOT_H - 2), "its bottom right corner")
        assertEquals('S', image.at(SLOT_X + SLOT_W + 2, SLOT_Y + SLOT_H - 2), "right of it the scene")
    }

    @Test
    fun a_rounded_hole_and_a_clip_show_the_scene_where_they_cut_the_view() = withSlot { f, _ ->
        // A hole at the view's top left corner only, so a mask upside down would show.
        f.views.setVisibleRegion(
            1,
            clip = PixelRect(0, 0, f.px(SLOT_X + SLOT_W - 40), f.px(1000)),
            holes = listOf(PixelRoundRect(f.px(SLOT_X), f.px(SLOT_Y), f.px(80), f.px(60), cornerRadius = f.px(24))),
        )
        turn(0.3)
        val image = f.image(platform.posix.getenv("DXC_NATIVE_VIEW_CAPTURE")?.toKString())
        if (image != null) {
            assertEquals('S', image.at(SLOT_X + 40, SLOT_Y + 30), "the scene in the middle of the hole")
            assertEquals('V', image.at(SLOT_X + 2, SLOT_Y + 2), "the view in the hole's rounded-off corner")
            assertEquals('S', image.at(SLOT_X + 40, SLOT_Y + 2), "the scene along the hole's top edge past the corner")
            assertEquals('V', image.at(SLOT_X + 40, SLOT_Y + SLOT_H - 10), "the view below the hole, not a hole mirrored there")
            assertEquals('V', image.at(SLOT_X + 100, SLOT_Y + 30), "the view right of the hole")
            assertEquals('S', image.at(SLOT_X + SLOT_W - 20, SLOT_Y + 30), "the scene past the clip")
        } else {
            println("native view: no image of the window to look at")
        }
        // The pointer goes the same way: a click in the hole is the scene's, one beside it the view's.
        f.heard.clear()
        f.click(SLOT_X + 40.0, SLOT_Y + 30.0)
        assertTrue(WindowEvent.POINTER_DOWN in f.kinds(), "a click in the hole reaches the scene")
        f.heard.clear()
        f.click(SLOT_X + 100.0, SLOT_Y + 30.0)
        assertFalse(WindowEvent.POINTER_DOWN in f.kinds(), "a click on the view does not: ${f.heard}")
        f.heard.clear()
        f.click(SLOT_X + SLOT_W - 20.0, SLOT_Y + 30.0)
        assertTrue(WindowEvent.POINTER_DOWN in f.kinds(), "a click past the clip reaches the scene")
    }

    @Test
    fun overlapping_holes_are_both_cut_out() = withSlot { f, _ ->
        f.views.setVisibleRegion(
            1,
            clip = null,
            holes = listOf(
                PixelRoundRect(f.px(SLOT_X + 20), f.px(SLOT_Y + 20), f.px(60), f.px(60), cornerRadius = f.px(8)),
                PixelRoundRect(f.px(SLOT_X + 50), f.px(SLOT_Y + 50), f.px(60), f.px(60), cornerRadius = f.px(8)),
            ),
        )
        turn(0.3)
        val image = f.image() ?: return@withSlot println("native view: no image of the window to look at")
        assertEquals('S', image.at(SLOT_X + 65, SLOT_Y + 65), "where the two holes overlap")
        assertEquals('S', image.at(SLOT_X + 30, SLOT_Y + 30), "in the first only")
        assertEquals('S', image.at(SLOT_X + 100, SLOT_Y + 100), "in the second only")
        assertEquals('V', image.at(SLOT_X + 150, SLOT_Y + 30), "outside both")
    }

    @Test
    fun a_hidden_view_is_kept_and_shows_again() = withSlot { f, view ->
        val container = view.superview!!
        f.views.setVisible(1, false)
        assertTrue(container.hidden)
        assertSame(container, view.superview, "the view is kept")
        turn(0.3)
        f.image()?.let { assertEquals('S', it.at(SLOT_X + 50, SLOT_Y + 50), "the scene where the view was") }
        f.heard.clear()
        f.click(SLOT_X + 50.0, SLOT_Y + 50.0)
        assertTrue(WindowEvent.POINTER_DOWN in f.kinds(), "a hidden view takes no click")
        f.views.setVisible(1, true)
        turn(0.3)
        f.image()?.let { assertEquals('V', it.at(SLOT_X + 50, SLOT_Y + 50), "the view again") }
    }

    @Test
    fun a_click_in_the_view_gives_it_the_keyboard_and_focus_scene_takes_it_back() = withSlot { f, view ->
        if (!f.window.window.keyWindow) {
            // No key window to be had (the process is not the active one): the window is told
            // it is key, as AppKit would tell it, so the focus events are reported.
            println("native view: the window cannot become key here; told it is key directly")
            f.window.window.becomeKeyWindow()
        }
        f.heard.clear()
        f.click(SLOT_X + 50.0, SLOT_Y + 50.0)
        assertSame<Any?>(view, f.window.window.firstResponder, "the clicked view is first responder")
        assertEquals(1, f.views.focused)
        assertFalse(WindowEvent.POINTER_DOWN in f.kinds(), "the scene did not get the click: ${f.heard}")
        assertEquals(listOf(WindowEvent.NativeViewFocusIn(1)), f.heard.filter { it.isFocusEvent() })
        f.heard.clear()
        f.views.focusScene()
        assertSame<Any?>(f.window.view, f.window.window.firstResponder, "the scene's view is first responder")
        assertEquals(listOf(WindowEvent.NativeViewFocusOut(1)), f.heard.filter { it.isFocusEvent() })
        f.heard.clear()
        f.views.focus(1)
        assertSame<Any?>(view, f.window.window.firstResponder, "focus(id) gives it back")
        f.heard.clear()
        f.click(SLOT_X - 20.0, SLOT_Y + 50.0)
        assertSame<Any?>(f.window.view, f.window.window.firstResponder, "a click on the scene takes it back")
        assertEquals(listOf(WindowEvent.NativeViewFocusOut(1)), f.heard.filter { it.isFocusEvent() })
    }

    @Test
    fun the_window_losing_and_regaining_key_says_focus_out_and_in() = withSlot { f, view ->
        f.views.focus(1)
        val other = NSWindow(
            contentRect = NSMakeRect(500.0, 500.0, 200.0, 100.0),
            styleMask = NSWindowStyleMaskTitled,
            backing = NSBackingStoreBuffered,
            defer = false,
        )
        other.releasedWhenClosed = false
        try {
            f.window.window.makeKeyAndOrderFront(null)
            turn(0.2)
            val real = f.window.window.keyWindow
            f.heard.clear()
            if (real) {
                other.makeKeyAndOrderFront(null)
                turn(0.2)
            } else {
                // No key window to be had (the process is not the active one): what AppKit
                // calls when it would take it away and give it back.
                println("native view: the window cannot become key here; key changes driven directly")
                f.window.window.becomeKeyWindow()
                f.heard.clear()
                f.window.window.resignKeyWindow()
            }
            assertEquals(listOf(WindowEvent.NativeViewFocusOut(1)), f.heard.filter { it.isFocusEvent() }, "out on deactivate")
            f.heard.clear()
            if (real) {
                f.window.window.makeKeyAndOrderFront(null)
                turn(0.2)
            } else {
                f.window.window.becomeKeyWindow()
            }
            assertEquals(listOf(WindowEvent.NativeViewFocusIn(1)), f.heard.filter { it.isFocusEvent() }, "in on reactivate")
            assertSame<Any?>(view, f.window.window.firstResponder, "the view has the keyboard again")
        } finally {
            other.orderOut(null)
        }
    }

    @Test
    fun hiding_or_detaching_the_focused_view_gives_the_keyboard_back_to_the_scene() = withSlot { f, view ->
        if (!f.window.window.keyWindow) f.window.window.becomeKeyWindow()
        f.views.focus(1)
        f.heard.clear()
        f.views.setVisible(1, false)
        assertEquals(listOf(WindowEvent.NativeViewFocusOut(1)), f.heard.filter { it.isFocusEvent() }, "hidden: focus out")
        assertSame<Any?>(f.window.view, f.window.window.firstResponder, "hidden: the scene")
        assertNull(f.views.focused)
        f.views.setVisible(1, true)
        f.views.focus(1)
        val container = view.superview!!
        f.heard.clear()
        f.views.detach(1)
        assertSame<Any?>(f.window.view, f.window.window.firstResponder, "detached: the scene")
        assertEquals(listOf(WindowEvent.NativeViewFocusOut(1)), f.heard.filter { it.isFocusEvent() }, "detached: focus out")
        assertNull(view.superview, "the view is out of the window")
        assertNull(container.superview, "and so is its container")
        assertNull(f.views.slot(1))
        f.heard.clear()
        f.click(SLOT_X + 50.0, SLOT_Y + 50.0)
        assertTrue(WindowEvent.POINTER_DOWN in f.kinds(), "the scene has the place back")
        assertTrue(f.views.attach(1, view.objcPtr().toLong()), "the view can be attached again")
    }

    @Test
    fun the_pointer_moving_into_the_view_is_an_exit_for_the_scene_and_back_an_enter() = withSlot { f, _ ->
        f.heard.clear()
        val sceneView = f.window.view
        sceneView.mouseMoved(f.mouse(NSEventTypeMouseMoved, SLOT_X - 20.0, SLOT_Y + 50.0))
        sceneView.mouseMoved(f.mouse(NSEventTypeMouseMoved, SLOT_X + 20.0, SLOT_Y + 50.0))
        sceneView.mouseMoved(f.mouse(NSEventTypeMouseMoved, SLOT_X + 40.0, SLOT_Y + 50.0))
        sceneView.mouseMoved(f.mouse(NSEventTypeMouseMoved, SLOT_X - 10.0, SLOT_Y + 50.0))
        assertEquals(
            listOf(WindowEvent.POINTER_MOVE, WindowEvent.POINTER_EXIT, WindowEvent.POINTER_ENTER, WindowEvent.POINTER_MOVE),
            f.kinds().filter { it in POINTER_KINDS },
            "moves over the view are not the scene's; crossing in is an exit and out an enter",
        )
        val exit = f.heard.first { it.kind == WindowEvent.POINTER_EXIT }
        assertEquals((SLOT_X + 20) * f.scale, exit.x.toDouble(), 0.5, "the exit is where the pointer crossed")
    }

    @Test
    fun a_scroll_over_the_view_does_not_reach_the_scene() = withSlot { f, _ ->
        f.heard.clear()
        val scroll = platform.CoreGraphics.CGEventCreateScrollWheelEvent(null, 0u, 1u, -3)
        val location = f.inWindow(SLOT_X + 50.0, SLOT_Y + 50.0)
        val event = NSEvent.eventWithCGEvent(scroll!!)!!
        // A scroll goes to the view under the pointer, found as a click is.
        val content = f.window.window.contentView!!
        val target = content.hitTest(content.superview!!.convertPoint(location, fromView = null))
        assertTrue(target != null && target !== f.window.view, "the view under the pointer is the slot's")
        target!!.scrollWheel(event)
        platform.CoreFoundation.CFRelease(scroll)
        assertFalse(WindowEvent.SCROLL in f.kinds(), "the scene heard no scroll: ${f.heard}")
    }

    @Test
    fun the_scene_does_not_reset_the_cursor_the_view_set() = withSlot { f, _ ->
        val sceneView = f.window.view
        // What a web view does when the pointer is over a link.
        NSCursor.crosshairCursor.set()
        sceneView.mouseMoved(f.mouse(NSEventTypeMouseMoved, SLOT_X + 20.0, SLOT_Y + 50.0))
        sceneView.cursorUpdate(f.mouse(NSEventTypeMouseMoved, SLOT_X + 20.0, SLOT_Y + 50.0))
        sceneView.mouseMoved(f.mouse(NSEventTypeMouseMoved, SLOT_X + 30.0, SLOT_Y + 50.0))
        assertEquals(NSCursor.crosshairCursor, NSCursor.currentCursor, "the view's cursor stays")
        NSCursor.arrowCursor.set()
    }

    private fun WindowEvent.isFocusEvent() =
        kind == WindowEvent.NATIVE_VIEW_FOCUS_IN || kind == WindowEvent.NATIVE_VIEW_FOCUS_OUT

    private companion object {
        // The slot, in content points.
        const val SLOT_X = 60
        const val SLOT_Y = 80
        const val SLOT_W = 220
        const val SLOT_H = 160
        val POINTER_KINDS = setOf(
            WindowEvent.POINTER_MOVE, WindowEvent.POINTER_ENTER, WindowEvent.POINTER_EXIT, WindowEvent.POINTER_DOWN,
        )
    }
}
