package org.thisisthepy.compose.window

/**
 * The operating system's text size setting as a multiplier of the default, 1 when the user
 * has not changed it. Compose calls this `Density.fontScale`.
 *
 * The functions here only interpret what a platform layer read, so the rules for a missing,
 * malformed or out of range answer are the same on every platform and can be tested without
 * one.
 */
object TextScale {
    const val DEFAULT: Float = 1f
    const val MIN: Float = 0.5f
    const val MAX: Float = 3f

    /** The DPI a desktop treats as a scale of 1, which KDE's font DPI is divided by. */
    const val BASE_DPI: Float = 96f

    /** Keeps [scale] inside [MIN]..[MAX]; NaN and infinity fall back to [DEFAULT]. */
    fun clamp(scale: Float): Float = when {
        scale.isNaN() || scale.isInfinite() -> DEFAULT
        scale < MIN -> MIN
        scale > MAX -> MAX
        else -> scale
    }

    /**
     * `gsettings get org.gnome.desktop.interface text-scaling-factor` prints `1.25`, or
     * `double 1.25` when the schema is read through `dconf`. Null when it is neither.
     */
    fun fromGnome(output: String?): Float? {
        val text = output?.trim()?.removePrefix("double")?.trim() ?: return null
        val value = text.toFloatOrNull() ?: return null
        return if (value > 0f) clamp(value) else null
    }

    /**
     * KDE keeps a forced font DPI as `forceFontDPI=120` in `kcmfonts`; the scale is that DPI
     * over [BASE_DPI]. Null when the file names no usable value.
     */
    fun fromKdeFontsConfig(contents: String?): Float? = forcedFontDpiOf(contents)?.let { clamp(it / BASE_DPI) }

    /** The `forceFontDPI` of `kcmfonts`, null when it names none above 0 (0 is KDE's "not forced"). */
    private fun forcedFontDpiOf(contents: String?): Float? {
        val line = contents?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("forceFontDPI=") } ?: return null
        val dpi = line.substringAfter('=').trim().toFloatOrNull() ?: return null
        return if (dpi > 0f) dpi else null
    }

    /**
     * KDE's global scale, `ScaleFactor=2` in the `[KScreen]` group of `kdeglobals`, which the
     * display settings write. Null when the file names no usable value, which Plasma reads as 1.
     */
    fun kdeDesktopScaleOf(globals: String?): Float? {
        if (globals == null) return null
        var group = ""
        for (raw in globals.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                group = line
                continue
            }
            if (group != "[KScreen]" || !line.startsWith("ScaleFactor=")) continue
            val scale = line.substringAfter('=').trim().toFloatOrNull() ?: return null
            return if (scale > 0f && !scale.isInfinite()) scale else null
        }
        return null
    }

    /**
     * KDE's text size: the forced font DPI of `kcmfonts` ([fromKdeFontsConfig]) over 96 x the
     * desktop scale of `kdeglobals` ([kdeDesktopScaleOf], 1 where unset). Plasma on X11 writes
     * that DPI into `Xft.dpi`, and its display settings force it to 96 x the global scale when
     * they set one, so only what the DPI holds beyond the global scale is the text size. Null
     * when no font DPI is forced.
     */
    fun fromKde(fontsConfig: String?, globals: String?): Float? {
        val dpi = forcedFontDpiOf(fontsConfig) ?: return null
        return clamp(dpi / (BASE_DPI * (kdeDesktopScaleOf(globals) ?: DEFAULT)))
    }

    /**
     * Where a Linux desktop's text size came from: [factor] is the text scale the window
     * reports, and the same factor the desktop multiplied into `Xft.dpi`, on top of
     * [desktopScale] where the desktop names that scale (KDE), null where it does not (GNOME,
     * whose window scales are whole numbers). [X11Scale.scaleOf] takes it out of `Xft.dpi`
     * again so that text is scaled once.
     */
    class LinuxTextFactor(val factor: Float, val desktopScale: Float?)

    /**
     * The Linux text size: GNOME's when it answers, then KDE's, except on a KDE session
     * ([currentDesktop] is `XDG_CURRENT_DESKTOP`) where KDE's comes first, since `gsettings`
     * is often installed there too and answers its default. Null when neither answers.
     */
    fun linuxFactor(
        gnomeOutput: String?,
        kdeFontsConfig: String?,
        kdeGlobals: String? = null,
        currentDesktop: String? = null,
    ): LinuxTextFactor? {
        val gnome = fromGnome(gnomeOutput)?.let { LinuxTextFactor(it, null) }
        val kde = fromKde(kdeFontsConfig, kdeGlobals)?.let {
            LinuxTextFactor(it, kdeDesktopScaleOf(kdeGlobals) ?: DEFAULT)
        }
        val onKde = currentDesktop?.split(':')?.any { it.trim().equals("KDE", ignoreCase = true) } == true
        return if (onKde) kde ?: gnome else gnome ?: kde
    }

    /** GNOME wins when it answers, then KDE, then [DEFAULT] (see [linuxFactor]). */
    fun resolveLinux(
        gnomeOutput: String?,
        kdeFontsConfig: String?,
        kdeGlobals: String? = null,
        currentDesktop: String? = null,
    ): Float = linuxFactor(gnomeOutput, kdeFontsConfig, kdeGlobals, currentDesktop)?.factor ?: DEFAULT

    /**
     * Windows keeps the Text size slider as a percent (100 to 225) in
     * `HKCU\Software\Microsoft\Accessibility\TextScaleFactor`, which is what
     * `UISettings.TextScaleFactor` reports. A value of 0 or less means the key is unset.
     */
    fun fromWindowsPercent(percent: Int): Float = if (percent <= 0) DEFAULT else clamp(percent / 100f)
}
