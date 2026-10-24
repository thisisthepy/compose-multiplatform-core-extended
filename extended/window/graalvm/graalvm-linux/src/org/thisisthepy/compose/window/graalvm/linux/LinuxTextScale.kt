package org.thisisthepy.compose.window.graalvm.linux

import java.io.File
import java.util.concurrent.TimeUnit
import org.thisisthepy.compose.window.TextScale

/** Reads the desktop's text size: GNOME's `text-scaling-factor`, else KDE's forced font DPI. */
internal object LinuxTextScale {
    fun read(): Float = TextScale.resolveLinux(gnomeOutput = gsettings(), kdeFontsConfig = kdeFonts())

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
