package org.thisisthepy.compose.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacosAxSnapshotTest {
    private fun node(depth: Int, role: String, subrole: String = "", title: String = "", label: String = "",
                     value: String = "", x: Double = 0.0, y: Double = 0.0, w: Double = 0.0, h: Double = 0.0) =
        MacosAxNode(depth, role, subrole, title, label, value, x, y, w, h)

    // A 480 by 320 window at (100, 100) with a 52 pt unified toolbar bar, as macOS 26 reports it.
    private fun normalWindow(contentTop: Double = 420.0) = listOf(
        node(0, MacosAx.WINDOW, "AXStandardWindow", title = "hello", x = 100.0, y = 100.0, w = 480.0, h = 320.0),
        node(1, MacosAx.GROUP, x = 100.0, y = contentTop - 320.0, w = 480.0, h = 320.0),
        node(2, MacosAx.TEXT_FIELD, label = "Your name", value = "", x = 124.0, y = 300.0, w = 260.0, h = 32.0),
        node(2, MacosAx.BUTTON, label = "click me", x = 124.0, y = 220.0, w = 220.0, h = 56.0),
        node(1, MacosAx.BUTTON, MacosAx.CLOSE_BUTTON_SUBROLE, x = 120.0, y = 394.0, w = 14.0, h = 16.0),
        node(1, MacosAx.BUTTON, MacosAx.MINIMIZE_BUTTON_SUBROLE, x = 140.0, y = 394.0, w = 14.0, h = 16.0),
        node(1, MacosAx.BUTTON, MacosAx.FULL_SCREEN_BUTTON_SUBROLE, x = 160.0, y = 394.0, w = 14.0, h = 16.0),
        node(1, "AXToolbar", x = 100.0, y = 368.0, w = 480.0, h = 52.0),
    )

    private val normalCaption = WindowCaption(height = 52f, buttonsWidth = 94f, cornerRadius = 26.75f)

    @Test
    fun the_snapshot_text_round_trips() {
        val snapshot = MacosAxSnapshot(26, normalWindow())
        val text = (listOf(formatMacosAxHeader(26)) + snapshot.nodes.map(::formatMacosAxNode)).joinToString("\n")
        assertEquals(snapshot, parseMacosAxSnapshot(text))
    }

    @Test
    fun fr19_4_a_normal_window_with_its_buttons_and_its_content_under_the_bar_has_no_findings() {
        val findings = macosChromeFindings(MacosAxSnapshot(26, normalWindow()), "hello", MacosTitleBar.Normal, normalCaption)
        assertEquals(emptyList(), findings)
    }

    @Test
    fun fr19_4_content_below_the_bar_and_a_missing_button_are_findings() {
        val nodes = normalWindow(contentTop = 368.0).filter { it.subrole != MacosAx.MINIMIZE_BUTTON_SUBROLE }
        val findings = macosChromeFindings(MacosAxSnapshot(26, nodes), "hello", MacosTitleBar.Normal, normalCaption)
        assertTrue(findings.any { "minimise" in it }, "$findings")
        assertTrue(findings.any { "below the window's top" in it }, "$findings")
    }

    @Test
    fun fr19_4_a_drawn_title_is_a_finding_when_the_title_is_hidden() {
        val nodes = normalWindow() + node(1, MacosAx.STATIC_TEXT, value = "hello", x = 300.0, y = 400.0, w = 40.0, h = 16.0)
        val findings = macosChromeFindings(MacosAxSnapshot(26, nodes), "hello", MacosTitleBar.Normal, normalCaption)
        assertTrue(findings.any { "shows the title" in it }, "$findings")
    }

    @Test
    fun fr19_7_the_radius_and_the_button_room_are_checked_against_the_table_and_the_buttons() {
        val wrong = normalCaption.copy(buttonsWidth = 80f, cornerRadius = 16.75f)
        val findings = macosChromeFindings(MacosAxSnapshot(26, normalWindow()), "hello", MacosTitleBar.Normal, wrong)
        assertTrue(findings.any { "button room" in it }, "$findings")
        assertTrue(findings.any { "corner radius" in it }, "$findings")
        assertEquals(20.0, MacosAxSnapshot(26, normalWindow()).closeButtonInset("hello"))
    }

    @Test
    fun nfr8_the_field_and_the_button_are_found_by_role_and_label() {
        val snapshot = MacosAxSnapshot(26, normalWindow())
        assertEquals(
            emptyList(),
            macosAccessibleContentFindings(snapshot, "hello", listOf(MacosAx.TEXT_FIELD to "Your name", MacosAx.BUTTON to "click me")),
        )
        val missing = macosAccessibleContentFindings(snapshot, "hello", listOf(MacosAx.CHECK_BOX to "Remember"))
        assertEquals(1, missing.size)
    }
}
