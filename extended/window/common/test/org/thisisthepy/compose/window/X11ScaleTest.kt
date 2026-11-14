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

    /**
     * What GNOME's settings daemon writes: 96 x the window scaling factor x
     * `text-scaling-factor`, cut to a whole DPI, with `gsettings` printing the factor.
     */
    private fun gnome(windowScale: Int, textFactor: String): Pair<String, Float?> {
        val dpi = (96.0 * windowScale * textFactor.toDouble() * 1024).toLong() / 1024
        return "Xft.antialias:\t1\nXft.dpi:\t$dpi\n" to TextScale.fromGnome(textFactor)
    }

    /** The scale of the window and of its text, as the X11 windows report them. */
    private fun scales(resources: String?, gnomeOutput: String?, gdkScale: String? = null): Pair<Float, Float> {
        val gnomeText = TextScale.fromGnome(gnomeOutput)
        val text = TextScale.resolveLinux(gnomeOutput, null)
        return X11Scale.scaleOf(resources, gdkScale, gnomeText) to text
    }

    @Test
    fun gnomeAt100PercentWithLargerTextScalesOnlyTheText() {
        val (resources, text) = gnome(1, "1.25")
        assertEquals("Xft.antialias:\t1\nXft.dpi:\t120\n", resources)
        assertEquals(1f, X11Scale.scaleOf(resources, null, text))
        assertEquals(1f to 1.25f, scales(resources, "1.25"))
    }

    @Test
    fun gnomeAt200PercentWithDefaultTextScalesTheWindow() {
        val (resources, _) = gnome(2, "1.0")
        assertEquals(2f to 1f, scales(resources, "1.0"))
        // dconf's spelling of the same answer.
        assertEquals(2f to 1f, scales(resources, "double 1.0"))
    }

    @Test
    fun gnomeAt200PercentWithLargerTextSplitsTheTwo() {
        val (resources, _) = gnome(2, "1.25")
        assertEquals("Xft.antialias:\t1\nXft.dpi:\t240\n", resources)
        assertEquals(2f to 1.25f, scales(resources, "1.25"))
    }

    @Test
    fun aFactorThatDoesNotDivideEvenlyStillFindsTheWindowScale() {
        // 96 x 1.1 is 105.6, written as 105; 96 x 2 x 1.1 is 211.2, written as 211.
        assertEquals(1f to 1.1f, scales(gnome(1, "1.1").first, "1.1"))
        assertEquals(2f to 1.1f, scales(gnome(2, "1.1").first, "1.1"))
        assertEquals(2f to 0.8f, scales(gnome(2, "0.8").first, "0.8"))
        assertEquals(3f to 1.5f, scales(gnome(3, "1.5").first, "1.5"))
        assertEquals(1f to 0.5f, scales(gnome(1, "0.5").first, "0.5"))
    }

    @Test
    fun onGnomeTextIsScaledOnceByWhatXftDpiSays() {
        for (windowScale in 1..3) {
            for (factor in listOf("0.5", "0.8", "1.0", "1.1", "1.25", "1.5", "2.0", "3.0")) {
                val (resources, _) = gnome(windowScale, factor)
                val (scale, text) = scales(resources, factor)
                val dpi = X11Scale.dpiOf(resources)!!
                assertEquals(windowScale.toFloat(), scale, "window scale at ${windowScale}x, text $factor")
                // Text comes to the pixels Xft.dpi gives a point, as GTK draws it, to within the DPI cut.
                assertEquals(dpi / 96.0, scale.toDouble() * text, 1.0 / 96.0, "text at ${windowScale}x, text $factor")
            }
        }
    }

    @Test
    fun withoutGnomeXftDpiIsTheScaleAsBefore() {
        // KDE or any desktop that writes Xft.dpi and no GNOME factor.
        assertEquals(1.25f to 1f, scales("Xft.dpi:\t120\n", null))
        assertEquals(1.25f, X11Scale.scaleOf("Xft.dpi:\t120\n", null, null))
        // gsettings installed with its default of 1: nothing to take out.
        assertEquals(1.25f to 1f, scales("Xft.dpi:\t120\n", "1.0"))
        assertEquals(1.5f to 1f, scales("Xft.dpi:\t144\n", "1.0"))
        // A GNOME factor that this Xft.dpi is no multiple of: the DPI was not written from it.
        assertEquals(1.5f to 1.25f, scales("Xft.dpi:\t144\n", "1.25"))
        assertEquals(1.5f, X11Scale.scaleOf("Xft.dpi:\t144\n", null, 1.25f))
    }

    @Test
    fun noXftDpiIsAScaleOfOneWhateverTheTextSize() {
        assertEquals(1f to 1.25f, scales(null, "1.25"))
        assertEquals(1f to 1.25f, scales("Xft.antialias:\t1\n", "1.25"))
        assertEquals(1f to 1f, scales(null, null))
    }

    @Test
    fun gdkScaleIsTheWindowScaleAndLeavesTheTextScale() {
        // GDK_SCALE is the window scale alone, so the text factor is not taken out of it.
        val (resources, _) = gnome(2, "1.25")
        assertEquals(2f to 1.25f, scales(resources, "1.25", gdkScale = "2"))
        assertEquals(1f to 1.25f, scales(resources, "1.25", gdkScale = "1"))
        assertEquals(3f to 1.25f, scales(null, "1.25", gdkScale = "3"))
    }

    @Test
    fun anUnusableFactorTakesNothingOut() {
        assertEquals(240.0, X11Scale.withoutTextScale(240.0, null))
        assertEquals(240.0, X11Scale.withoutTextScale(240.0, 1f))
        assertEquals(240.0, X11Scale.withoutTextScale(240.0, 0f))
        assertEquals(240.0, X11Scale.withoutTextScale(240.0, -1.25f))
        assertEquals(240.0, X11Scale.withoutTextScale(240.0, Float.NaN))
        assertEquals(192.0, X11Scale.withoutTextScale(240.0, 1.25f))
        // Under one whole multiple: not a DPI GNOME wrote from this factor.
        assertEquals(50.0, X11Scale.withoutTextScale(50.0, 2f))
    }

    /** `kcmfonts` with fonts forced to [dpi], and `kdeglobals` with the global scale [scale]. */
    private fun kdeFonts(dpi: Int) = "[General]\nforceFontDPI=$dpi\n"
    private fun kdeGlobals(scale: String) = "[General]\nColorScheme=BreezeLight\n\n[KScreen]\nScaleFactor=$scale\nScreenScaleFactors=eDP-1=$scale;\n"

    /** The scale of the window and of its text on KDE, as the X11 windows report them. */
    private fun kdeScales(
        resources: String?,
        fonts: String?,
        globals: String?,
        gnomeOutput: String? = null,
        gdkScale: String? = null,
    ): Pair<Float, Float> {
        val text = TextScale.linuxFactor(gnomeOutput, fonts, globals, currentDesktop = "KDE")
        return X11Scale.scaleOf(resources, gdkScale, text?.factor, text?.desktopScale) to (text?.factor ?: 1f)
    }

    @Test
    fun kdeAt100PercentWithFontsForcedTo120ScalesOnlyTheText() {
        // Plasma writes the forced DPI into Xft.dpi; before, both scales were 1.25.
        assertEquals(1f to 1.25f, kdeScales("Xft.dpi:\t120\n", kdeFonts(120), kdeGlobals("1")))
        // No kdeglobals scale is Plasma's scale of 1.
        assertEquals(1f to 1.25f, kdeScales("Xft.dpi:\t120\n", kdeFonts(120), null))
    }

    @Test
    fun kdeAt200PercentWithoutForcingScalesTheWindow() {
        // The display settings force fonts to 96 x the global scale themselves; before, text was 2 on top.
        assertEquals(2f to 1f, kdeScales("Xft.dpi:\t192\n", kdeFonts(192), kdeGlobals("2")))
        assertEquals(2f to 1f, kdeScales("Xft.dpi:\t192\n", null, kdeGlobals("2")))
        assertEquals(2f to 1f, kdeScales("Xft.dpi:\t192\n", "[General]\nforceFontDPI=0\n", kdeGlobals("2")))
    }

    @Test
    fun kdeAt200PercentWithFontsForcedTo240SplitsTheTwo() {
        assertEquals(2f to 1.25f, kdeScales("Xft.dpi:\t240\n", kdeFonts(240), kdeGlobals("2")))
        // Fractional global scales, which KDE has on X11 too.
        assertEquals(1.5f to 1.25f, kdeScales("Xft.dpi:\t180\n", kdeFonts(180), kdeGlobals("1.5")))
        assertEquals(1.5f to 1f, kdeScales("Xft.dpi:\t144\n", kdeFonts(144), kdeGlobals("1.5")))
    }

    @Test
    fun kdeWithFontsForcedTo96ChangesNothing() {
        assertEquals(1f to 1f, kdeScales("Xft.dpi:\t96\n", kdeFonts(96), kdeGlobals("1")))
        assertEquals(1f to 1f, kdeScales("Xft.dpi:\t96\n", kdeFonts(96), null))
    }

    @Test
    fun onKdeTextIsScaledOnceByWhatXftDpiSays() {
        for (scale in listOf("1", "1.25", "1.5", "2", "3")) {
            for (dpi in listOf(96, 110, 120, 144, 192, 240, 288)) {
                val forced = (dpi * scale.toDouble()).toInt()
                val resources = "Xft.dpi:\t$forced\n"
                val (window, text) = kdeScales(resources, kdeFonts(forced), kdeGlobals(scale))
                assertEquals(scale.toFloat(), window, "window scale at $scale, fonts at $forced DPI")
                assertEquals(forced / 96.0, window.toDouble() * text, 1e-4, "text at $scale, fonts at $forced DPI")
            }
        }
    }

    @Test
    fun onKdeItsFontDpiWinsOverGsettingsDefault() {
        // gsettings is often installed on KDE and answers its default of 1.
        assertEquals(1f to 1.25f, kdeScales("Xft.dpi:\t120\n", kdeFonts(120), kdeGlobals("1"), gnomeOutput = "1.0"))
        // KDE without a forced DPI: GNOME's answer, as before.
        assertEquals(1.25f to 1f, kdeScales("Xft.dpi:\t120\n", null, null, gnomeOutput = "1.0"))
    }

    @Test
    fun onKdeGdkScaleIsTheWindowScaleAndLeavesTheTextScale() {
        assertEquals(2f to 1.25f, kdeScales("Xft.dpi:\t240\n", kdeFonts(240), kdeGlobals("2"), gdkScale = "2"))
    }

    @Test
    fun onKdeAnXftDpiNotFromTheForcedDpiIsLeftAlone() {
        // xrdb or another tool wrote a DPI that is not 96 x the global scale x the text size.
        assertEquals((200.0 / 96.0).toFloat() to 1.25f, kdeScales("Xft.dpi:\t200\n", kdeFonts(120), kdeGlobals("1")))
        assertEquals(192.0, X11Scale.withoutTextScale(240.0, 1.25f, 2f))
        assertEquals(144.0, X11Scale.withoutTextScale(180.0, 1.25f, 1.5f))
        assertEquals(200.0, X11Scale.withoutTextScale(200.0, 1.25f, 2f))
        // A desktop scale that is no number is none: GNOME's whole multiples apply.
        assertEquals(192.0, X11Scale.withoutTextScale(240.0, 1.25f, Float.NaN))
    }

    @Test
    fun aScaleChangeKeepsTheSizeInPoints() {
        assertEquals(1280 to 960, X11Scale.sizeAtScale(640, 480, 1f, 2f))
        assertEquals(640 to 480, X11Scale.sizeAtScale(1280, 960, 2f, 1f))
        // 800x600 at 1.25 is 640x480 points, which are 1600x1200 at 2.5.
        assertEquals(1600 to 1200, X11Scale.sizeAtScale(800, 600, 1.25f, 2.5f))
        assertEquals(960 to 720, X11Scale.sizeAtScale(640, 480, 1f, 1.5f))
    }

    @Test
    fun aRescaledSizeRoundsToTheNearestPixel() {
        // 641 at 1 to 1.25 is 801.25; 333 at 1.5 to 1 is 222; 101 at 2 to 1 is 50.5, a half up.
        assertEquals(801, X11Scale.pixelsAtScale(641, 1f, 1.25f))
        assertEquals(222, X11Scale.pixelsAtScale(333, 1.5f, 1f))
        assertEquals(51, X11Scale.pixelsAtScale(101, 2f, 1f))
        assertEquals(1602, X11Scale.pixelsAtScale(801, 1.25f, 2.5f))
        // 1 at 2 to 1 is half a pixel, kept at one; nothing goes under one pixel.
        assertEquals(1, X11Scale.pixelsAtScale(1, 2f, 1f))
        assertEquals(1, X11Scale.pixelsAtScale(1, 4f, 1f))
    }

    @Test
    fun aScaleThatIsNoPositiveNumberLeavesThePixels() {
        assertEquals(640, X11Scale.pixelsAtScale(640, 0f, 2f))
        assertEquals(640, X11Scale.pixelsAtScale(640, 1f, -1f))
        assertEquals(640, X11Scale.pixelsAtScale(640, Float.NaN, 2f))
        assertEquals(640, X11Scale.pixelsAtScale(640, 1f, Float.POSITIVE_INFINITY))
        assertEquals(640, X11Scale.pixelsAtScale(640, 2f, 2f))
    }
}
