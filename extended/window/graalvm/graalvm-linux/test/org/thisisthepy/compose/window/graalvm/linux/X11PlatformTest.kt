package org.thisisthepy.compose.window.graalvm.linux

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.thisisthepy.compose.window.FramePresentRecord
import org.thisisthepy.compose.window.LinuxTheme
import org.thisisthepy.compose.window.SystemTheme
import org.thisisthepy.compose.window.X11Scale
import org.thisisthepy.compose.window.X11Title

class X11PlatformTest {
    @Test
    fun presentRecordFlagsADrawnSizeThatDiffersFromTheWindow() {
        assertEquals(true, FramePresentRecord(100, 80, 120, 80).mismatched)
        assertEquals(false, FramePresentRecord(100, 80, 100, 80).mismatched)
    }

    @Test
    fun dpiParsingHandlesGdkScaleXftDpiAndDefaultFallback() {
        assertEquals(2f, X11Scale.scaleOf(null, gdkScale = "2"))
        assertEquals(192.0, X11Scale.dpiOf("Xft.antialias:\t1\nXft.dpi:\t192\n"))
        assertEquals(2f, X11Scale.scaleOf("Xft.dpi:\t192\n"))
        assertEquals(1.5f, X11Scale.scaleOf("Xft.dpi:\t144\n"))
        assertEquals(1f, X11Scale.scaleOf("Xft.dpi:\t72\n"))
        assertEquals(1f, X11Scale.scaleOf(null, gdkScale = "0"))
        assertEquals(1f, X11Scale.scaleOf("Xft.dpi:\tinvalid\n"))
    }

    @Test
    fun netWmNameHelperEncodesUtf8WithoutTrailingOrEmbeddedNul() {
        assertContentEquals("Hello".encodeToByteArray(), X11Title.netWmNameBytes("Hello"))
        val unicode = "Compose 한글 타이틀"
        val encoded = X11Title.netWmNameBytes(unicode)
        assertEquals(unicode, encoded.decodeToString())
        assertEquals(0, X11Title.netWmNameBytes("").size)
        assertContentEquals("ab".encodeToByteArray(), X11Title.netWmNameBytes("a\u0000b\u0000"))
    }
}

class X11PolicyTest {
    @Test
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

    @Test
    fun gtkThemeSuffixSelectsDark() {
        assertEquals(SystemTheme.Dark, X11Theme.fromGtkTheme("Adwaita:dark"))
        assertEquals(SystemTheme.Dark, X11Theme.fromGtkTheme("Breeze-Dark"))
        assertEquals(SystemTheme.Light, X11Theme.fromGtkTheme("Adwaita"))
        assertEquals(SystemTheme.Light, X11Theme.fromGtkTheme(null))
    }

    @Test
    fun linuxThemePrefersGtkThemeAndFallsBackToPortalGsettingsAndKde() {
        assertEquals(SystemTheme.Dark, LinuxTheme.fromGtkTheme("Adwaita:dark"))
        assertEquals(SystemTheme.Light, LinuxTheme.fromGtkTheme("Adwaita:light"))
        assertNull(LinuxTheme.fromGtkTheme("Adwaita"))
        assertNull(LinuxTheme.fromGtkTheme(null))

        assertEquals(SystemTheme.Dark, LinuxTheme.fromPortalColorScheme("   variant       variant          uint32 1\n"))
        assertEquals(SystemTheme.Light, LinuxTheme.fromPortalColorScheme("(<uint32 2>,)"))
        assertNull(LinuxTheme.fromPortalColorScheme("   variant       variant          uint32 0\n"))
        assertNull(LinuxTheme.fromPortalColorScheme(null))

        assertEquals(SystemTheme.Dark, LinuxTheme.fromGnomeColorScheme("'prefer-dark'\n"))
        assertEquals(SystemTheme.Light, LinuxTheme.fromGnomeColorScheme("'prefer-light'\n"))
        assertNull(LinuxTheme.fromGnomeColorScheme("'default'\n"))

        assertEquals(SystemTheme.Dark, LinuxTheme.fromGnomeGtkTheme("'Yaru-dark'\n"))
        assertNull(LinuxTheme.fromGnomeGtkTheme("'Yaru'\n"))

        val kdeDark = "[General]\nColorScheme=BreezeDark\n"
        val kdeLight = "[General]\nColorScheme=BreezeLight\n"
        assertEquals(SystemTheme.Dark, LinuxTheme.fromKdeGlobals(kdeDark))
        assertEquals(SystemTheme.Light, LinuxTheme.fromKdeGlobals(kdeLight))

        // GTK_THEME takes precedence over portal and desktop settings.
        assertEquals(
            SystemTheme.Light,
            LinuxTheme.resolve(
                gtkTheme = "Adwaita:light",
                portalOutput = "variant uint32 1",
                gnomeColorScheme = "'prefer-dark'",
                kdeGlobals = kdeDark,
            ),
        )
        // Inconclusive GTK_THEME falls back to portal, then gsettings/kdeglobals.
        assertEquals(
            SystemTheme.Dark,
            LinuxTheme.resolve(gtkTheme = "Adwaita", portalOutput = "variant uint32 1"),
        )
        assertEquals(
            SystemTheme.Dark,
            LinuxTheme.resolve(gtkTheme = null, portalOutput = "uint32 0", gnomeColorScheme = "'prefer-dark'"),
        )
        assertEquals(
            SystemTheme.Dark,
            LinuxTheme.resolve(
                gtkTheme = null,
                gnomeColorScheme = "'prefer-light'",
                kdeGlobals = kdeDark,
                currentDesktop = "KDE",
            ),
        )
        assertEquals(SystemTheme.Light, LinuxTheme.resolve(gtkTheme = null))
    }
}

class X11DroppedPathsTest {
    @Test
    fun droppedPathsSplitAtNulAndKeepSpaces() {
        assertEquals(listOf("/tmp/a b.txt", "/tmp/\u00fc"), splitDroppedPaths("/tmp/a b.txt\u0000/tmp/\u00fc"))
        assertEquals(emptyList(), splitDroppedPaths(""))
    }
}
