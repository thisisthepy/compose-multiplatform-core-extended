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
    fun fromKdeFontsConfig(contents: String?): Float? {
        val line = contents?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("forceFontDPI=") } ?: return null
        val dpi = line.substringAfter('=').trim().toFloatOrNull() ?: return null
        return if (dpi > 0f) clamp(dpi / BASE_DPI) else null
    }

    /** GNOME wins when it answers, then KDE, then [DEFAULT]. */
    fun resolveLinux(gnomeOutput: String?, kdeFontsConfig: String?): Float =
        fromGnome(gnomeOutput) ?: fromKdeFontsConfig(kdeFontsConfig) ?: DEFAULT

    /**
     * Windows keeps the Text size slider as a percent (100 to 225) in
     * `HKCU\Software\Microsoft\Accessibility\TextScaleFactor`, which is what
     * `UISettings.TextScaleFactor` reports. A value of 0 or less means the key is unset.
     */
    fun fromWindowsPercent(percent: Int): Float = if (percent <= 0) DEFAULT else clamp(percent / 100f)
}
