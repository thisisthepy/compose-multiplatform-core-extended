package org.thisisthepy.compose.window.scene

import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import org.thisisthepy.compose.window.CaptionAction
import org.thisisthepy.compose.window.ElementRole
import org.thisisthepy.compose.window.SystemTheme
import org.thisisthepy.compose.window.Win32Chrome
import org.thisisthepy.compose.window.WindowCaption
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEdge
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.X11Chrome

/** A surface that keeps the pixels of the last frame drawn into it. */
private class KeptSurface : WindowSurface {
    var last: Bitmap? = null

    override fun begin(width: Int, height: Int): FrameTarget {
        val surface = Surface.makeRasterN32Premul(width, height)
        return object : FrameTarget {
            override val surface = surface
            override fun close() {
                val bitmap = Bitmap()
                bitmap.allocN32Pixels(width, height)
                surface.readPixels(bitmap, 0, 0)
                last = bitmap
                surface.close()
            }
        }
    }

    override fun setResizePainter(paint: (() -> Boolean)?) {}
    override fun close() {}
}

/**
 * The buttons the scene draws in a caption the platform draws none in (#171): on Windows the
 * window gives the strip to the content and passes the pointer on, also over the maximise
 * button, which is not client area there. What the window sends is in win32_caption.h; these
 * are the scene's half, with the events the window sends.
 */
class SceneCaptionTest {
    private val config = WindowConfig("caption", 300, 200)
    private val windows = Win32Chrome.caption(config)

    // The buttons of a 300 point window: minimise from 162, maximise from 208, close from 254.
    private val overMinimise = 185f to 16f
    private val overMaximise = 231f to 16f
    private val overClose = 277f to 16f

    // Inside each button and clear of its glyph, which is drawn in the middle 32 points.
    private val cornerOfMinimise = 164 to 3
    private val cornerOfMaximise = 210 to 3
    private val cornerOfClose = 297 to 3

    private val strip = Color(0xFFF3F3F3).toArgb()

    private class Opened(val host: ComposeWindowHost, val platform: FakePlatform, val surface: KeptSurface) {
        fun send(kind: Int, at: Pair<Float, Float>, buttons: Int = 0) {
            platform.queued.add(WindowEvent(kind, at.first, at.second, buttons, 0, 0, 0, ""))
            repeat(3) { host.turn(0) }
        }

        fun move(at: Pair<Float, Float>, held: Int = 0) = send(WindowEvent.POINTER_MOVE, at, held)
        fun press(at: Pair<Float, Float>) = send(WindowEvent.POINTER_DOWN, at, WindowEvent.PRIMARY_HELD)
        fun release(at: Pair<Float, Float>) = send(WindowEvent.POINTER_UP, at)
        fun pixel(at: Pair<Int, Int>): Int = assertNotNull(surface.last, "no frame was drawn").getColor(at.first, at.second)
    }

    private fun opened(caption: WindowCaption, resizable: Boolean = true): Opened {
        val platform = FakePlatform().apply {
            listening = true
            captionNow = caption
        }
        val surface = KeptSurface()
        val host = ComposeWindowHost(platform, config.copy(resizable = resizable), { surface }) { BasicText("content") }
        assertTrue(host.open())
        repeat(3) { host.turn(0) }
        return Opened(host, platform, surface)
    }

    @Test
    fun windows_the_three_buttons_are_drawn_in_the_strip_and_each_acts_on_the_window() {
        val window = opened(windows)
        val labels = window.platform.pushed.last().filter { it.role == ElementRole.BUTTON }.map { it.label }
        assertEquals(listOf("Minimize", "Maximize", "Close"), labels)

        for (at in listOf(overMinimise, overMaximise, overClose)) {
            window.press(at)
            window.release(at)
        }
        assertEquals(
            listOf(CaptionAction.Minimize, CaptionAction.ToggleMaximize, CaptionAction.Close),
            window.platform.captionActions,
        )
        window.host.close()
    }

    @Test
    fun windows_a_button_under_the_pointer_is_lit_and_the_move_outside_puts_it_out() {
        val window = opened(windows)
        assertEquals(strip, window.pixel(cornerOfMinimise))
        assertEquals(strip, window.pixel(cornerOfMaximise))
        assertEquals(strip, window.pixel(cornerOfClose))

        // The maximise button's moves are the window's non-client ones, passed on at the same point.
        window.move(overMaximise)
        assertNotEquals(strip, window.pixel(cornerOfMaximise), "maximise is lit under the pointer")
        assertEquals(strip, window.pixel(cornerOfMinimise))
        assertEquals(strip, window.pixel(cornerOfClose))

        // Along to close: one button is lit at a time, and close is red on Windows.
        window.move(overClose)
        assertEquals(strip, window.pixel(cornerOfMaximise))
        assertEquals(Color(0xFFC42B1C).toArgb(), window.pixel(cornerOfClose))

        // Off the window's edge: the window sends one move to a point outside (DXC_CAPTION_OUTSIDE).
        window.move(-1f to -1f)
        assertEquals(strip, window.pixel(cornerOfClose), "nothing stays lit once the pointer has left")
        window.host.close()
    }

    @Test
    fun windows_a_pressed_button_shows_it_and_a_release_elsewhere_does_nothing() {
        val window = opened(windows)
        window.move(overMaximise)
        val hovered = window.pixel(cornerOfMaximise)
        window.press(overMaximise)
        val pressed = window.pixel(cornerOfMaximise)
        assertNotEquals(hovered, pressed, "a held button looks held")
        assertNotEquals(strip, pressed)

        // Dragged off the button and let go over the content: the mouse is captured, so the
        // release arrives, the press ends and the window is not maximised.
        window.move(100f to 120f, held = WindowEvent.PRIMARY_HELD)
        window.release(100f to 120f)
        assertEquals(strip, window.pixel(cornerOfMaximise))
        assertEquals(emptyList(), window.platform.captionActions)
        window.host.close()
    }

    @Test
    fun windows_the_maximise_button_of_a_maximised_window_is_restore() {
        val window = opened(windows)
        window.platform.captionNow = Win32Chrome.caption(config, maximized = true)
        repeat(3) { window.host.turn(0) }
        val labels = window.platform.pushed.last().filter { it.role == ElementRole.BUTTON }.map { it.label }
        assertEquals(listOf("Minimize", "Restore", "Close"), labels)

        // Still the one button, in the same place, doing the same thing.
        window.press(overMaximise)
        window.release(overMaximise)
        assertEquals(listOf(CaptionAction.ToggleMaximize), window.platform.captionActions)
        window.host.close()
    }

    @Test
    fun windows_the_frame_is_the_systems_so_the_corner_of_close_closes_and_nothing_resizes() {
        val window = opened(windows)
        val corner = 298f to 2f
        window.press(corner)
        window.release(corner)
        assertEquals(listOf(CaptionAction.Close), window.platform.captionActions)
        assertEquals(emptyList(), window.platform.resizes)
        // Nor does a press at any other edge ask the platform for a resize.
        window.press(1f to 100f)
        window.release(1f to 100f)
        window.press(150f to 198f)
        window.release(150f to 198f)
        assertEquals(emptyList(), window.platform.resizes)
        window.host.close()
    }

    @Test
    fun linux_the_scene_still_resizes_at_the_edges_and_close_is_not_red() {
        val window = opened(X11Chrome.caption(config))
        window.press(298f to 2f)
        window.release(298f to 2f)
        assertEquals(listOf(WindowEdge.TopRight), window.platform.resizes)
        assertEquals(emptyList(), window.platform.captionActions)

        window.move(277f to 23f)
        val lit = window.pixel(290 to 40)
        assertNotEquals(Color(0xFFC42B1C).toArgb(), lit)
        assertNotEquals(strip, lit, "the button is lit under the pointer here too")
        window.host.close()
    }

    @Test
    fun the_fill_and_the_glyph_of_each_state() {
        for (theme in SystemTheme.entries) {
            assertEquals(Color.Transparent, captionButtonFill(CaptionButtonState.Idle, theme, closeIsRed = false))
            assertEquals(Color.Transparent, captionButtonFill(CaptionButtonState.Idle, theme, closeIsRed = true))
            val hovered = captionButtonFill(CaptionButtonState.Hovered, theme, closeIsRed = false)
            val pressed = captionButtonFill(CaptionButtonState.Pressed, theme, closeIsRed = false)
            assertTrue(hovered.alpha > 0f && pressed.alpha > hovered.alpha, "a press is stronger than a hover")
            assertEquals(Color(0xFFC42B1C), captionButtonFill(CaptionButtonState.Hovered, theme, closeIsRed = true))
            assertEquals(Color.White, captionGlyphColor(CaptionButtonState.Hovered, theme, closeIsRed = true))
            assertEquals(Color.White, captionGlyphColor(CaptionButtonState.Pressed, theme, closeIsRed = true))
            assertEquals(
                captionGlyphColor(CaptionButtonState.Idle, theme, closeIsRed = false),
                captionGlyphColor(CaptionButtonState.Idle, theme, closeIsRed = true),
            )
        }
    }
}
