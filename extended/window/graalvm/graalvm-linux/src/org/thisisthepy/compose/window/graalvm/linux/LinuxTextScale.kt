package org.thisisthepy.compose.window.graalvm.linux

import java.io.File
import java.util.concurrent.TimeUnit
import org.thisisthepy.compose.window.DoubleClick
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

    private fun gsettings(
        schema: String = "org.gnome.desktop.interface",
        key: String = "text-scaling-factor",
    ): String? = try {
        val process = ProcessBuilder("gsettings", "get", schema, key)
            .redirectErrorStream(false)
            .start()
        process.errorStream.close()
        val text = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        text
    } catch (_: Exception) {
        null
    }

    private fun kdeConfig(name: String): String? {
        val base = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotEmpty() }
            ?: System.getenv("HOME")?.let { "$it/.config" } ?: return null
        return try {
            File(base, name).takeIf { it.isFile }?.readText()
        } catch (_: Exception) {
            null
        }
    }
}
