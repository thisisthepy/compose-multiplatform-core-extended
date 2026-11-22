package org.thisisthepy.compose.window.linux

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import org.thisisthepy.compose.window.DoubleClick
import org.thisisthepy.compose.window.LinuxTheme
import org.thisisthepy.compose.window.SystemTheme
import org.thisisthepy.compose.window.TextScale
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fclose
import platform.posix.getenv
import platform.posix.pclose
import platform.posix.popen

/** Reads the desktop's text size: GNOME's `text-scaling-factor`, or KDE's forced font DPI over its global scale. */
internal object LinuxTextScale {
    /** Reads the settings now. A missing tool or file is an unset setting, not an error. */
    fun read(): Float = factor()?.factor ?: TextScale.DEFAULT

    /**
     * The text size and where it came from, null where no desktop answers it. GNOME and KDE
     * put it into `Xft.dpi` as well, which [org.thisisthepy.compose.window.X11Scale] takes it
     * out of again so that text is scaled once.
     */
    fun factor(): TextScale.LinuxTextFactor? = TextScale.linuxFactor(
        gnomeOutput = run("gsettings get org.gnome.desktop.interface text-scaling-factor 2>/dev/null"),
        kdeFontsConfig = kdeConfig("kcmfonts"),
        kdeGlobals = kdeConfig("kdeglobals"),
        currentDesktop = getenv("XDG_CURRENT_DESKTOP")?.toKString(),
    )

    internal fun kdeConfig(name: String): String? {
        val config = getenv("XDG_CONFIG_HOME")?.toKString()?.takeIf { it.isNotEmpty() }
            ?: getenv("HOME")?.toKString()?.let { "$it/.config" } ?: return null
        return readFile("$config/$name")
    }

    internal fun run(command: String): String? {
        val pipe = popen(command, "r") ?: return null
        val text = drain(pipe)
        pclose(pipe)
        return text
    }

    private fun readFile(path: String): String? {
        val file = fopen(path, "r") ?: return null
        val text = drain(file)
        fclose(file)
        return text
    }

    private fun drain(file: kotlinx.cinterop.CPointer<platform.posix.FILE>): String = memScoped {
        val line = allocArray<ByteVar>(512)
        val out = StringBuilder()
        while (out.length < 16384 && fgets(line, 512, file) != null) out.append(line.toKString())
        out.toString()
    }
}

/**
 * Reads the desktop's double-click interval: GNOME's `double-click`, else KDE's
 * `DoubleClickInterval`, else [DoubleClick.DEFAULT_MILLIS]. X11 itself has no such setting;
 * the XSETTINGS `Net/DoubleClickTime` a GNOME session also publishes is the same value.
 */
internal object LinuxDoubleClick {
    fun read(): Long = DoubleClick.resolveLinux(
        LinuxTextScale.run("gsettings get org.gnome.desktop.peripherals.mouse double-click 2>/dev/null"),
        LinuxTextScale.kdeConfig("kdeglobals"),
    )
}

/**
 * Reads the desktop's dark or light preference: `GTK_THEME` first, then the XDG desktop
 * portal `color-scheme`, then GNOME `gsettings` / KDE `kdeglobals` ordered by
 * `XDG_CURRENT_DESKTOP`, defaulting to [SystemTheme.Light].
 */
internal object LinuxSystemTheme {
    private const val PORTAL_COMMAND =
        "dbus-send --session --reply-timeout=200 --print-reply=literal " +
            "--dest=org.freedesktop.portal.Desktop /org/freedesktop/portal/desktop " +
            "org.freedesktop.portal.Settings.Read string:org.freedesktop.appearance string:color-scheme 2>/dev/null"

    fun read(): SystemTheme {
        val gtkTheme = getenv("GTK_THEME")?.toKString()
        LinuxTheme.fromGtkTheme(gtkTheme)?.let { return it }

        val portalOutput = if (!getenv("DBUS_SESSION_BUS_ADDRESS")?.toKString().isNullOrEmpty()) {
            LinuxTextScale.run(PORTAL_COMMAND)
        } else {
            null
        }
        LinuxTheme.fromPortalColorScheme(portalOutput)?.let { return it }

        val currentDesktop = getenv("XDG_CURRENT_DESKTOP")?.toKString()
        val onKde = LinuxTheme.isKdeDesktop(currentDesktop)
        var kdeGlobals: String? = null
        if (onKde) {
            kdeGlobals = LinuxTextScale.kdeConfig("kdeglobals")
            LinuxTheme.fromKdeGlobals(kdeGlobals)?.let { return it }
        }

        val gnomeColorScheme = LinuxTextScale.run("gsettings get org.gnome.desktop.interface color-scheme 2>/dev/null")
        LinuxTheme.fromGnomeColorScheme(gnomeColorScheme)?.let { return it }

        val gnomeGtkTheme = LinuxTextScale.run("gsettings get org.gnome.desktop.interface gtk-theme 2>/dev/null")
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
