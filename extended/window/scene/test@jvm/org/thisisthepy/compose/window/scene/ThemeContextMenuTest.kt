package org.thisisthepy.compose.window.scene

import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.DarkDefaultContextMenuRepresentation
import androidx.compose.foundation.LightDefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.thisisthepy.compose.window.SystemTheme
import org.thisisthepy.compose.window.WindowConfig

class ThemeContextMenuTest {

    @Test
    fun jvm_drawn_context_menu_representation_follows_system_theme() {
        val platform = FakePlatform().also {
            it.systemMenu = false
            it.themeNow = SystemTheme.Light
        }
        val surface = FakeSurface()
        var currentRepresentation: ContextMenuRepresentation? = null
        val host = ComposeWindowHost(platform, WindowConfig("theme-menu", 200, 100), { surface }) {
            currentRepresentation = LocalContextMenuRepresentation.current
            Box(Modifier.size(200.dp, 100.dp))
        }
        assertTrue(host.open())
        repeat(2) { host.turn(0) }
        assertEquals(LightDefaultContextMenuRepresentation, currentRepresentation)

        platform.themeChanges += SystemTheme.Dark
        repeat(3) { host.turn(0) }
        assertEquals(DarkDefaultContextMenuRepresentation, currentRepresentation)
        host.close()
    }
}
