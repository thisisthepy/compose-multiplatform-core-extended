package org.thisisthepy.compose.window

/**
 * How many pixels go to a point on an X11 display, from what the desktop wrote down.
 *
 * X11 has no scale of its own. Desktops say it through `Xft.dpi` in the `RESOURCE_MANAGER`
 * property of the root window (what `xrdb -query` prints), 96 being a scale of 1, and a user
 * can force one with `GDK_SCALE`. The functions here only interpret what a platform layer
 * read, so both X11 windows answer the same and the rules can be tested without a display.
 */
object X11Scale {
    const val DEFAULT: Float = 1f

    /** The DPI that is a scale of 1. */
    const val BASE_DPI: Double = 96.0

    /**
     * The resource names that answer `Xft.dpi` (class `Xft.Dpi`), the most specific first,
     * which is the order Xlib's resource manager prefers them in: a tight binding before a
     * loose one and a name before a class.
     */
    private val DPI_NAMES = listOf(
        "Xft.dpi", "Xft.Dpi", "Xft*dpi", "Xft*Dpi",
        "*Xft.dpi", "*Xft.Dpi", "*Xft*dpi", "*Xft*Dpi",
        "*dpi", "*Dpi",
    )

    /**
     * The `Xft.dpi` a resource string names, or null where it names none that is a positive
     * number. [resources] is the text of `RESOURCE_MANAGER`: one `name: value` entry per line,
     * `!` starting a comment.
     */
    fun dpiOf(resources: String?): Double? {
        if (resources == null) return null
        val found = HashMap<String, String>()
        for (raw in resources.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("!")) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).filterNot { it == ' ' || it == '\t' }
            if (name in DPI_NAMES) found[name] = line.substring(colon + 1).trim()
        }
        for (name in DPI_NAMES) {
            val dpi = found[name]?.toDoubleOrNull() ?: continue
            if (dpi.isNaN() || dpi.isInfinite() || dpi <= 0.0) continue
            return dpi
        }
        return null
    }

    /**
     * The scale: `GDK_SCALE` ([gdkScale]) when it is set to 1 or more, else `Xft.dpi` over
     * [BASE_DPI], else [DEFAULT]. A DPI under 96 is a scale of 1, as the GraalVM X11 window
     * has always read it: a desktop that writes 72 or 90 means fonts, not a smaller window.
     */
    fun scaleOf(resources: String?, gdkScale: String? = null): Float {
        val forced = gdkScale?.trim()?.toDoubleOrNull()
        if (forced != null && !forced.isNaN() && !forced.isInfinite() && forced >= 1.0) return forced.toFloat()
        val dpi = dpiOf(resources) ?: return DEFAULT
        return if (dpi < BASE_DPI) DEFAULT else (dpi / BASE_DPI).toFloat()
    }
}
