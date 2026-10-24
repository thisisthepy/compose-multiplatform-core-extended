package org.thisisthepy.compose.window.linux

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import org.thisisthepy.compose.window.TextScale
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fclose
import platform.posix.getenv
import platform.posix.pclose
import platform.posix.popen

/** Reads the desktop's text size: GNOME's `text-scaling-factor`, else KDE's forced font DPI. */
internal object LinuxTextScale {
    /** Reads both settings now. A missing tool or file is an unset setting, not an error. */
    fun read(): Float = TextScale.resolveLinux(
        gnomeOutput = run("gsettings get org.gnome.desktop.interface text-scaling-factor 2>/dev/null"),
        kdeFontsConfig = kdeFonts(),
    )

    private fun kdeFonts(): String? {
        val config = getenv("XDG_CONFIG_HOME")?.toKString()?.takeIf { it.isNotEmpty() }
            ?: getenv("HOME")?.toKString()?.let { "$it/.config" } ?: return null
        return readFile("$config/kcmfonts")
    }

    private fun run(command: String): String? {
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
