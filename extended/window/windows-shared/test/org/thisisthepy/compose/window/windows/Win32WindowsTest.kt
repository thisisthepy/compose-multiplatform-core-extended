package org.thisisthepy.compose.window.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.thisisthepy.compose.window.CaptionAction
import org.thisisthepy.compose.window.ContextMenuItem
import org.thisisthepy.compose.window.ScreenPointer
import org.thisisthepy.compose.window.WindowBounds
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.WindowMeasurement
import org.thisisthepy.compose.window.WindowVisibility

// The platform over a backend that is a few fields. Nothing here reaches the C window, whose
// object is linked by MSVC into applications and never by this toolchain; the C side of the
// same requirements is checked by c/test/win32_shared_test.c with real windows.

private class FakeWindow(val config: WindowConfig) : Win32WindowBackend {
    var destroyed = false
    var released = false
    var pointerDown = true
    var focusGranted = true
    var currentBounds = WindowBounds(config.x ?: 100, config.y ?: 100, config.width, config.height)
    val events = ArrayList<WindowEvent>()
    var activation: Boolean? = null
    var moved: WindowBounds? = null
    var followEnd: Boolean? = null
    var closeRequests = 0
    var active = false
    var followAnchor: Pair<Float, Float>? = null
    var focusCalls = 0

    override val closed: Boolean get() = destroyed
    override fun measure() = WindowMeasurement(config.width, config.height, 1f)
    override fun drainEvents(): List<WindowEvent> {
        val out = events.toList()
        events.clear()
        return out
    }
    override fun setTitle(title: String) {}
    override fun setMinimumSize(width: Int, height: Int) {}
    override fun setVisibility(visibility: WindowVisibility) {}
    override fun readClipboardText(): String? = null
    override fun writeClipboardText(text: String) {}
    override fun setImeSpot(x: Int, y: Int) {}
    override fun showContextMenu(items: List<ContextMenuItem>): Int = if (items.isEmpty()) -1 else items.size - 1
    override fun performCaptionAction(action: CaptionAction) {}
    override fun systemDark(): Boolean = false

    override fun setPosition(x: Int, y: Int) {
        currentBounds = currentBounds.copy(x = x, y = y)
        moved = currentBounds
    }

    override fun setContentSize(width: Int, height: Int) {
        currentBounds = currentBounds.copy(width = width, height = height)
        moved = currentBounds
    }

    override fun bounds(): WindowBounds? = currentBounds

    override fun focus(): Boolean {
        focusCalls++
        if (focusGranted) {
            active = true
            activation = true
        }
        return focusGranted
    }

    override val isActive: Boolean get() = active
    override fun pointerOnScreen(): ScreenPointer? = ScreenPointer(10f, 20f, pointerDown)
    override fun followStart(anchorX: Float, anchorY: Float): Boolean {
        if (!pointerDown) return false
        followAnchor = anchorX to anchorY
        return true
    }
    override fun takeActivation(): Boolean? = activation.also { activation = null }
    override fun takeMoved(): WindowBounds? = moved.also { moved = null }
    override fun takeFollowEnd(): Boolean? = followEnd.also { followEnd = null }
    override fun takeCloseRequest(): Boolean {
        val asked = closeRequests > 0
        closeRequests = 0
        return asked
    }
    override fun destroy() {
        destroyed = true
    }
    override fun release() {
        released = true
    }
}

private class FakeProcess : Win32ProcessBackend {
    val windows = ArrayList<FakeWindow>()
    var pumps = 0
    var quit: Int? = null
    var refuse = false

    override fun openWindow(config: WindowConfig): Win32WindowBackend? {
        if (refuse) return null
        val window = FakeWindow(config)
        windows.add(window)
        return window
    }

    override fun pump(seconds: Double) {
        pumps++
    }

    override fun postQuit(code: Int) {
        quit = code
    }

    override fun quitPosted(): Int? = quit
}

private class Recorder : WindowListener {
    val events = ArrayList<WindowEvent>()
    val focus = ArrayList<Boolean>()
    val moves = ArrayList<WindowBounds>()
    val follows = ArrayList<Boolean>()
    var closeAsked = 0
    var allowClose = true
    override fun onEvent(event: WindowEvent) {
        events.add(event)
    }
    override fun onFocusChanged(focused: Boolean) {
        focus.add(focused)
    }
    override fun onMoved(bounds: WindowBounds) {
        moves.add(bounds)
    }
    override fun onFollowEnded(cancelled: Boolean) {
        follows.add(cancelled)
    }
    override fun onCloseRequested(): Boolean {
        closeAsked++
        return allowClose
    }
}

private fun move(x: Float) = WindowEvent(WindowEvent.POINTER_MOVE, x, 0f, 0, 0, 0, 0, "")

private fun config(title: String, x: Int? = null, y: Int? = null) =
    WindowConfig(title = title, width = 300, height = 200, x = x, y = y)

class Win32WindowsTest {

    @Test
    fun fr53_two_windows_have_independent_state() {
        val process = FakeProcess()
        val windows = Win32Windows(process)
        val first = windows.newPlatform()
        val second = windows.newPlatform()
        val firstListener = Recorder()
        val secondListener = Recorder()
        assertTrue(first.open(config("a", 10, 20), firstListener))
        assertTrue(second.open(config("b", 400, 300), secondListener))
        assertTrue(first.supportsMultipleWindows)
        assertEquals(2, windows.openCount)

        process.windows[0].events.add(move(1f))
        process.windows[1].events.add(move(2f))
        process.windows[1].events.add(move(3f))
        // Pumping on either window reaches both.
        second.pump(0)
        assertEquals(listOf(1f), firstListener.events.map { it.x })
        assertEquals(listOf(2f, 3f), secondListener.events.map { it.x })

        first.setPosition(50, 60)
        assertEquals(WindowBounds(50, 60, 300, 200), first.bounds())
        assertEquals(WindowBounds(400, 300, 300, 200), second.bounds())

        process.windows[1].events.add(move(4f))
        first.pump(0)
        assertEquals(1, firstListener.events.size)
        assertEquals(3, secondListener.events.size)
    }

    @Test
    fun fr53_second_pump_in_the_same_turn_delivers_nothing_twice() {
        val process = FakeProcess()
        val windows = Win32Windows(process)
        val platform = windows.newPlatform()
        val listener = Recorder()
        platform.open(config("a"), listener)
        process.windows[0].events.add(move(1f))
        process.windows[0].moved = WindowBounds(1, 2, 3, 4)
        process.windows[0].activation = true
        platform.pump(0)
        platform.pump(0)
        assertEquals(1, listener.events.size)
        assertEquals(listOf(WindowBounds(1, 2, 3, 4)), listener.moves)
        assertEquals(listOf(true), listener.focus)
    }

    @Test
    fun fr53_destroying_one_window_does_not_quit() {
        val process = FakeProcess()
        val windows = Win32Windows(process)
        val first = windows.newPlatform()
        val second = windows.newPlatform()
        val secondListener = Recorder()
        first.open(config("a"), Recorder())
        second.open(config("b"), secondListener)

        first.close()
        assertTrue(process.windows[0].destroyed)
        assertTrue(process.windows[0].released)
        assertEquals(1, windows.openCount)
        assertNull(windows.quitPosted())

        // The other window still hears its own events.
        process.windows[1].events.add(move(7f))
        second.pump(0)
        assertEquals(1, secondListener.events.size)
        assertFalse(process.windows[1].destroyed)

        // Closing twice is harmless, and the last window going leaves the process to the application.
        first.close()
        second.close()
        assertEquals(0, windows.openCount)
        assertNull(windows.quitPosted())
        windows.postQuit(3)
        assertEquals(3, windows.quitPosted())
    }

    @Test
    fun fr53_close_request_reaches_only_its_window() {
        val process = FakeProcess()
        val windows = Win32Windows(process)
        val first = windows.newPlatform()
        val second = windows.newPlatform()
        val firstListener = Recorder()
        val secondListener = Recorder()
        first.open(config("a"), firstListener)
        second.open(config("b"), secondListener)

        secondListener.allowClose = false
        process.windows[1].closeRequests = 1
        first.pump(0)
        assertEquals(0, firstListener.closeAsked)
        assertEquals(1, secondListener.closeAsked)
        // Refused: the window stays, and is closed later by the application.
        assertFalse(process.windows[1].destroyed)
        assertEquals(2, windows.openCount)

        secondListener.allowClose = true
        process.windows[1].closeRequests = 1
        first.pump(0)
        assertEquals(2, secondListener.closeAsked)
        assertTrue(process.windows[1].destroyed)
        assertFalse(process.windows[0].destroyed)
        assertEquals(1, windows.openCount)
    }

    @Test
    fun fr53_window_position_round_trips() {
        val process = FakeProcess()
        val windows = Win32Windows(process)
        val platform = windows.newPlatform()
        // Where the window opens is the configured position; none leaves the choice to the system.
        platform.open(config("a", 37, 91), Recorder())
        assertEquals(37, process.windows[0].config.x)
        assertEquals(91, process.windows[0].config.y)
        for ((x, y) in listOf(0 to 0, 120 to 140, -30 to 60)) {
            platform.setPosition(x, y)
            val bounds = assertNotNull(platform.bounds())
            assertEquals(x, bounds.x)
            assertEquals(y, bounds.y)
        }
        platform.setContentSize(410, 270)
        val sized = assertNotNull(platform.bounds())
        assertEquals(410, sized.width)
        assertEquals(270, sized.height)
    }

    @Test
    fun fr53_move_is_reported_once_per_turn() {
        val process = FakeProcess()
        val windows = Win32Windows(process)
        val platform = windows.newPlatform()
        val listener = Recorder()
        platform.open(config("a"), listener)
        platform.setPosition(1, 1)
        platform.setPosition(2, 2)
        platform.setPosition(3, 3)
        platform.pump(0)
        assertEquals(1, listener.moves.size)
        assertEquals(3, listener.moves[0].x)
        platform.pump(0)
        assertEquals(1, listener.moves.size)
    }

    @Test
    fun fr53_follow_pointer_ends_with_the_button_or_escape() {
        val process = FakeProcess()
        val windows = Win32Windows(process)
        val platform = windows.newPlatform()
        val listener = Recorder()
        platform.open(config("a"), listener)

        process.windows[0].pointerDown = false
        assertFalse(platform.followPointer(5f, 6f))
        assertNull(process.windows[0].followAnchor)

        process.windows[0].pointerDown = true
        assertTrue(platform.followPointer(5f, 6f))
        assertEquals(5f to 6f, process.windows[0].followAnchor)
        process.windows[0].followEnd = false
        platform.pump(0)
        assertEquals(listOf(false), listener.follows)

        assertTrue(platform.followPointer(1f, 2f))
        process.windows[0].followEnd = true
        platform.pump(0)
        assertEquals(listOf(false, true), listener.follows)
    }

    @Test
    fun fr53_activate_reports_focus() {
        val process = FakeProcess()
        val windows = Win32Windows(process)
        val platform = windows.newPlatform()
        val listener = Recorder()
        platform.open(config("a"), listener)
        val window = process.windows[0]

        window.active = true
        window.activation = true
        platform.pump(0)
        assertEquals(listOf(true), listener.focus)
        assertTrue(platform.isKey)

        // The same state again is not a change.
        window.activation = true
        platform.pump(0)
        assertEquals(listOf(true), listener.focus)

        window.active = false
        window.activation = false
        platform.pump(0)
        assertEquals(listOf(true, false), listener.focus)
        assertFalse(platform.isKey)

        // A focus the system grants is reported by the activation that follows it.
        platform.focus()
        platform.pump(0)
        assertEquals(listOf(true, false, true), listener.focus)

        // A focus the foreground lock refuses reports nothing.
        window.active = false
        window.activation = false
        platform.pump(0)
        window.focusGranted = false
        val before = listener.focus.size
        platform.focus()
        platform.pump(0)
        assertEquals(before, listener.focus.size)
        assertEquals(2, window.focusCalls)
    }

    @Test
    fun fr53_a_machine_without_a_window_refuses_to_open() {
        val process = FakeProcess()
        process.refuse = true
        val windows = Win32Windows(process)
        val platform = windows.newPlatform()
        assertFalse(platform.open(config("a"), Recorder()))
        assertEquals(0, windows.openCount)
        assertNull(platform.bounds())
        assertFalse(platform.isKey)
    }
}
