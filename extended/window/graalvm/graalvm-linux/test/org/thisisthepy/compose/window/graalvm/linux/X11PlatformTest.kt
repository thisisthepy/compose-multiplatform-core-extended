package org.thisisthepy.compose.window.graalvm.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import org.thisisthepy.compose.window.FramePresentRecord

class X11PlatformTest {
    @Test
    fun presentRecordFlagsADrawnSizeThatDiffersFromTheWindow() {
        assertEquals(true, FramePresentRecord(100, 80, 120, 80).mismatched)
        assertEquals(false, FramePresentRecord(100, 80, 100, 80).mismatched)
    }
}

class X11PolicyTest {
    @kotlin.test.Test
    fun visibilityCodesFollowTheCOrder() {
        assertEquals(
            listOf(0, 1, 2, 3),
            listOf(
                org.thisisthepy.compose.window.WindowVisibility.Hidden,
                org.thisisthepy.compose.window.WindowVisibility.Visible,
                org.thisisthepy.compose.window.WindowVisibility.Minimized,
                org.thisisthepy.compose.window.WindowVisibility.Fullscreen,
            ).map(X11Visibility::code),
        )
    }

    @kotlin.test.Test
    fun gtkThemeSuffixSelectsDark() {
        val dark = org.thisisthepy.compose.window.SystemTheme.Dark
        val light = org.thisisthepy.compose.window.SystemTheme.Light
        assertEquals(dark, X11Theme.fromGtkTheme("Adwaita:dark"))
        assertEquals(dark, X11Theme.fromGtkTheme("Breeze-Dark"))
        assertEquals(light, X11Theme.fromGtkTheme("Adwaita"))
        assertEquals(light, X11Theme.fromGtkTheme(null))
    }
}
