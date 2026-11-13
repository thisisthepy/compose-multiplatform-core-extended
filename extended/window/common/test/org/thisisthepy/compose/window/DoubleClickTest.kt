package org.thisisthepy.compose.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DoubleClickTest {
    @Test
    fun macosSecondsBecomeMilliseconds() {
        assertEquals(500L, DoubleClick.fromSeconds(0.5))
        assertEquals(1500L, DoubleClick.fromSeconds(1.5))
        assertEquals(DoubleClick.DEFAULT_MILLIS, DoubleClick.fromSeconds(Double.NaN))
        assertEquals(DoubleClick.DEFAULT_MILLIS, DoubleClick.fromSeconds(0.0))
    }

    @Test
    fun clampKeepsTheIntervalUsable() {
        assertEquals(DoubleClick.DEFAULT_MILLIS, DoubleClick.clamp(0))
        assertEquals(DoubleClick.DEFAULT_MILLIS, DoubleClick.clamp(-3))
        assertEquals(DoubleClick.MIN_MILLIS, DoubleClick.clamp(20))
        assertEquals(DoubleClick.MAX_MILLIS, DoubleClick.clamp(60_000))
        assertEquals(400L, DoubleClick.clamp(400))
    }

    @Test
    fun gnomeReadsPlainAndTypedIntegers() {
        assertEquals(400L, DoubleClick.fromGnome("400\n"))
        assertEquals(650L, DoubleClick.fromGnome("int32 650"))
        assertNull(DoubleClick.fromGnome(null))
        assertNull(DoubleClick.fromGnome("No such schema"))
        assertNull(DoubleClick.fromGnome("0"))
    }

    @Test
    fun kdeReadsTheKdeGroupOnly() {
        assertEquals(400L, DoubleClick.fromKdeGlobals("[General]\nx=1\n[KDE]\nDoubleClickInterval=400\n"))
        assertNull(DoubleClick.fromKdeGlobals("[Other]\nDoubleClickInterval=400\n"))
        assertNull(DoubleClick.fromKdeGlobals("[KDE]\nDoubleClickInterval=fast\n"))
        assertNull(DoubleClick.fromKdeGlobals(null))
    }

    @Test
    fun linuxPrefersGnomeThenKdeThenTheDefault() {
        assertEquals(300L, DoubleClick.resolveLinux("300", "[KDE]\nDoubleClickInterval=700"))
        assertEquals(700L, DoubleClick.resolveLinux(null, "[KDE]\nDoubleClickInterval=700"))
        assertEquals(DoubleClick.DEFAULT_MILLIS, DoubleClick.resolveLinux(null, null))
    }
}
