package org.thisisthepy.compose.window.scene

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import org.thisisthepy.compose.window.EditMenuId
import org.thisisthepy.compose.window.SystemTheme
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent

private class PixelSurface : WindowSurface {
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

class DrawnEditMenuTest {

    @Test
    fun paste_item_is_disabled_when_clipboard_is_null_or_empty_and_enabled_when_non_empty() {
        val platform = FakePlatform()

        platform.clipboardText = null
        val whenNull = drawnEditMenuItems(platform)
        assertFalse(whenNull.first { it.id == EditMenuId.PASTE }.enabled)
        assertTrue(whenNull.first { it.id == EditMenuId.CUT }.enabled)
        assertTrue(whenNull.first { it.id == EditMenuId.COPY }.enabled)
        assertTrue(whenNull.first { it.id == EditMenuId.SELECT_ALL }.enabled)

        platform.clipboardText = ""
        val whenEmpty = drawnEditMenuItems(platform)
        assertFalse(whenEmpty.first { it.id == EditMenuId.PASTE }.enabled)

        platform.clipboardText = "copied text"
        val whenPopulated = drawnEditMenuItems(platform)
        assertTrue(whenPopulated.first { it.id == EditMenuId.PASTE }.enabled)
    }

    @Test
    fun dark_and_light_palettes_have_distinct_contrasting_colors() {
        val light = drawnEditMenuColors(SystemTheme.Light)
        val dark = drawnEditMenuColors(SystemTheme.Dark)
        assertNotEquals(light.background, dark.background)
        assertNotEquals(light.border, dark.border)
        assertNotEquals(light.text, dark.text)
        assertNotEquals(light.disabledText, dark.disabledText)
        assertTrue(light.background.luminance() > dark.background.luminance())
        assertTrue(dark.text.luminance() > light.text.luminance())
    }

    @Test
    fun clicking_disabled_paste_does_not_fire_on_chosen_while_enabled_paste_does() {
        val platform = FakePlatform().also { it.clipboardText = null }
        val surface = FakeSurface()
        val chosen = ArrayList<Int>()
        val host = ComposeWindowHost(platform, WindowConfig("menu", 200, 200), { surface }) {
            DrawnEditMenu(
                at = IntOffset(0, 0),
                platform = platform,
                onChosen = { chosen += it },
                onDismiss = {},
            )
        }
        assertTrue(host.open())
        repeat(3) { host.turn(0) }

        // Sweep across all vertical menu rows so the click probes are independent of platform font metrics.
        for (y in 10..120 step 8) {
            platform.queued.add(WindowEvent(WindowEvent.POINTER_DOWN, 30f, y.toFloat(), WindowEvent.PRIMARY_HELD, 0, 0, 0, ""))
            platform.queued.add(WindowEvent(WindowEvent.POINTER_UP, 30f, y.toFloat(), 0, 0, 0, 0, ""))
            repeat(2) { host.turn(0) }
        }
        assertTrue(EditMenuId.COPY in chosen, "enabled Copy should fire: $chosen")
        assertFalse(EditMenuId.PASTE in chosen, "disabled Paste must not fire when clipboard is empty: $chosen")
        host.close()

        // Now with clipboard text present, Paste is clickable and fires EditMenuId.PASTE.
        val populated = FakePlatform().also { it.clipboardText = "paste me" }
        val chosenPopulated = ArrayList<Int>()
        val host2 = ComposeWindowHost(populated, WindowConfig("menu", 200, 200), { FakeSurface() }) {
            DrawnEditMenu(
                at = IntOffset(0, 0),
                platform = populated,
                onChosen = { chosenPopulated += it },
                onDismiss = {},
            )
        }
        assertTrue(host2.open())
        repeat(3) { host2.turn(0) }
        for (y in 10..120 step 8) {
            populated.queued.add(WindowEvent(WindowEvent.POINTER_DOWN, 30f, y.toFloat(), WindowEvent.PRIMARY_HELD, 0, 0, 0, ""))
            populated.queued.add(WindowEvent(WindowEvent.POINTER_UP, 30f, y.toFloat(), 0, 0, 0, 0, ""))
            repeat(2) { host2.turn(0) }
        }
        assertTrue(EditMenuId.PASTE in chosenPopulated, "enabled Paste should fire when clipboard has text: $chosenPopulated")
        host2.close()
    }

    @Test
    fun drawn_edit_menu_renders_dark_and_light_backgrounds_from_local_system_theme() {
        val platform = FakePlatform().also { it.themeNow = SystemTheme.Light }
        val surface = PixelSurface()
        val host = ComposeWindowHost(platform, WindowConfig("theme", 200, 200), { surface }) {
            DrawnEditMenu(
                at = IntOffset(10, 10),
                platform = platform,
                onChosen = {},
                onDismiss = {},
            )
        }
        assertTrue(host.open())
        repeat(3) { host.turn(0) }
        // Sample inside the right margin of the 140px-wide menu where background is unobstructed by text.
        val lightPixel = assertNotNull(surface.last).getColor(140, 20)
        assertEquals(drawnEditMenuColors(SystemTheme.Light).background.toArgb(), lightPixel)

        platform.themeChanges += SystemTheme.Dark
        repeat(3) { host.turn(0) }
        val darkPixel = assertNotNull(surface.last).getColor(140, 20)
        assertEquals(drawnEditMenuColors(SystemTheme.Dark).background.toArgb(), darkPixel)
        assertNotEquals(lightPixel, darkPixel)
        host.close()
    }
}
