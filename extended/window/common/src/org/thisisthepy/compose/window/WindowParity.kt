package org.thisisthepy.compose.window

// What every platform layer decides the same way, once.
//
// The native and GraalVM layers each make the operating system calls in their own language.
// Everything that is a decision rather than a call lives here and is reached from both: the
// smallest size a window is given, when the Dock icon is ready to be put on, how a change of
// the system's light or dark setting or scale is noticed, and what text on the clipboard
// means. Nothing here names a platform.

/**
 * The smallest content area the window may be dragged to, in points, or null where the
 * application named none. A measurement of zero means it did not ask; one axis may be named
 * without the other.
 */
fun contentMinimum(minWidth: Int, minHeight: Int): Pair<Double, Double>? =
    if (minWidth <= 0 && minHeight <= 0) {
        null
    } else {
        minWidth.coerceAtLeast(0).toDouble() to minHeight.coerceAtLeast(0).toDouble()
    }

/**
 * Puts the application's picture on the Dock or the task bar, once the asset it named has
 * arrived.
 *
 * The id is known from the first batch and the picture a little later, so [tryApply] is asked
 * until it succeeds and does nothing afterwards.
 */
class DockIcon<T : Any>(
    private val lookup: (Int) -> T?,
    private val apply: (T) -> Unit,
) {
    var applied = false
        private set

    /** True once the picture is on the Dock, from this call or an earlier one. */
    fun tryApply(assetId: Int): Boolean {
        if (applied) return true
        if (assetId == 0) return false
        val picture = lookup(assetId) ?: return false
        apply(picture)
        applied = true
        return true
    }
}

/**
 * Whether the system is in dark mode, kept current.
 *
 * [read] is what touches the system. How [refresh] gets called is the platform layer's: a
 * native layer subscribes to appearance changes, a polling one calls it on a timer. When the
 * answer moved, [onChange] hears it and [requestFrame] asks for a frame so what changed is
 * drawn without waiting for the next input.
 */
class SystemDarkMonitor(
    private val read: () -> Boolean,
    private val requestFrame: () -> Unit = {},
    private val onChange: (Boolean) -> Unit = {},
) {
    /** True while the system is dark, as of the last [refresh]. */
    var dark: Boolean = read()
        private set

    /** Reads the system again, and when the answer moved, publishes it and asks for a frame. */
    fun refresh() {
        val now = read()
        if (now != dark) {
            dark = now
            onChange(now)
            requestFrame()
        }
    }
}

/**
 * The window's pixels per point, kept current the same way as [SystemDarkMonitor]: a change
 * of display or of the system's scale is published once and asks for a frame, because a frame
 * drawn at the old scale is the wrong size.
 */
class ScaleMonitor(
    private val read: () -> Float,
    private val requestFrame: () -> Unit = {},
    private val onChange: (Float) -> Unit = {},
) {
    var scale: Float = read()
        private set

    fun refresh() {
        val now = read()
        if (now != scale && now > 0f) {
            scale = now
            onChange(now)
            requestFrame()
        }
    }
}

/** True for the appearance names of the dark family: dark Aqua and its vibrant and contrast forms. */
fun isDarkAppearanceName(name: String?): Boolean = name?.contains("Dark") == true

/**
 * The text on a pasteboard, which is all a text field asks for.
 *
 * A seam so that what the clipboard does with it is one set of rules for every layer and can
 * be exercised without a window server.
 */
interface TextPasteboard {
    /** The text on it, or null where it holds nothing or holds something that is not text. */
    fun read(): String?

    /** Replaces what is on it with [text]. */
    fun write(text: String)
}

/** The text to paste, or null: an empty string is nothing, not a paste of nothing. */
fun TextPasteboard.pasteText(): String? = read()?.takeIf { it.isNotEmpty() }

/** Whether there is text to paste, which is what decides if Paste is offered. */
fun TextPasteboard.hasText(): Boolean = pasteText() != null

/** Stores [text], where null clears the clipboard as Compose asks it to. */
fun TextPasteboard.copyText(text: String?) = write(text.orEmpty())

/** A [TextPasteboard] over a [WindowPlatform]'s clipboard calls. */
fun WindowPlatform.pasteboard(): TextPasteboard = object : TextPasteboard {
    override fun read(): String? = readClipboardText()
    override fun write(text: String) = writeClipboardText(text)
}
