package org.thisisthepy.compose.window

/**
 * Resolves the Linux desktop dark or light appearance from environment variables, the XDG
 * desktop portal, GNOME `gsettings` and KDE `kdeglobals`.
 *
 * The functions here only interpret what a platform layer read, so both X11 windows follow
 * the same precedence and every fallback rule can be tested without a desktop session.
 */
object LinuxTheme {
    /**
     * Checks a `GTK_THEME` value (for example `Adwaita:dark`, `Breeze-Dark`, `Adwaita:light`).
     * Returns null when unset or when the name carries no dark or light suffix, so callers can
     * consult desktop settings before defaulting to [SystemTheme.Light].
     */
    fun fromGtkTheme(value: String?): SystemTheme? {
        val normalized = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            normalized == "dark" || normalized.endsWith(":dark") || normalized.endsWith("-dark") -> SystemTheme.Dark
            normalized == "light" || normalized.endsWith(":light") || normalized.endsWith("-light") -> SystemTheme.Light
            else -> null
        }
    }

    /**
     * Parses the reply of `org.freedesktop.portal.Settings.Read("org.freedesktop.appearance",
     * "color-scheme")`, as printed by `dbus-send --print-reply=literal` or `gdbus call`.
     *
     * Per the portal specification: 1 is prefer-dark, 2 is prefer-light, 0 is no preference.
     */
    fun fromPortalColorScheme(output: String?): SystemTheme? {
        val text = output?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lower = text.lowercase()
        val marker = "uint32"
        val number = if (marker in lower) {
            val after = lower.substringAfterLast(marker).trim()
            val digits = after.takeWhile { it.isDigit() }
            digits.toIntOrNull()
        } else {
            lower.toIntOrNull()
        }
        return when (number) {
            1 -> SystemTheme.Dark
            2 -> SystemTheme.Light
            else -> null
        }
    }

    /**
     * Parses `gsettings get org.gnome.desktop.interface color-scheme`, which prints
     * `'prefer-dark'`, `'prefer-light'` or `'default'`.
     */
    fun fromGnomeColorScheme(output: String?): SystemTheme? {
        val value = unquote(output)?.lowercase() ?: return null
        return when (value) {
            "prefer-dark" -> SystemTheme.Dark
            "prefer-light" -> SystemTheme.Light
            else -> null
        }
    }

    /** Parses `gsettings get org.gnome.desktop.interface gtk-theme`. */
    fun fromGnomeGtkTheme(output: String?): SystemTheme? = fromGtkTheme(unquote(output))

    /**
     * Reads `ColorScheme=` under `[General]` in KDE's `kdeglobals` (for example `BreezeDark`
     * or `BreezeLight`). Returns null when absent or inconclusive.
     */
    fun fromKdeGlobals(contents: String?): SystemTheme? {
        if (contents == null) return null
        var group = ""
        for (raw in contents.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                group = line
                continue
            }
            if (group != "[General]" || !line.startsWith("ColorScheme=")) continue
            val scheme = line.substringAfter('=').trim().lowercase()
            return when {
                "dark" in scheme -> SystemTheme.Dark
                "light" in scheme -> SystemTheme.Light
                else -> null
            }
        }
        return null
    }

    /** Whether `XDG_CURRENT_DESKTOP` names a KDE session. */
    fun isKdeDesktop(currentDesktop: String?): Boolean =
        currentDesktop?.split(':')?.any { it.trim().equals("KDE", ignoreCase = true) } == true

    /**
     * Resolves the Linux appearance: `GTK_THEME` first when conclusive, then the XDG desktop
     * portal `color-scheme`, then desktop-specific settings (KDE `kdeglobals` before GNOME
     * `gsettings` on a KDE session, GNOME before KDE otherwise), defaulting to [SystemTheme.Light].
     */
    fun resolve(
        gtkTheme: String?,
        portalOutput: String? = null,
        gnomeColorScheme: String? = null,
        gnomeGtkTheme: String? = null,
        kdeGlobals: String? = null,
        currentDesktop: String? = null,
    ): SystemTheme {
        fromGtkTheme(gtkTheme)?.let { return it }
        fromPortalColorScheme(portalOutput)?.let { return it }
        return if (isKdeDesktop(currentDesktop)) {
            fromKdeGlobals(kdeGlobals)
                ?: fromGnomeColorScheme(gnomeColorScheme)
                ?: fromGnomeGtkTheme(gnomeGtkTheme)
                ?: SystemTheme.Light
        } else {
            fromGnomeColorScheme(gnomeColorScheme)
                ?: fromGnomeGtkTheme(gnomeGtkTheme)
                ?: fromKdeGlobals(kdeGlobals)
                ?: SystemTheme.Light
        }
    }

    private fun unquote(output: String?): String? {
        val trimmed = output?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (
            (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length >= 2) ||
            (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length >= 2)
        ) {
            trimmed.substring(1, trimmed.length - 1).trim().takeIf { it.isNotEmpty() }
        } else {
            trimmed
        }
    }
}
