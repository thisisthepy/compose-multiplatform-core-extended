@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.thisisthepy.compose.window.isIconBuffer
import platform.AppKit.NSApplication
import platform.AppKit.NSBitmapImageRep
import platform.AppKit.NSDeviceRGBColorSpace
import platform.AppKit.NSImage
import platform.Foundation.NSMakeSize
import platform.posix.memcpy

/**
 * Puts a picture on the application, which is what the Dock and the application switcher show.
 *
 * The same call, with the same pixel form, as the GraalVM window's `setApplicationIcon`:
 * [rgba] is eight bits each of red, green, blue and alpha, the colour already multiplied by
 * the alpha, row after row with no padding. Call it on the main thread. Returns false, and
 * changes nothing, where [rgba] is not [width] by [height] pixels of that form.
 */
fun setApplicationIcon(rgba: ByteArray, width: Int, height: Int): Boolean {
    if (!isIconBuffer(rgba.size, width, height)) return false
    val representation = NSBitmapImageRep(
        bitmapDataPlanes = null,
        pixelsWide = width.toLong(),
        pixelsHigh = height.toLong(),
        bitsPerSample = 8,
        samplesPerPixel = 4,
        hasAlpha = true,
        isPlanar = false,
        colorSpaceName = NSDeviceRGBColorSpace,
        bytesPerRow = width.toLong() * 4,
        bitsPerPixel = 32,
    )
    val target = representation.bitmapData ?: return false
    rgba.usePinned { memcpy(target, it.addressOf(0), rgba.size.toULong()) }
    val image = NSImage(size = NSMakeSize(width.toDouble(), height.toDouble()))
    image.addRepresentation(representation)
    NSApplication.sharedApplication().applicationIconImage = image
    return true
}
