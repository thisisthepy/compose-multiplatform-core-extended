@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AppKit.NSScreen
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGPointMake
import kotlin.math.roundToInt

/*
 * Screen coordinates. The common API measures a screen from the top left of the primary screen
 * with y growing downward; AppKit measures from the bottom left of the primary screen with y
 * growing upward. Everything that converts between them is here, as functions of numbers, so the
 * arithmetic is tested without a window.
 */

/** A rectangle in the common API's space: top left origin, y down, in points. */
internal data class TopLeftRect(val x: Double, val y: Double, val width: Double, val height: Double)

/** The top left origin of an AppKit rectangle, given the height of the primary screen. */
internal fun appKitRectToTopLeft(
    originX: Double,
    originY: Double,
    width: Double,
    height: Double,
    primaryHeight: Double,
): TopLeftRect = TopLeftRect(originX, primaryHeight - originY - height, width, height)

/** A point in the common API's space, as AppKit's. Its own inverse. */
internal fun flipPoint(x: Double, y: Double, primaryHeight: Double): Pair<Double, Double> =
    x to (primaryHeight - y)

/**
 * Where the window's outer frame has its top left corner, in the common space, so that the point
 * ([anchorX], [anchorY]) of its content, in points from the content's top left, is under the
 * pointer at ([pointerX], [pointerY]). The content starts [titleBarHeight] below the frame's top.
 */
internal fun followFrameTopLeft(
    pointerX: Double,
    pointerY: Double,
    anchorX: Double,
    anchorY: Double,
    titleBarHeight: Double,
): Pair<Double, Double> = (pointerX - anchorX) to (pointerY - anchorY - titleBarHeight)

/** Whether the primary button is in the bitmask `NSEvent.pressedMouseButtons` reports. */
internal fun primaryButtonDown(pressedMouseButtons: ULong): Boolean = (pressedMouseButtons and 1uL) != 0uL

/** Rounds a screen coordinate to the whole points the common API reports. */
internal fun wholePoints(value: Double): Int = value.roundToInt()

/**
 * Who waits in [MacosWindowPlatform.pump] when several instances are pumped in one turn.
 *
 * The run loop is the process's, so one wait serves every window. The first instance to pump in
 * a turn waits; an instance that pumps after it, before the first pumps again, does not. A new
 * turn starts when the instance that waited pumps again. If it never does (it was closed, or the
 * caller moved on to another instance), its claim goes stale after the wait it asked for plus a
 * grace period, and the next caller takes over, so no caller spins without waiting.
 */
internal class PumpTurn(private val graceNanos: Long = 50_000_000L) {
    private var waiter: Any? = null
    private var claimedUntilNanos = 0L

    /** Whether [who], pumping at [nowNanos] with [timeoutMillis], is the one that waits. */
    fun claim(who: Any, nowNanos: Long, timeoutMillis: Long): Boolean {
        val current = waiter
        val stale = nowNanos > claimedUntilNanos
        if (current == null || current === who || stale) {
            waiter = who
            claimedUntilNanos = nowNanos + timeoutMillis * 1_000_000L + graceNanos
            return true
        }
        return false
    }

    /** [who] is gone: its claim ends. */
    fun release(who: Any) {
        if (waiter === who) waiter = null
    }
}

/** Where the next window opens when none says, so windows do not stack exactly. */
internal object MacosCascade {
    var next: CValue<CGPoint>? = null
    var alive = 0

    fun closed() {
        alive -= 1
        if (alive <= 0) {
            alive = 0
            next = null
        }
    }
}

/** The height of the primary screen, the one with the menu bar, which is the first of `NSScreen.screens`. */
internal fun primaryScreenHeight(): Double {
    val primary = (NSScreen.screens.firstOrNull() as? NSScreen) ?: NSScreen.mainScreen
    return primary?.frame?.useContents { size.height } ?: 0.0
}

/** A point of the common space as an AppKit point. */
internal fun topLeftToAppKit(x: Double, y: Double): CValue<CGPoint> =
    flipPoint(x, y, primaryScreenHeight()).let { CGPointMake(it.first, it.second) }
