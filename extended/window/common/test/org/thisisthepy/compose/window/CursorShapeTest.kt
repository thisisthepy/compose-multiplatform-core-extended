package org.thisisthepy.compose.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * FR-47.1: the `Cursor` property's 36 CSS keywords, numbered as on the wire, each with a shape
 * on every desktop window (Windows, X11, AppKit). A platform without the shape takes the
 * closest one; a keyword the table forgot would be drawn as the arrow, which this fails on.
 */
class CursorShapeTest {

    @Test
    fun fr47_cursor_values_are_numbered_as_on_the_wire() {
        assertEquals(36, CursorShape.entries.size)
        assertEquals(CursorShape.Auto, CursorShape.fromWire(0))
        assertEquals(CursorShape.Pointer, CursorShape.fromWire(2))
        assertEquals(CursorShape.NotAllowed, CursorShape.fromWire(9))
        assertEquals(CursorShape.ColResize, CursorShape.fromWire(21))
        assertEquals(CursorShape.NwseResize, CursorShape.fromWire(34))
        assertEquals(CursorShape.None, CursorShape.fromWire(35))
        assertEquals(null, CursorShape.fromWire(36), "an unknown value is not a shape")
        assertEquals(null, CursorShape.fromWire(-1))
    }

    @Test
    fun fr47_every_cursor_value_has_a_platform_mapping() {
        // Only the keywords that are the arrow everywhere may map to the arrow.
        val arrowByRight = setOf(CursorShape.Auto, CursorShape.Default)
        for (shape in CursorShape.entries) {
            if (shape == CursorShape.None) continue
            assertTrue(shape.win32Idc > 0, "$shape has no Windows cursor")
            assertTrue(shape.x11Font >= 0, "$shape has no X11 cursor")
            assertTrue(shape.appKit != AppKitCursor.Hidden, "$shape has no AppKit cursor")
        }
        // The shapes every platform has are not the arrow.
        val distinct = listOf(
            CursorShape.Pointer, CursorShape.Text, CursorShape.Crosshair, CursorShape.NotAllowed,
            CursorShape.ColResize, CursorShape.RowResize, CursorShape.EwResize, CursorShape.NsResize,
        )
        for (shape in distinct) {
            assertTrue(shape !in arrowByRight)
            assertNotEquals(CursorShape.Default.win32Idc, shape.win32Idc, "$shape is the arrow on Windows")
            assertNotEquals(CursorShape.Default.x11Font, shape.x11Font, "$shape is the arrow on X11")
            assertNotEquals(CursorShape.Default.appKit, shape.appKit, "$shape is the arrow on AppKit")
        }
    }

    @Test
    fun fr47_windows_takes_the_closest_shape_the_spec_names() {
        assertEquals(Win32Idc.CROSS, CursorShape.ZoomIn.win32Idc, "Windows has no magnifier: crosshair")
        assertEquals(Win32Idc.HAND, CursorShape.Grab.win32Idc, "grab is the hand")
        assertEquals(Win32Idc.HAND, CursorShape.Grabbing.win32Idc, "grabbing is the hand")
        assertEquals(Win32Idc.NO, CursorShape.NotAllowed.win32Idc)
        assertEquals(Win32Idc.WAIT, CursorShape.Wait.win32Idc)
        assertEquals(Win32Idc.APPSTARTING, CursorShape.Progress.win32Idc)
        assertEquals(Win32Idc.SIZENWSE, CursorShape.NwseResize.win32Idc)
        assertEquals(Win32Idc.SIZENESW, CursorShape.NeswResize.win32Idc)
        assertEquals(Win32Idc.SIZEALL, CursorShape.Move.win32Idc)
    }

    @Test
    fun fr47_none_hides_the_pointer_everywhere() {
        assertEquals(0, CursorShape.None.win32Idc)
        assertEquals(-1, CursorShape.None.x11Font)
        assertEquals(AppKitCursor.Hidden, CursorShape.None.appKit)
    }
}
