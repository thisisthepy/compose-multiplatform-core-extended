package org.thisisthepy.compose.window.graalvm.linux

import java.io.File
import java.util.concurrent.TimeUnit
import org.thisisthepy.compose.window.TextScale

/** Reads the desktop's text size: GNOME's `text-scaling-factor`, else KDE's forced font DPI. */
internal object LinuxTextScale {
    fun read(): Float = resolve(gnome())

    /** The text scale where GNOME's factor is [gnome]: that, else KDE's, else the default. */
    fun resolve(gnome: Float?): Float = gnome ?: TextScale.fromKdeFontsConfig(kdeFonts()) ?: TextScale.DEFAULT

    /**
     * GNOME's `text-scaling-factor` alone, null where `gsettings` does not answer it. GNOME
     * puts it into `Xft.dpi` as well, which x11_window.c takes it out of again (told through
     * [X11Natives.setTextFactor]) so that text is scaled once.
     */
    fun gnome(): Float? = TextScale.fromGnome(gsettings())

    private fun gsettings(): String? = try {
        val process = ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "text-scaling-factor")
            .redirectErrorStream(false)
            .start()
        process.errorStream.close()
        val text = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        text
    } catch (_: Exception) {
        null
    }

    private fun kdeFonts(): String? {
        val base = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotEmpty() }
            ?: System.getenv("HOME")?.let { "$it/.config" } ?: return null
        return try {
            File(base, "kcmfonts").takeIf { it.isFile }?.readText()
        } catch (_: Exception) {
            null
        }
    }
}
