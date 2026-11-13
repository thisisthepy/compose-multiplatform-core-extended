package org.thisisthepy.compose.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class X11ScaleTest {
    /** What `xrdb -query` prints on a GNOME session at 200%. */
    private val gnome2x = "Gtk/ButtonImages:\t1\nXft.antialias:\t1\nXft.dpi:\t192\nXft.hinting:\t1\nXft.rgba:\trgb\n"

    @Test
    fun readsXftDpiFromAResourceString() {
        assertEquals(192.0, X11Scale.dpiOf(gnome2x))
        assertEquals(2f, X11Scale.scaleOf(gnome2x))
    }

    @Test
    fun fractionalDpiGivesAFractionalScale() {
        assertEquals(1.25f, X11Scale.scaleOf("Xft.dpi: 120\n"))
        assertEquals(1.5f, X11Scale.scaleOf("Xft.dpi:\t144"))
        assertEquals(1.5f, X11Scale.scaleOf("Xft.dpi:\t144.0\n"))
    }

    @Test
    fun noDpiIsAScaleOfOne() {
        assertNull(X11Scale.dpiOf(null))
        assertNull(X11Scale.dpiOf(""))
        assertNull(X11Scale.dpiOf("Xft.antialias:\t1\nXcursor.size:\t24\n"))
        assertEquals(1f, X11Scale.scaleOf(null))
        assertEquals(1f, X11Scale.scaleOf("Xft.antialias:\t1\n"))
    }

    @Test
    fun malformedOrNonPositiveDpiIsIgnored() {
        assertNull(X11Scale.dpiOf("Xft.dpi:\tlarge\n"))
        assertNull(X11Scale.dpiOf("Xft.dpi:\t0\n"))
        assertNull(X11Scale.dpiOf("Xft.dpi:\t-96\n"))
        assertNull(X11Scale.dpiOf("Xft.dpi:\n"))
        assertNull(X11Scale.dpiOf("Xft.dpi 192\n"))
        assertEquals(1f, X11Scale.scaleOf("Xft.dpi:\tNaN\n"))
    }

    @Test
    fun dpiUnderTheBaseIsAScaleOfOne() {
        assertEquals(72.0, X11Scale.dpiOf("Xft.dpi: 72"))
        assertEquals(1f, X11Scale.scaleOf("Xft.dpi: 72"))
        assertEquals(1f, X11Scale.scaleOf("Xft.dpi: 96"))
    }

    @Test
    fun commentsAndOtherResourcesAreSkipped() {
        val text = "! Xft.dpi: 300\nXTerm*dpi: 400\nXft.dpiX: 500\n  Xft.dpi :  168  \n"
        assertEquals(168.0, X11Scale.dpiOf(text))
    }

    @Test
    fun theMostSpecificBindingWins() {
        assertEquals(144.0, X11Scale.dpiOf("*dpi: 120\nXft.dpi: 144\nXft*dpi: 168\n"))
        assertEquals(168.0, X11Scale.dpiOf("*dpi: 120\nXft*dpi: 168\n"))
        assertEquals(120.0, X11Scale.dpiOf("*dpi: 120\n"))
        assertEquals(144.0, X11Scale.dpiOf("Xft.Dpi: 192\nXft.dpi: 144\n"))
        // A usable less specific one answers where the most specific one is not a number.
        assertEquals(120.0, X11Scale.dpiOf("Xft.dpi: none\n*dpi: 120\n"))
    }

    @Test
    fun theLastOfARepeatedNameWins() {
        assertEquals(192.0, X11Scale.dpiOf("Xft.dpi: 96\nXft.dpi: 192\n"))
    }

    @Test
    fun gdkScaleForcesTheScale() {
        assertEquals(2f, X11Scale.scaleOf(null, "2"))
        assertEquals(3f, X11Scale.scaleOf(gnome2x, " 3 "))
        // Unset, below 1 or not a number: Xft.dpi answers.
        assertEquals(2f, X11Scale.scaleOf(gnome2x, ""))
        assertEquals(2f, X11Scale.scaleOf(gnome2x, "0"))
        assertEquals(2f, X11Scale.scaleOf(gnome2x, "big"))
    }
}
