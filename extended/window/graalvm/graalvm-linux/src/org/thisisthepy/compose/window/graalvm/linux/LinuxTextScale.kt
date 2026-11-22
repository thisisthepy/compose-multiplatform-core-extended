package org.thisisthepy.compose.window.graalvm.linux

import java.io.File
import java.util.concurrent.TimeUnit
import org.thisisthepy.compose.window.DoubleClick
import org.thisisthepy.compose.window.LinuxTheme
import org.thisisthepy.compose.window.SystemTheme
import org.thisisthepy.compose.window.TextScale

/** Reads the desktop's text size: GNOME's `text-scaling-factor`, or KDE's forced font DPI over its global scale. */
internal object LinuxTextScale {
    fun read(): Float = factor()?.factor ?: TextScale.DEFAULT

    /**
     * The text size and where it came from, null where no desktop answers it. GNOME and KDE
     * put it into `Xft.dpi` as well, which x11_window.c takes it out of again (told through
     * [X11Natives.setTextFactorFor]) so that text is scaled once.
     */
    fun factor(): TextScale.LinuxTextFactor? = TextScale.linuxFactor(
        gnomeOutput = gsettings(),
        kdeFontsConfig = kdeConfig("kcmfonts"),
        kdeGlobals = kdeConfig("kdeglobals"),
        currentDesktop = System.getenv("XDG_CURRENT_DESKTOP"),
    )

    /**
     * The desktop's double-click interval: GNOME's `double-click`, else KDE's
     * `DoubleClickInterval` in `kdeglobals`, else [DoubleClick.DEFAULT_MILLIS].
     */
    fun doubleClickMillis(): Long = DoubleClick.resolveLinux(
        gsettings("org.gnome.desktop.peripherals.mouse", "double-click"),
        kdeConfig("kdeglobals"),
    )

    internal fun gsettings(
        schema: String = "org.gnome.desktop.interface",
        key: String = "text-scaling-factor",
    ): String? = runCommand("gsettings", "get", schema, key)

    internal fun runCommand(vararg command: String): String? = try {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(false)
            .start()
        process.errorStream.close()
        val text = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        text
    } catch (_: Exception) {
        null
    }

    internal fun kdeConfig(name: String): String? {
        val base = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotEmpty() }
            ?: System.getenv("HOME")?.let { "$it/.config" } ?: return null
        return try {
            File(base, name).takeIf { it.isFile }?.readText()
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Reads the desktop's dark or light preference: `GTK_THEME` first, then the XDG desktop
 * portal `color-scheme`, then GNOME `gsettings` / KDE `kdeglobals` ordered by
 * `XDG_CURRENT_DESKTOP`, defaulting to [SystemTheme.Light].
 */
internal object LinuxSystemTheme {
    fun read(): SystemTheme {
        val gtkTheme = System.getenv("GTK_THEME")
        LinuxTheme.fromGtkTheme(gtkTheme)?.let { return it }

        val portalOutput = if (!System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrEmpty()) {
            LinuxTextScale.runCommand(
                "dbus-send",
                "--session",
                "--reply-timeout=200",
                "--print-reply=literal",
                "--dest=org.freedesktop.portal.Desktop",
                "/org/freedesktop/portal/desktop",
                "org.freedesktop.portal.Settings.Read",
                "string:org.freedesktop.appearance",
                "string:color-scheme",
            )
        } else {
            null
        }
        LinuxTheme.fromPortalColorScheme(portalOutput)?.let { return it }

        val currentDesktop = System.getenv("XDG_CURRENT_DESKTOP")
        val onKde = LinuxTheme.isKdeDesktop(currentDesktop)
        var kdeGlobals: String? = null
        if (onKde) {
            kdeGlobals = LinuxTextScale.kdeConfig("kdeglobals")
            LinuxTheme.fromKdeGlobals(kdeGlobals)?.let { return it }
        }

        val gnomeColorScheme = LinuxTextScale.gsettings("org.gnome.desktop.interface", "color-scheme")
        LinuxTheme.fromGnomeColorScheme(gnomeColorScheme)?.let { return it }

        val gnomeGtkTheme = LinuxTextScale.gsettings("org.gnome.desktop.interface", "gtk-theme")
        LinuxTheme.fromGnomeGtkTheme(gnomeGtkTheme)?.let { return it }

        if (!onKde) {
            kdeGlobals = LinuxTextScale.kdeConfig("kdeglobals")
        }
        return LinuxTheme.resolve(
            gtkTheme = gtkTheme,
            portalOutput = portalOutput,
            gnomeColorScheme = gnomeColorScheme,
            gnomeGtkTheme = gnomeGtkTheme,
            kdeGlobals = kdeGlobals,
            currentDesktop = currentDesktop,
        )
    }
}
