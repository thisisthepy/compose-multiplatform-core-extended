package org.thisisthepy.compose.window

/**
 * What happened in the window, as the window recorded it.
 *
 * Plain numbers rather than a platform event. Every platform layer fills in the same
 * record, so nothing of AppKit, Win32 or Xlib reaches the scene and the scene cannot tell
 * which platform it ran on.
 */
data class WindowEvent(
    val kind: Int,
    val x: Float,
    val y: Float,
    val buttons: Int,
    val modifiers: Int,
    val keyCode: Int,
    val codePoint: Int,
    /** What an input method produced, and empty for everything that is not text. */
    val text: String,
    /**
     * When the operating system says the event happened, in milliseconds on the platform's
     * own monotonic event clock (`NSEvent.timestamp`, Win32 `GetMessageTime`, the X11 event
     * time), or [TIME_UNKNOWN] where the platform layer did not say. Only the difference
     * between two of these means anything; [EventClock] puts them on the scene's clock.
     */
    val timeMillis: Long = TIME_UNKNOWN,
) {

    /**
     * Whether this is the press of the secondary button. Read through [SECONDARY_BUTTON],
     * which every platform layer sets, and not through the held bits, which a control-click
     * on macOS does not carry.
     */
    val isSecondaryPress: Boolean
        get() = kind == POINTER_DOWN && buttons and SECONDARY_BUTTON != 0

    /**
     * The held bit ([PRIMARY_HELD] and the others) of the button this press or release is
     * about. The event's own button flag names it, and an event with none is the primary
     * button's, which is the one a platform has no reason to flag.
     */
    val changedHeldBit: Int
        get() = when {
            buttons and SECONDARY_BUTTON != 0 -> SECONDARY_HELD
            buttons and MIDDLE_BUTTON != 0 -> MIDDLE_HELD
            buttons and BACK_BUTTON != 0 -> BACK_HELD
            buttons and FORWARD_BUTTON != 0 -> FORWARD_HELD
            else -> PRIMARY_HELD
        }

    companion object {
        /** The [timeMillis] of an event the platform layer did not time; the scene times it on arrival. */
        const val TIME_UNKNOWN = 0L

        /**
         * A 32 bit event time (Win32 `GetMessageTime`, X11 `Time`) as a [timeMillis]: read
         * unsigned, so it counts up to 49 days before it wraps rather than turning negative
         * after 24.
         */
        fun timeFrom32Bits(time: Int): Long = time.toLong() and 0xffffffffL

        /** `NSEvent.timestamp`, seconds since the system started, as a [timeMillis]. */
        fun timeFromSeconds(seconds: Double): Long = (seconds * 1000).toLong()

        const val POINTER_MOVE = 1
        const val POINTER_DOWN = 2
        const val POINTER_UP = 3
        /**
         * A turn of the wheel or a swipe on a trackpad. [x] and [y] carry its travel, not a
         * place: the scene scrolls what is under the pointer's last place.
         *
         * The travel is in lines, the convention of AWT's wheel rotation and of a Compose
         * scroll delta: a positive [y] asks for what is below (the content moves up) and a
         * positive [x] for what is to the right. Every window layer sends it this way, and the
         * scroll config of each platform in the fork's foundation answers a positive [y] by
         * moving a verticalScroll forward:
         *
         * | Window | What the window layer sends | Foundation config, line distance |
         * |---|---|---|
         * | macOS, Kotlin/Native and GraalVM | AppKit's `scrollingDelta` negated (it already follows natural scrolling): lines for a wheel, points / 10 for a trackpad | `MacOsScrollConfig` / desktop `MacOSCocoaConfig`: 10 dp |
         * | Windows, Kotlin/Native and GraalVM (one C window) | `WM_MOUSEWHEEL` delta / 120 × lines a notch (`SPI_GETWHEELSCROLLLINES`), negated, since Win32 counts a turn away from the reader as positive; `WM_MOUSEHWHEEL` the same, not negated, since right is positive on both sides; fractions of a notch kept | `WindowsScrollConfig` / desktop `WindowsWinUIConfig`: a twentieth of the scrolled side |
         * | X11, Kotlin/Native and GraalVM | three lines a notch: button 4 -y, 5 +y, 6 -x, 7 +x (no XInput2 smooth scrolling) | `LinuxScrollConfig` / desktop `LinuxGnomeConfig`: the square root of the scrolled side in pixels |
         *
         * The lines a notch moves are counted once, by the window; a config does not multiply
         * by them again.
         */
        const val SCROLL = 4
        const val KEY_DOWN = 5
        const val KEY_UP = 6
        const val TEXT_COMMIT = 7
        const val TEXT_COMPOSE = 8
        const val RESIZE = 9
        const val FILES_ENTERED = 10
        const val FILES_DROPPED = 11
        const val FILES_EXITED = 12

        // An input method's preedit callbacks as the X11 window heard them: a composition
        // began, a run of characters was replaced (first in `keyCode`, how many in
        // `codePoint`, the caret in `x`, the new characters in `text`), and the composition
        // ended.
        const val PREEDIT_START = 13
        const val PREEDIT_DRAW = 14
        const val PREEDIT_DONE = 15

        /**
         * An editing action AppKit named by its selector, `selectAll:` or `copy:`, with the
         * selector in [text]. The Edit menu sends these, and so does a key binding the
         * scene has not already been shown as a key.
         */
        const val EDIT_COMMAND = 16

        /**
         * Set in [buttons] on a press or release of the secondary button. The pressed
         * buttons alone cannot say which one was let go.
         */
        const val SECONDARY_BUTTON = 1 shl 16

        /** Like [SECONDARY_BUTTON], for the middle button. */
        const val MIDDLE_BUTTON = 1 shl 17

        /** Like [SECONDARY_BUTTON], for the back button. */
        const val BACK_BUTTON = 1 shl 18

        /** Like [SECONDARY_BUTTON], for the forward button. */
        const val FORWARD_BUTTON = 1 shl 19

        /**
         * The buttons held down, in the low bits of [buttons]: primary, secondary, middle,
         * back and forward.
         */
        const val PRIMARY_HELD = 1
        const val SECONDARY_HELD = 2
        const val MIDDLE_HELD = 4
        const val BACK_HELD = 8
        const val FORWARD_HELD = 16

        /** Every held bit. */
        const val ALL_HELD = PRIMARY_HELD or SECONDARY_HELD or MIDDLE_HELD or BACK_HELD or FORWARD_HELD

        /**
         * The [buttons] of a pointer event: the buttons [held] and, when the event is the
         * press or release of the secondary button, [SECONDARY_BUTTON]. Every platform layer
         * builds the field through this, so the scene reads one encoding.
         */
        fun pointerButtons(held: Int, secondaryEvent: Boolean): Int =
            if (secondaryEvent) held or SECONDARY_BUTTON else held

        /**
         * The [buttons] of a pointer event whose own button is [eventButton]: one of
         * [SECONDARY_BUTTON], [MIDDLE_BUTTON], [BACK_BUTTON], [FORWARD_BUTTON], or 0 for the
         * primary button.
         */
        fun pointerButtons(held: Int, eventButton: Int): Int = held or eventButton

        // An entry of the text edit menu the window put up itself on a right click was
        // chosen; `keyCode` is its [EditMenuId]. Platform layers hand it to
        // [WindowListener.onContextMenuChosen] and do not pass it on as an event.
        const val MENU_COMMAND = 17

        /** The operating system's text size changed; the new multiplier is in `x`. */
        const val TEXT_SCALE_CHANGED = 18

        /** The event a platform layer queues when the text size becomes [scale]. */
        @Suppress("FunctionName")
        fun TextScaleChanged(scale: Float): WindowEvent = WindowEvent(
            kind = TEXT_SCALE_CHANGED, x = scale, y = 0f, buttons = 0, modifiers = 0,
            keyCode = 0, codePoint = 0, text = "",
        )

        /**
         * A native view slot took keyboard focus (a click in it, or [NativeViews.focus]), or
         * the window holding it became the key window again; the slot id is in `keyCode`. The
         * scene lets go of its own focus, so no field keeps a caret.
         */
        const val NATIVE_VIEW_FOCUS_IN = 19

        /**
         * Keyboard focus left a native view slot: to the scene, to another slot, or to another
         * window or application. The slot id is in `keyCode`.
         */
        const val NATIVE_VIEW_FOCUS_OUT = 20

        /**
         * The pointer came back to the scene at `x`, `y` from a place the scene does not get
         * it, such as a native view slot.
         */
        const val POINTER_ENTER = 21

        /**
         * The pointer left the scene at `x`, `y` for a place the scene does not get it, such as
         * a native view slot, so nothing in the scene stays hovered.
         */
        const val POINTER_EXIT = 22

        /**
         * A reader asked a control to do something, on a window whose platform writes such
         * requests to its event queue (Windows' UI Automation): `keyCode` is the
         * [AccessibleElement.id], `codePoint` the [AccessibleAction], and `text` the new value
         * of an [AccessibleAction.SET_VALUE] (empty for every other action). The renderer
         * hands it to the control, not to the scene as input.
         */
        const val ACCESSIBILITY_ACTION = 23

        @Suppress("FunctionName")
        fun NativeViewFocusIn(id: Int): WindowEvent = WindowEvent(
            kind = NATIVE_VIEW_FOCUS_IN, x = 0f, y = 0f, buttons = 0, modifiers = 0,
            keyCode = id, codePoint = 0, text = "",
        )

        @Suppress("FunctionName")
        fun NativeViewFocusOut(id: Int): WindowEvent = WindowEvent(
            kind = NATIVE_VIEW_FOCUS_OUT, x = 0f, y = 0f, buttons = 0, modifiers = 0,
            keyCode = id, codePoint = 0, text = "",
        )
    }

    /** The slot a [NATIVE_VIEW_FOCUS_IN] or [NATIVE_VIEW_FOCUS_OUT] event is about. */
    val nativeViewId: Int get() = keyCode

    /** True for [TEXT_SCALE_CHANGED]. */
    val isTextScaleChanged: Boolean get() = kind == TEXT_SCALE_CHANGED

    /** The multiplier a [TEXT_SCALE_CHANGED] event carries. */
    val textScale: Float get() = x
}
