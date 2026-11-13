package org.thisisthepy.compose.window

/**
 * The operating system's double-click interval: how long after one press another press at
 * the same place still makes it a double click.
 *
 * The scene hands it to Compose as `ViewConfiguration.doubleTapTimeoutMillis`, whose own
 * default of 300 ms is shorter than every desktop's (macOS and Windows ship 500 ms), so a
 * double click at the pace the user set up their system for read as two single clicks.
 *
 * The functions here only interpret what a platform layer read, so the rules for a missing,
 * malformed or out of range answer are the same on every platform and can be tested without
 * one.
 */
object DoubleClick {
    /** What a platform that cannot ask answers, and what macOS and Windows ship with. */
    const val DEFAULT_MILLIS: Long = 500
    const val MIN_MILLIS: Long = 100
    const val MAX_MILLIS: Long = 5000

    /** Keeps [millis] inside [MIN_MILLIS]..[MAX_MILLIS]; zero or less means unset, so [DEFAULT_MILLIS]. */
    fun clamp(millis: Long): Long = when {
        millis <= 0 -> DEFAULT_MILLIS
        millis < MIN_MILLIS -> MIN_MILLIS
        millis > MAX_MILLIS -> MAX_MILLIS
        else -> millis
    }

    /** `NSEvent.doubleClickInterval`, which is in seconds. */
    fun fromSeconds(seconds: Double): Long =
        if (seconds.isNaN() || seconds.isInfinite()) DEFAULT_MILLIS else clamp((seconds * 1000).toLong())

    /**
     * `gsettings get org.gnome.desktop.peripherals.mouse double-click` prints `400`, or
     * `int32 400` when the schema is read through `dconf`. Null when it is neither.
     */
    fun fromGnome(output: String?): Long? {
        val text = output?.trim()?.removePrefix("int32")?.trim() ?: return null
        val value = text.toLongOrNull() ?: return null
        return if (value > 0) clamp(value) else null
    }

    /**
     * KDE keeps the interval as `DoubleClickInterval=400` in the `[KDE]` group of
     * `kdeglobals`. Null when the file names no usable value.
     */
    fun fromKdeGlobals(contents: String?): Long? {
        var group = ""
        for (raw in contents?.lineSequence() ?: return null) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                group = line
                continue
            }
            if (group != "[KDE]" || !line.startsWith("DoubleClickInterval=")) continue
            val value = line.substringAfter('=').trim().toLongOrNull() ?: return null
            return if (value > 0) clamp(value) else null
        }
        return null
    }

    /** GNOME wins when it answers, then KDE, then [DEFAULT_MILLIS]. */
    fun resolveLinux(gnomeOutput: String?, kdeGlobals: String?): Long =
        fromGnome(gnomeOutput) ?: fromKdeGlobals(kdeGlobals) ?: DEFAULT_MILLIS
}
