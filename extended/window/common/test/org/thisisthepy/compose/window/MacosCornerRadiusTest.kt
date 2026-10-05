package org.thisisthepy.compose.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacosCornerRadiusTest {

    @Test
    fun fr19_7_macos_26_rounds_a_toolbar_window_more_than_a_plain_one() {
        assertEquals(16.75, macosCornerRadius(toolbar = false, macosMajor = 26))
        assertEquals(26.75, macosCornerRadius(toolbar = true, macosMajor = 26))
    }

    @Test
    fun fr19_7_a_newer_release_takes_the_latest_known_row() {
        val latest = MACOS_CORNER_RADII.last()
        assertEquals(latest.simple, macosCornerRadius(toolbar = false, macosMajor = latest.fromMajor + 3))
        assertEquals(latest.toolbar, macosCornerRadius(toolbar = true, macosMajor = latest.fromMajor + 3))
    }

    @Test
    fun fr19_7_an_older_release_takes_its_own_row() {
        assertEquals(10.25, macosCornerRadius(toolbar = false, macosMajor = 15))
        assertEquals(10.25, macosCornerRadius(toolbar = true, macosMajor = 14))
        assertNull(macosCornerRadius(toolbar = true, macosMajor = 10))
    }

    @Test
    fun fr19_7_the_rows_are_in_release_order() {
        val majors = MACOS_CORNER_RADII.map { it.fromMajor }
        assertEquals(majors.sorted().distinct(), majors)
        assertTrue(MACOS_CORNER_RADII.all { it.toolbar >= it.simple })
    }
}
