package org.thisisthepy.compose.window.graalvm.linux

import org.thisisthepy.compose.window.atspi.AtspiActions
import org.thisisthepy.compose.window.atspi.AtspiBridge
import org.thisisthepy.compose.window.atspi.AtspiSemanticsSource
import org.thisisthepy.compose.window.atspi.BusAddress
import org.thisisthepy.compose.window.atspi.BusConnection

/**
 * The GraalVM X11 window on the AT-SPI accessibility bus, so that Orca and every other client
 * of libatspi can read it.
 *
 * The protocol, the object model and the bus bridge are the ones the Kotlin/Native X11 window
 * uses, from the shared module, and the semantics reader is the same file. What is this
 * window's own is the socket under the bus ([JvmBusConnection]) and the two facts a reader
 * asks of the window itself: where it is on the screen and whether it has the keyboard.
 *
 * The host gives the scene [source] as (one of) its semantics listeners, and calls
 * [afterFrame] once a turn, after drawing. Everything happens on the window's thread: the bus
 * is read without waiting once a turn, so a question is answered in the turn it was asked in.
 */
class X11Accessibility private constructor(
    private val window: X11Window,
    private val bridge: AtspiBridge,
    /** What the scene tells about its semantics, and what a reader's actions are performed on. */
    val source: AtspiSemanticsSource,
) {
    /** What this process is called on the accessibility bus. */
    val busName: String get() = bridge.busName

    /**
     * Publishes what changed in the tree, says whether the window has the keyboard, and answers
     * whatever a reader asked since the last turn. After the drawing, because what is read is
     * where everything was placed.
     */
    fun afterFrame(title: String) {
        source.capture(title)?.let { bridge.update(it) }
        bridge.windowActive(window.isFocused)
        bridge.pump()
    }

    fun close() = bridge.close()

    companion object {
        /**
         * Joins the accessibility bus for [window], or answers null where there is none: a
         * machine with no accessibility bus has no reader to serve, and the window carries on.
         * `NO_AT_BRIDGE=1` turns it off, as it does for every toolkit.
         */
        fun join(
            window: X11Window,
            source: AtspiSemanticsSource = AtspiSemanticsSource(),
            applicationName: String = JvmBusConnection.applicationName(),
        ): X11Accessibility? {
            if (System.getenv("NO_AT_BRIDGE") == "1") return null
            val user = JvmBusConnection.userId() ?: return null
            return join(
                window,
                source,
                applicationName,
                user,
                openSession = { JvmBusConnection.open() },
                openAccessibility = { address -> JvmBusConnection.open(address) },
            )
        }

        /** [join] with the connections given, which is how a test puts a fake bus under it. */
        internal fun join(
            window: X11Window,
            source: AtspiSemanticsSource,
            applicationName: String,
            userId: Long,
            openSession: () -> BusConnection?,
            openAccessibility: (BusAddress) -> BusConnection?,
        ): X11Accessibility? {
            val actions = object : AtspiActions {
                override fun click(id: Int) = source.click(id)
                override fun focus(id: Int) = source.focus(id)
                override fun windowOrigin(): Pair<Int, Int> = window.originOnScreen()
            }
            val bridge = AtspiBridge(openSession, openAccessibility, userId, applicationName, actions)
            return if (bridge.start()) X11Accessibility(window, bridge, source) else null
        }
    }
}
