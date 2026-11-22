package org.thisisthepy.compose.window

/**
 * Shared parity contract for [WindowPlatform] implementations across Kotlin/Native and GraalVM
 * modules.
 *
 * Each check creates a fresh platform instance through [createPlatform], opens it with
 * [defaultConfig], verifies one invariant, and always closes it in a `finally` block.
 * Call [assertAll] to run the entire suite as a single test or call individual `verify*`
 * methods from separate tests.
 *
 * [requestClose] simulates a window manager close request on an open platform and returns
 * `true` if the platform closed the window or `false` if [WindowListener.onCloseRequested]
 * vetoed the request. Leave it `null` when the test environment cannot synthesize OS close
 * requests.
 */
class WindowPlatformParityContract(
    private val createPlatform: () -> WindowPlatform,
    private val requestClose: ((WindowPlatform) -> Boolean)? = null,
    private val defaultConfig: WindowConfig = WindowConfig(
        title = "Parity",
        width = 320,
        height = 240,
    ),
    private val checkClipboard: Boolean = true,
) {
    /** Outcome of one parity check returned by [runAll]. */
    data class CheckResult(
        val name: String,
        val passed: Boolean,
        val skipped: Boolean = false,
        val detail: String? = null,
    )

    private class RecordingListener(
        private val allowClose: Boolean = true,
    ) : WindowListener {
        var closeRequests: Int = 0
            private set

        override fun onEvent(event: WindowEvent) {}

        override fun onCloseRequested(): Boolean {
            closeRequests++
            return allowClose
        }
    }

    private fun checkCondition(condition: Boolean, message: () -> String) {
        if (!condition) throw AssertionError(message())
    }

    private fun openOrFail(platform: WindowPlatform, listener: WindowListener) {
        checkCondition(platform.name.isNotBlank()) {
            "WindowPlatform.name must not be blank"
        }
        val opened = platform.open(defaultConfig, listener)
        checkCondition(opened) {
            "${platform.name}: open() returned false for $defaultConfig"
        }
    }

    /** Verifies open, measure, frame request, pump, present, and close lifecycle. */
    fun verifyOpenCloseLifecycle() {
        val platform = createPlatform()
        val listener = RecordingListener()
        openOrFail(platform, listener)
        try {
            val measurement = platform.measure()
            checkCondition(measurement.width > 0 && measurement.height > 0) {
                "${platform.name}: measure() returned non-positive size $measurement"
            }
            checkCondition(measurement.scale > 0f) {
                "${platform.name}: measure() returned non-positive scale ${measurement.scale}"
            }
            platform.requestFrame()
            platform.pump(0L)
            val record = platform.present(measurement.width, measurement.height)
            checkCondition(
                record.drawnWidth == measurement.width && record.drawnHeight == measurement.height,
            ) {
                "${platform.name}: present() echoed wrong drawn size $record for $measurement"
            }
        } finally {
            platform.close()
        }
    }

    /**
     * Verifies [WindowListener.onCloseRequested] veto vs allow behaviour.
     *
     * Always verifies the default listener returns `true`. When [requestClose] is provided,
     * also opens two windows to verify that returning `false` vetoes the close and returning
     * `true` allows it.
     */
    fun verifyCloseRequestVetoAndAllow() {
        val defaultListener = object : WindowListener {
            override fun onEvent(event: WindowEvent) {}
        }
        checkCondition(defaultListener.onCloseRequested()) {
            "WindowListener.onCloseRequested() must default to true"
        }

        val trigger = requestClose ?: return

        val vetoPlatform = createPlatform()
        val vetoListener = RecordingListener(allowClose = false)
        openOrFail(vetoPlatform, vetoListener)
        try {
            val closed = trigger(vetoPlatform)
            checkCondition(vetoListener.closeRequests == 1) {
                "${vetoPlatform.name}: expected 1 onCloseRequested call on veto, got ${vetoListener.closeRequests}"
            }
            checkCondition(!closed) {
                "${vetoPlatform.name}: window closed despite onCloseRequested() returning false"
            }
        } finally {
            vetoPlatform.close()
        }

        val allowPlatform = createPlatform()
        val allowListener = RecordingListener(allowClose = true)
        openOrFail(allowPlatform, allowListener)
        try {
            val closed = trigger(allowPlatform)
            checkCondition(allowListener.closeRequests == 1) {
                "${allowPlatform.name}: expected 1 onCloseRequested call on allow, got ${allowListener.closeRequests}"
            }
            checkCondition(closed) {
                "${allowPlatform.name}: window stayed open after onCloseRequested() returned true"
            }
        } finally {
            allowPlatform.close()
        }
    }

    /** Verifies [WindowPlatform.setTitle] accepts ASCII, empty, and Unicode titles. */
    fun verifySetTitle() {
        val platform = createPlatform()
        openOrFail(platform, RecordingListener())
        try {
            platform.setTitle("Parity Title")
            platform.setTitle("")
            platform.setTitle("Unicode Title 한글")
            platform.pump(0L)
        } finally {
            platform.close()
        }
    }

    /** Verifies [WindowPlatform.setVisibility] accepts every [WindowVisibility] state. */
    fun verifySetVisibility() {
        val platform = createPlatform()
        openOrFail(platform, RecordingListener())
        try {
            for (visibility in listOf(
                WindowVisibility.Visible,
                WindowVisibility.Hidden,
                WindowVisibility.Minimized,
                WindowVisibility.Fullscreen,
                WindowVisibility.Visible,
            )) {
                platform.setVisibility(visibility)
                platform.pump(0L)
            }
        } finally {
            platform.close()
        }
    }

    /** Verifies [WindowPlatform.setImeSpot] and [WindowPlatform.endComposition]. */
    fun verifySetImeSpot() {
        val platform = createPlatform()
        openOrFail(platform, RecordingListener())
        try {
            platform.setImeSpot(0, 0)
            platform.setImeSpot(120, 80)
            platform.endComposition()
            platform.pump(0L)
        } finally {
            platform.close()
        }
    }

    /** Verifies [WindowPlatform.wake] can be called before and after [WindowPlatform.pump]. */
    fun verifyWake() {
        val platform = createPlatform()
        openOrFail(platform, RecordingListener())
        try {
            platform.wake()
            platform.pump(0L)
            platform.wake()
            platform.wake()
            platform.pump(0L)
        } finally {
            platform.close()
        }
    }

    /** Verifies clipboard write/read round-trip for ASCII, Unicode, and [pasteboard] clearing. */
    fun verifyClipboardRoundTrip() {
        val platform = createPlatform()
        openOrFail(platform, RecordingListener())
        try {
            val ascii = "parity-clipboard-check"
            platform.writeClipboardText(ascii)
            val readAscii = platform.readClipboardText()
            checkCondition(readAscii == ascii) {
                "${platform.name}: clipboard ASCII round-trip expected '$ascii', got '$readAscii'"
            }

            val unicode = "안녕 hello 123"
            platform.writeClipboardText(unicode)
            val readUnicode = platform.readClipboardText()
            checkCondition(readUnicode == unicode) {
                "${platform.name}: clipboard Unicode round-trip expected '$unicode', got '$readUnicode'"
            }

            val board = platform.pasteboard()
            board.copyText("pasteboard-check")
            checkCondition(board.hasText() && board.pasteText() == "pasteboard-check") {
                "${platform.name}: pasteboard() failed to read back copied text"
            }
            board.copyText(null)
            checkCondition(!board.hasText() && board.pasteText() == null) {
                "${platform.name}: pasteboard() did not clear on copyText(null)"
            }
        } finally {
            platform.close()
        }
    }

    /** Verifies [WindowPlatform.systemTheme] returns a valid non-null [SystemTheme]. */
    fun verifySystemTheme() {
        val platform = createPlatform()
        openOrFail(platform, RecordingListener())
        try {
            val theme: SystemTheme? = platform.systemTheme()
            checkCondition(theme != null && theme in SystemTheme.entries) {
                "${platform.name}: systemTheme() returned invalid value $theme"
            }
        } finally {
            platform.close()
        }
    }

    /** Runs every parity check and returns the per-check results without throwing. */
    fun runAll(): List<CheckResult> {
        val checks = listOf<Pair<String, () -> Unit>>(
            "openCloseLifecycle" to ::verifyOpenCloseLifecycle,
            "closeRequestVetoAndAllow" to ::verifyCloseRequestVetoAndAllow,
            "setTitle" to ::verifySetTitle,
            "setVisibility" to ::verifySetVisibility,
            "setImeSpot" to ::verifySetImeSpot,
            "wake" to ::verifyWake,
            "clipboardRoundTrip" to ::verifyClipboardRoundTrip,
            "systemTheme" to ::verifySystemTheme,
        )
        return checks.map { (name, block) ->
            if (name == "clipboardRoundTrip" && !checkClipboard) {
                CheckResult(name = name, passed = true, skipped = true)
            } else {
                try {
                    block()
                    CheckResult(name = name, passed = true)
                } catch (failure: Throwable) {
                    CheckResult(
                        name = name,
                        passed = false,
                        detail = failure.message ?: failure.toString(),
                    )
                }
            }
        }
    }

    /** Runs all parity checks and throws [AssertionError] if any check fails. */
    fun assertAll() {
        val failures = runAll().filter { !it.passed }
        if (failures.isNotEmpty()) {
            val summary = failures.joinToString("\n") { " - ${it.name}: ${it.detail}" }
            throw AssertionError("WindowPlatform parity contract failed:\n$summary")
        }
    }
}
