@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlin.concurrent.AtomicInt
import org.thisisthepy.compose.window.MacosAxApiAnswer
import platform.ApplicationServices.AXIsProcessTrusted
import platform.ApplicationServices.AXUIElementCopyAttributeValue
import platform.ApplicationServices.AXUIElementCreateApplication
import platform.ApplicationServices.AXUIElementSetMessagingTimeout
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import platform.darwin.QOS_CLASS_USER_INITIATED
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.posix.getpid
import org.thisisthepy.compose.window.MacosAxNode
import org.thisisthepy.compose.window.MacosAxSnapshot
import platform.AppKit.NSAccessibilityProtocol
import platform.AppKit.NSApplication
import platform.AppKit.NSWindow
import platform.Foundation.NSAttributedString
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo

/**
 * Every accessibility element of this application's visible windows, walked in process through
 * the same objects AppKit's accessibility server answers VoiceOver and every other client from.
 *
 * For a check on a machine where no outside client is trusted for accessibility, which a CI
 * runner is: a client outside the process is refused there, and the application can still ask
 * itself. The GraalVM window writes the same walk (`dxc_native_accessibility_snapshot`), so one
 * check reads both.
 */
fun accessibilitySnapshot(): MacosAxSnapshot {
    val nodes = ArrayList<MacosAxNode>()
    var budget = 4000
    fun text(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is NSNumber -> value.stringValue
        is NSAttributedString -> value.string
        else -> ""
    }
    fun walk(element: Any?, depth: Int) {
        if (element !is NSAccessibilityProtocol || budget <= 0 || depth > 24) return
        budget--
        val frame = element.accessibilityFrame().useContents {
            doubleArrayOf(origin.x, origin.y, size.width, size.height)
        }
        nodes += MacosAxNode(
            depth = depth,
            role = element.accessibilityRole() ?: "",
            subrole = element.accessibilitySubrole() ?: "",
            title = element.accessibilityTitle() ?: "",
            label = element.accessibilityLabel() ?: "",
            value = text(element.accessibilityValue()),
            x = frame[0],
            y = frame[1],
            width = frame[2],
            height = frame[3],
        )
        for (child in element.accessibilityChildren().orEmpty()) walk(child, depth + 1)
    }
    for (window in NSApplication.sharedApplication.windows) {
        if ((window as? NSWindow)?.visible == true) walk(window, 0)
    }
    val major = NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion }.toInt()
    return MacosAxSnapshot(major, nodes)
}

/**
 * Asks the system's accessibility API for this application's windows, as VoiceOver or any
 * other client outside the process does, and says what it answered. On a machine that has not
 * trusted the process for accessibility (a CI runner) that is an error, which a check reports
 * with its number rather than treating as a failure of the window.
 *
 * Asked from a worker thread while this one keeps the run loop turning, because the answer
 * comes from this thread.
 */
fun axApiProbe(): MacosAxApiAnswer {
    val trusted = if (AXIsProcessTrusted()) 1 else 0
    val error = AtomicInt(-2)
    val count = AtomicInt(-1)
    val done = AtomicInt(0)
    val pid = getpid()
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED.toLong(), 0u)) {
        val app = AXUIElementCreateApplication(pid)
        if (app != null) {
            AXUIElementSetMessagingTimeout(app, 3.0f)
            val name = CFStringCreateWithCString(null, "AXWindows", kCFStringEncodingUTF8)
            memScoped {
                val out = alloc<CFTypeRefVar>()
                error.value = AXUIElementCopyAttributeValue(app, name, out.ptr)
                val list = out.value
                if (error.value == 0 && list != null) {
                    count.value = CFArrayGetCount(list.reinterpret()).toInt()
                }
                if (list != null) CFRelease(list)
            }
            if (name != null) CFRelease(name)
            CFRelease(app)
        }
        done.value = 1
    }
    var turns = 0
    while (done.value == 0 && turns < 120) {
        NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.05))
        turns++
    }
    return MacosAxApiAnswer(if (done.value == 1) error.value else -2, trusted, count.value)
}
