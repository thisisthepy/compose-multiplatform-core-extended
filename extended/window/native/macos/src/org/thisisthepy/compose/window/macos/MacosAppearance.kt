@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import platform.AppKit.NSApplication
import org.thisisthepy.compose.window.isDarkAppearanceName
import platform.AppKit.NSAppearanceNameAqua
import platform.AppKit.NSAppearanceNameDarkAqua
import platform.AppKit.effectiveAppearance
import platform.Foundation.NSDistributedNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** The application's effective appearance, read now. */
fun systemIsDark(): Boolean {
    val appearance = NSApplication.sharedApplication().effectiveAppearance
    val best = appearance.bestMatchFromAppearancesWithNames(
        listOf(NSAppearanceNameAqua, NSAppearanceNameDarkAqua),
    ) as? String
    return isDarkAppearanceName(best)
}

/**
 * Watches the system's appearance through the distributed notification the system posts for
 * the switch in System Settings and the automatic day and night change.
 *
 * Kotlin/Native cannot override `observeValueForKeyPath`, which is declared in a category,
 * so key-value observing of `effectiveAppearance` is not available. The answer is read on
 * the next turn of the main queue, after the application's appearance has caught up.
 */
fun observeSystemAppearance(onChange: () -> Unit) {
    val observer = NSDistributedNotificationCenter.defaultCenter().addObserverForName(
        name = "AppleInterfaceThemeChangedNotification",
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _ -> dispatch_async(dispatch_get_main_queue()) { onChange() } }
    // Held for the life of the process.
    retainedObservers += observer
}

private val retainedObservers = mutableListOf<Any>()
