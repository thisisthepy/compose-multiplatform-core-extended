package org.thisisthepy.compose.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextScaleTest {
    @Test
    fun gnomeReadsPlainAndTypedDoubles() {
        assertEquals(1.25f, TextScale.fromGnome("1.25\n"))
        assertEquals(1.5f, TextScale.fromGnome("double 1.5"))
    }

    @Test
    fun gnomeRejectsGarbageAndNonPositive() {
        assertNull(TextScale.fromGnome(null))
        assertNull(TextScale.fromGnome(""))
        assertNull(TextScale.fromGnome("No such schema"))
        assertNull(TextScale.fromGnome("0"))
        assertNull(TextScale.fromGnome("-1.0"))
    }

    @Test
    fun kdeDividesForcedDpiByBase() {
        val config = "[General]\nfontsIncludingFixed=x\nforceFontDPI=120\n"
        assertEquals(1.25f, TextScale.fromKdeFontsConfig(config))
        assertNull(TextScale.fromKdeFontsConfig("[General]\nforceFontDPI=abc"))
        assertNull(TextScale.fromKdeFontsConfig("[General]\n"))
        assertNull(TextScale.fromKdeFontsConfig(null))
    }

    @Test
    fun linuxPrefersGnomeThenKdeThenDefault() {
        val kde = "forceFontDPI=144"
        assertEquals(2f, TextScale.resolveLinux("2.0", kde))
        assertEquals(1.5f, TextScale.resolveLinux("nonsense", kde))
        assertEquals(1f, TextScale.resolveLinux(null, null))
    }

    @Test
    fun windowsPercentBecomesAMultiplier() {
        assertEquals(1f, TextScale.fromWindowsPercent(0))
        assertEquals(1f, TextScale.fromWindowsPercent(100))
        assertEquals(1.5f, TextScale.fromWindowsPercent(150))
        assertEquals(2.25f, TextScale.fromWindowsPercent(225))
    }

    @Test
    fun outOfRangeAnswersAreClamped() {
        assertEquals(TextScale.MAX, TextScale.clamp(10f))
        assertEquals(TextScale.MIN, TextScale.clamp(0.1f))
        assertEquals(1f, TextScale.clamp(Float.NaN))
    }

    @Test
    fun textScaleEventCarriesTheScale() {
        val event = WindowEvent.TextScaleChanged(1.3f)
        assertTrue(event.isTextScaleChanged)
        assertEquals(1.3f, event.textScale)
    }
}
