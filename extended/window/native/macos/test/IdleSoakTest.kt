package org.thisisthepy.compose.window.macos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdleSoakTest {

    @Test
    fun the_window_soak_reads_its_own_variable() {
        assertEquals("DXC_WINDOW_IDLE_SOAK_SECONDS", IdleSoak.VARIABLE)
    }

    @Test
    fun of_three_windows_only_the_first_runs_the_soak() {
        var taken = false
        val claim = { if (taken) false else { taken = true; true } }
        val windows = List(3) { IdleSoak(seconds = 30, claim = claim) }
        assertEquals(listOf(true, false, false), windows.map { it.enabled })
        // Asked again, each keeps its answer.
        assertEquals(listOf(true, false, false), windows.map { it.enabled })
    }

    @Test
    fun without_seconds_no_window_claims_the_soak() {
        var asked = 0
        val soak = IdleSoak(seconds = 0, claim = { asked++; true })
        assertFalse(soak.enabled)
        assertEquals(0, asked)
        assertTrue(IdleSoak(seconds = 5, claim = { true }).enabled)
    }
}
