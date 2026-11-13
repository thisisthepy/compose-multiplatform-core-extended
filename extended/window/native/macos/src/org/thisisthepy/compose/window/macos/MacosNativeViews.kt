@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package org.thisisthepy.compose.window.macos

import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CValue
import kotlinx.cinterop.interpretObjCPointerOrNull
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.useContents
import org.thisisthepy.compose.window.NativeViewFocus
import org.thisisthepy.compose.window.NativeViewRegion
import org.thisisthepy.compose.window.NativeViews
import org.thisisthepy.compose.window.PixelRect
import org.thisisthepy.compose.window.PixelRoundRect
import org.thisisthepy.compose.window.WindowEvent
import platform.AppKit.NSEvent
import platform.AppKit.NSView
import platform.AppKit.NSViewMinYMargin
import platform.Foundation.CFBridgingRelease
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGBlendMode
import platform.CoreGraphics.CGContextAddPath
import platform.CoreGraphics.CGContextFillPath
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetBlendMode
import platform.CoreGraphics.CGContextSetGrayFillColor
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGMutablePathRef
import platform.CoreGraphics.CGPathAddRect
import platform.CoreGraphics.CGPathAddRoundedRect
import platform.CoreGraphics.CGPathCreateMutable
import platform.CoreGraphics.CGPathRelease
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSMakeRect
import platform.QuartzCore.CALayer
import platform.QuartzCore.CAShapeLayer
import platform.QuartzCore.CATransaction
import platform.QuartzCore.kCAFillRuleEvenOdd

/**
 * The view a slot's native view sits in, which the window owns: it is what is placed, cut to
 * the slot's visible region and hit tested, so the caller's view needs nothing of its own.
 *
 * Its frame is the part of the slot inside the clip and its layer clips to it; the caller's view
 * inside keeps the slot's whole frame. Holes are cut by the layer's mask, and [shows] answers
 * for the pointer the same way, so a click in a hole falls through to the scene below.
 */
internal class NativeViewContainer(
    private val shows: (pointInSuperview: CValue<CGPoint>) -> Boolean,
) : NSView(NSMakeRect(0.0, 0.0, 0.0, 0.0)) {
    override fun hitTest(aPoint: CValue<CGPoint>): NSView? {
        if (hidden || !shows(aPoint)) return null
        // A view of the caller's that answers nothing (a plain one with no subviews does) still
        // keeps the click from the scene under it.
        return super.hitTest(aPoint) ?: this
    }

    override fun acceptsFirstMouse(event: NSEvent?) = true

    // What the caller's view leaves unhandled climbs the responder chain, and AppKit hands a
    // mouse event no one took to the window's first responder, which may be the scene. Inside
    // a slot it is the slot's, so it stops here.
    override fun mouseDown(event: NSEvent) {}
    override fun mouseUp(event: NSEvent) {}
    override fun mouseDragged(event: NSEvent) {}
    override fun rightMouseDown(event: NSEvent) {}
    override fun rightMouseUp(event: NSEvent) {}
    override fun rightMouseDragged(event: NSEvent) {}
    override fun otherMouseDown(event: NSEvent) {}
    override fun otherMouseUp(event: NSEvent) {}
    override fun otherMouseDragged(event: NSEvent) {}
    override fun scrollWheel(event: NSEvent) {}
    override fun mouseMoved(event: NSEvent) {}
}

/** One slot: the caller's view, the container it was put in and where it shows, in pixels. */
internal class NativeViewSlot(val id: Int, val view: NSView, val container: NativeViewContainer) {
    var region = NativeViewRegion(PixelRect(0, 0, 0, 0))
    var visible = false

    /** The backing scale the frame and region were converted with. */
    var scale = 1.0
}

/**
 * The [NativeViews] of a [MacosWindow].
 *
 * Each slot's container is a subview of the window's content view [backdrop], above the Metal
 * view [scene] and above every slot attached before it. The window reports its first responder
 * and key changes here, and its scene view asks [covers] before it takes a pointer move, a
 * cursor update or a drag, so inside a slot's visible region they are the native view's.
 */
class MacosNativeViews internal constructor(
    private val owner: MacosWindow,
    private val backdrop: NSView,
    private val scene: NSView,
) : NativeViews {
    private val slots = LinkedHashMap<Int, NativeViewSlot>()
    private val focus = NativeViewFocus(key = owner.window.keyWindow)

    /** The slots in the order they were attached, bottom first. */
    internal val all: Collection<NativeViewSlot> get() = slots.values

    /** Slot [id], for a test. */
    internal fun slot(id: Int): NativeViewSlot? = slots[id]

    /** The slot that has keyboard focus, or null for the scene. */
    val focused: Int? get() = focus.focused

    override val parentHandle: Long
        get() = if (owner.isClosed) 0L else backdrop.objcPtr().toLong()

    override fun attach(id: Int, handle: Long): Boolean {
        if (handle == 0L || id in slots) return false
        val view = interpretObjCPointerOrNull<platform.darwin.NSObject>(handle.toCPointer<CPointed>()!!.rawValue) as? NSView
            ?: return false
        if (view === backdrop || view === scene || slots.values.any { it.view === view }) return false
        lateinit var slot: NativeViewSlot
        val container = NativeViewContainer { point -> shows(slot, point) }
        slot = NativeViewSlot(id, view, container)
        container.wantsLayer = true
        container.layer?.masksToBounds = true
        container.hidden = true
        // The backdrop is not flipped: a slot keeps its distance from the top when the window
        // changes height, as the content laid out from the top left does.
        container.autoresizingMask = NSViewMinYMargin
        view.autoresizingMask = 0u
        // Moves the view out of the content view, where a caller making it against
        // [parentHandle] may have put it.
        container.addSubview(view)
        backdrop.addSubview(container)
        slots[id] = slot
        return true
    }

    override fun setFrame(id: Int, x: Int, y: Int, width: Int, height: Int) {
        val slot = slots[id] ?: return
        slot.region = slot.region.copy(frame = PixelRect(x, y, maxOf(0, width), maxOf(0, height)))
        place(slot)
    }

    override fun setVisibleRegion(id: Int, clip: PixelRect?, holes: List<PixelRoundRect>) {
        val slot = slots[id] ?: return
        slot.region = slot.region.copy(clip = clip, holes = holes.filter { !it.rect.isEmpty })
        place(slot)
    }

    override fun setVisible(id: Int, visible: Boolean) {
        val slot = slots[id] ?: return
        if (slot.visible == visible) return
        if (!visible && focus.focused == id) focusScene()
        slot.visible = visible
        slot.container.hidden = !visible
    }

    override fun focus(id: Int) {
        val slot = slots[id] ?: return
        if (!slot.visible) return
        owner.window.makeFirstResponder(slot.view)
    }

    override fun focusScene() {
        owner.window.makeFirstResponder(scene)
    }

    override fun detach(id: Int) {
        val slot = slots[id] ?: return
        if (focus.focused == id) focusScene()
        slots.remove(id)
        slot.view.removeFromSuperview()
        slot.container.removeFromSuperview()
        // The first responder may have been inside the view without the window making it so
        // through makeFirstResponder; whatever it is now is reported.
        firstResponderChanged()
    }

    /**
     * Whether a point in the window ([locationInWindow], AppKit's window coordinates) is in the
     * visible region of a shown slot.
     */
    internal fun covers(locationInWindow: CValue<CGPoint>): Boolean {
        if (slots.isEmpty()) return false
        val point = backdrop.convertPoint(locationInWindow, fromView = null)
        return slots.values.any { it.visible && shows(it, point) }
    }

    /** The visible slots' views, for a reader walking the window. */
    internal fun accessibleViews(): List<NSView> = slots.values.filter { it.visible }.map { it.view }

    /** The window's first responder may have moved; reports it if it went in or out of a slot. */
    internal fun firstResponderChanged() {
        val responder = owner.window.firstResponder
        var at = responder as? NSView
        var id: Int? = null
        while (at != null && id == null) {
            id = slots.values.firstOrNull { it.container === at }?.id
            at = at.superview
        }
        emit(focus.responderChanged(id))
    }

    /** The window became the key window or stopped being it. */
    internal fun keyChanged(key: Boolean) {
        if (key) {
            // AppKit keeps the first responder while the window is not key; put it back in the
            // slot if something took it away meanwhile.
            val slot = focus.focused?.let { slots[it] }
            if (slot != null && !isInside(owner.window.firstResponder, slot.container)) {
                owner.window.makeFirstResponder(slot.view)
            }
        }
        emit(focus.keyChanged(key))
    }

    private fun isInside(responder: Any?, container: NSView): Boolean {
        var at = responder as? NSView
        while (at != null) {
            if (at === container) return true
            at = at.superview
        }
        return false
    }

    private fun emit(events: List<WindowEvent>) {
        for (event in events) owner.onEvent?.invoke(event)
    }

    /** Whether [point], in the backdrop's coordinates, shows [slot]'s view. */
    private fun shows(slot: NativeViewSlot, point: CValue<CGPoint>): Boolean {
        val height = backdrop.bounds.useContents { size.height }
        return point.useContents { slot.region.contains(x * slot.scale, (height - y) * slot.scale) }
    }

    /** Puts [slot]'s container and view where its region says, and cuts it to the region. */
    private fun place(slot: NativeViewSlot) {
        val scale = owner.window.backingScaleFactor.takeIf { it > 0.0 } ?: 1.0
        slot.scale = scale
        val region = slot.region
        val frame = region.frame
        val bounds = region.bounds
        val height = backdrop.bounds.useContents { size.height }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        try {
            slot.container.setFrame(
                NSMakeRect(bounds.x / scale, height - bounds.bottom / scale, bounds.width / scale, bounds.height / scale),
            )
            // The view keeps the slot's whole frame; the container cuts it to the clip. Both are
            // unflipped, so the view's origin is its bottom left corner.
            slot.view.setFrame(
                NSMakeRect(
                    (frame.x - bounds.x) / scale,
                    (bounds.bottom - frame.bottom) / scale,
                    frame.width / scale,
                    frame.height / scale,
                ),
            )
            slot.container.layer?.masksToBounds = true
            slot.container.layer?.mask = mask(region, scale)
        } finally {
            CATransaction.commit()
        }
    }

    /**
     * The mask of a container cut to [region]: none when the whole frame shows, one path filled
     * even-odd when the holes do not touch, and an image with each hole cleared when they do.
     */
    private fun mask(region: NativeViewRegion, scale: Double): CALayer? {
        if (region.isWholeFrame) return null
        val bounds = region.bounds
        if (bounds.isEmpty) return CALayer()
        val layerFrame = CGRectMake(0.0, 0.0, bounds.width / scale, bounds.height / scale)
        val inside = region.holes.filter { it.rect.intersects(bounds) }
        if (region.holesAreDisjoint) {
            val path = CGPathCreateMutable() ?: return null
            CGPathAddRect(path, null, layerFrame)
            for (hole in inside) addHole(path, hole, bounds, scale)
            val shape = CAShapeLayer()
            shape.frame = layerFrame
            shape.path = path
            shape.fillRule = kCAFillRuleEvenOdd
            CGPathRelease(path)
            return shape
        }
        // One byte of coverage per backing pixel, cleared under every hole: an overlap of two
        // holes is cleared twice and stays a hole.
        val width = bounds.width.toULong()
        val context = CGBitmapContextCreate(
            null, width, bounds.height.toULong(), 8u, width, null, CGImageAlphaInfo.kCGImageAlphaOnly.value,
        ) ?: return null
        CGContextSetGrayFillColor(context, 0.0, 1.0)
        CGContextFillRect(context, CGRectMake(0.0, 0.0, bounds.width.toDouble(), bounds.height.toDouble()))
        CGContextSetBlendMode(context, CGBlendMode.kCGBlendModeClear)
        for (hole in inside) {
            val path = CGPathCreateMutable() ?: continue
            addHole(path, hole, bounds, 1.0)
            CGContextAddPath(context, path)
            CGContextFillPath(context)
            CGPathRelease(path)
        }
        val image = CGBitmapContextCreateImage(context)
        CGContextRelease(context)
        val layer = CALayer()
        layer.frame = layerFrame
        layer.contents = CFBridgingRelease(image)
        return layer
    }

    /**
     * Adds [hole] to [path] in the coordinates of a layer covering [bounds] at [scale] (1 for
     * backing pixels), whose origin is its bottom left corner.
     */
    private fun addHole(path: CGMutablePathRef, hole: PixelRoundRect, bounds: PixelRect, scale: Double) {
        val rect = hole.rect
        val width = rect.width / scale
        val height = rect.height / scale
        val box = CGRectMake((rect.x - bounds.x) / scale, (bounds.bottom - rect.bottom) / scale, width, height)
        // CoreGraphics refuses a radius over half a side; rounding in the division must not reach it.
        val radius = minOf(hole.radius / scale, minOf(width, height) / 2 - 1e-6).coerceAtLeast(0.0)
        if (radius > 0.0) CGPathAddRoundedRect(path, null, box, radius, radius) else CGPathAddRect(path, null, box)
    }
}
