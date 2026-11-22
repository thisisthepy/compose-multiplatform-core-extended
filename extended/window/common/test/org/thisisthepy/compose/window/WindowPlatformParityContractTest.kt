package org.thisisthepy.compose.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowPlatformParityContractTest {

    private class FakeWindowPlatform(
        override val name: String = "fake-window",
        private val openSucceeds: Boolean = true,
        private val honorCloseVeto: Boolean = true,
        private val persistClipboard: Boolean = true,
        private val theme: SystemTheme = SystemTheme.Light,
    ) : WindowPlatform {
        var isOpen: Boolean = false
            private set
        var closeCount: Int = 0
            private set
        var wakeCount: Int = 0
            private set
        var pumpCount: Int = 0
            private set
        var frameRequests: Int = 0
            private set
        val titles: MutableList<String> = mutableListOf()
        val visibilities: MutableList<WindowVisibility> = mutableListOf()
        val imeSpots: MutableList<Pair<Int, Int>> = mutableListOf()
        var endCompositionCount: Int = 0
            private set

        private var width: Int = 1
        private var height: Int = 1
        private var listener: WindowListener? = null
        private var clipboard: String? = null

        override fun open(config: WindowConfig, listener: WindowListener): Boolean {
            if (!openSucceeds) return false
            this.listener = listener
            this.width = config.width.coerceAtLeast(1)
            this.height = config.height.coerceAtLeast(1)
            this.titles += config.title
            this.isOpen = true
            return true
        }

        override fun pump(timeoutMillis: Long) {
            pumpCount++
        }

        override fun wake() {
            wakeCount++
        }

        override fun measure(): WindowMeasurement = WindowMeasurement(width, height, 2f)

        override fun requestFrame() {
            frameRequests++
        }

        override fun present(drawnWidth: Int, drawnHeight: Int): FramePresentRecord =
            FramePresentRecord(drawnWidth, drawnHeight, width, height)

        override fun systemTheme(): SystemTheme = theme

        override fun setTitle(title: String) {
            titles += title
        }

        override fun setMinimumSize(width: Int, height: Int) {}

        override fun setVisibility(visibility: WindowVisibility) {
            visibilities += visibility
        }

        override fun readClipboardText(): String? = clipboard

        override fun writeClipboardText(text: String) {
            if (persistClipboard) clipboard = text
        }

        override fun setImeSpot(x: Int, y: Int) {
            imeSpots += x to y
        }

        override fun endComposition() {
            endCompositionCount++
        }

        override fun showContextMenu(items: List<ContextMenuItem>) {}

        override fun close() {
            isOpen = false
            closeCount++
        }

        fun simulateCloseRequest(): Boolean {
            val allowed = listener?.onCloseRequested() ?: true
            if (allowed || !honorCloseVeto) {
                close()
                return true
            }
            return false
        }
    }

    @Test
    fun full_parity_contract_passes_against_reference_fake_platform() {
        val created = mutableListOf<FakeWindowPlatform>()
        val contract = WindowPlatformParityContract(
            createPlatform = { FakeWindowPlatform().also(created::add) },
            requestClose = { (it as FakeWindowPlatform).simulateCloseRequest() },
        )

        val results = contract.runAll()
        assertEquals(8, results.size)
        assertTrue(results.all { it.passed && !it.skipped }, "unexpected failures: $results")
        assertTrue(created.all { !it.isOpen && it.closeCount >= 1 }, "every instance must be closed")
        assertTrue(created.any { it.wakeCount >= 3 }, "wake() must be exercised")
        assertTrue(created.any { it.titles.contains("Unicode Title 한글") }, "setTitle() must be exercised")
        assertTrue(
            created.any { WindowVisibility.Fullscreen in it.visibilities && WindowVisibility.Minimized in it.visibilities },
            "setVisibility() must cycle visibility states",
        )
        assertTrue(
            created.any { (120 to 80) in it.imeSpots && it.endCompositionCount >= 1 },
            "setImeSpot() and endComposition() must be exercised",
        )

        contract.assertAll()
    }

    @Test
    fun close_request_veto_violation_is_caught_by_contract() {
        val contract = WindowPlatformParityContract(
            createPlatform = { FakeWindowPlatform(honorCloseVeto = false) },
            requestClose = { (it as FakeWindowPlatform).simulateCloseRequest() },
        )

        assertFailsWith<AssertionError> {
            contract.verifyCloseRequestVetoAndAllow()
        }
    }

    @Test
    fun clipboard_round_trip_failure_is_caught_by_contract() {
        val contract = WindowPlatformParityContract(
            createPlatform = { FakeWindowPlatform(persistClipboard = false) },
        )

        assertFailsWith<AssertionError> {
            contract.verifyClipboardRoundTrip()
        }
    }

    @Test
    fun failed_open_and_blank_name_fail_lifecycle_check() {
        val failedOpen = WindowPlatformParityContract(
            createPlatform = { FakeWindowPlatform(openSucceeds = false) },
        )
        assertFailsWith<AssertionError> {
            failedOpen.verifyOpenCloseLifecycle()
        }

        val blankName = WindowPlatformParityContract(
            createPlatform = { FakeWindowPlatform(name = "   ") },
        )
        assertFailsWith<AssertionError> {
            blankName.verifyOpenCloseLifecycle()
        }
    }

    @Test
    fun contract_supports_skipping_clipboard_and_omitting_close_request_hook() {
        val contract = WindowPlatformParityContract(
            createPlatform = { FakeWindowPlatform(persistClipboard = false, theme = SystemTheme.Dark) },
            requestClose = null,
            checkClipboard = false,
        )

        val results = contract.runAll()
        assertTrue(results.all { it.passed }, "all checks should pass when clipboard is skipped: $results")
        val clipboard = results.single { it.name == "clipboardRoundTrip" }
        assertTrue(clipboard.skipped)
        assertFalse(results.single { it.name == "systemTheme" }.skipped)
        contract.assertAll()
    }
}
