@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
)

package org.thisisthepy.compose.window.macos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.readValue
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.addressOf
import org.jetbrains.skia.Canvas
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.MacosAx
import org.thisisthepy.compose.window.ResizeStats
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowMeasurement
import org.thisisthepy.compose.window.macosCornerRadius
import org.thisisthepy.compose.window.scene.ComposeWindowHost
import platform.AppKit.NSApplication
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSColor
import platform.AppKit.NSDragOperation
import platform.AppKit.NSDragOperationCopy
import platform.AppKit.NSDragOperationNone
import platform.AppKit.NSDraggingDestinationProtocol
import platform.AppKit.NSDraggingInfoProtocol
import platform.AppKit.NSEvent
import platform.AppKit.NSEventModifierFlagControl
import platform.AppKit.NSFilenamesPboardType
import platform.AppKit.NSMenu
import platform.AppKit.NSTextInputClientProtocol
import platform.AppKit.NSToolbar
import platform.AppKit.NSTrackingActiveAlways
import platform.AppKit.NSTrackingActiveInKeyWindow
import platform.AppKit.NSTrackingArea
import platform.AppKit.NSTrackingAssumeInside
import platform.AppKit.NSTrackingInVisibleRect
import platform.AppKit.NSTrackingMouseEnteredAndExited
import platform.AppKit.NSTrackingMouseMoved
import platform.AppKit.NSView
import platform.AppKit.NSViewHeightSizable
import platform.AppKit.NSViewLayerContentsRedrawDuringViewResize
import platform.AppKit.NSViewWidthSizable
import platform.AppKit.NSVisualEffectBlendingMode
import platform.AppKit.NSVisualEffectMaterialUnderWindowBackground
import platform.AppKit.NSVisualEffectState
import platform.AppKit.NSVisualEffectView
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowCloseButton
import platform.AppKit.NSWindowStyleMaskClosable
import platform.AppKit.NSWindowStyleMaskFullSizeContentView
import platform.AppKit.NSWindowStyleMaskMiniaturizable
import platform.AppKit.NSWindowStyleMaskResizable
import platform.AppKit.NSWindowStyleMaskTitled
import platform.AppKit.NSWindowTitleHidden
import platform.AppKit.NSWindowTitleVisible
import platform.AppKit.NSWindowToolbarStyle
import platform.AppKit.NSWindowZoomButton
import platform.AppKit.NSWorkspace
import platform.AppKit.postEvent
import platform.AppKit.voiceOverEnabled
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSize
import platform.Foundation.NSAttributedString
import platform.Foundation.NSDate
import platform.Foundation.NSMakeRange
import platform.Foundation.NSMakeRect
import platform.Foundation.NSMakeSize
import platform.Foundation.NSNotFound
import platform.Foundation.NSPointInRect
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSRange
import platform.Foundation.NSRangePointer
import platform.Foundation.NSRunLoop
import platform.Foundation.NSStringFromSelector
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.Foundation.string
import platform.QuartzCore.CALayer
import platform.QuartzCore.CALayerDelegateProtocol
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

/**
 * A window of this renderer's own, rather than the one Compose opens for this platform.
 *
 * It hosts a Compose scene through `ComposeWindowHost`, providing the native AppKit window,
 * event pump, Metal surface and screen reader accessibility tree.
 */
class MacosWindow(
    val name: String,
    val width: Int,
    val height: Int,
    /** The smallest content area the window may be resized to, or null for none. */
    val minimumSize: Pair<Double, Double>? = null,
    /**
     * How the title bar is built: the same answer the native image's window is given, so
     * the two have the same corners and their content starts at the same height.
     */
    val chrome: MacosWindowChrome = MacosWindowChrome.Modern,
) {
    private var measured = IntSize(width, height)
    private var isTheKeyWindow by mutableStateOf(true)

    internal val metal = MetalSurface()
    val metalSurface: MetalSurface get() = metal

    internal var frameTime: () -> Long = ::uptimeNanos

    var onEvent: ((WindowEvent) -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var resizePainter: (() -> Boolean)? = null
    var host: ComposeWindowHost? = null

    private var inTurn = false
    private var loopTimer: platform.Foundation.NSTimer? = null

    fun pumpHost(timeoutMillis: Long = 0L) {
        val currentHost = host ?: return
        if (inTurn) return
        inTurn = true
        try {
            currentHost.turn(timeoutMillis)
        } finally {
            inTurn = false
        }
    }

    fun startLoopTimer() {
        if (loopTimer != null) return
        loopTimer = platform.Foundation.NSTimer.scheduledTimerWithTimeInterval(
            interval = 0.016,
            repeats = true,
        ) { _: platform.Foundation.NSTimer? ->
            pumpHost(0)
        }
    }

    fun stopLoopTimer() {
        loopTimer?.invalidate()
        loopTimer = null
    }

    private var describesItself = true

    fun describeFrom(elements: List<AccessibleElement>) {
        describesItself = false
        reader.update(elements)
    }

    var onAccessibilityAction: (id: Int, action: Int, argument: String?) -> Boolean = { id, action, argument ->
        host?.onAccessibilityAction(id, action, argument) ?: false
    }

    internal val reader: AppKitAccessibilityTree by lazy {
        AppKitAccessibilityTree(backdrop) { id, action, argument -> onAccessibilityAction(id, action, argument) }
    }

    val accessibilityActive: Boolean get() = readerIsListening || NSWorkspace.sharedWorkspace.voiceOverEnabled

    var onElements: ((List<AccessibleElement>) -> Unit)? = null

    fun listenForReader() {
        readerIsListening = true
        view.needsDisplay = true
    }

    val resizeStats = ResizeStats()

    var onFrameRendered: ((nanos: Long) -> Unit)? = null

    internal fun paintFrame(canvas: Canvas, widthInPixels: Int, heightInPixels: Int) {
        val size = IntSize(widthInPixels, heightInPixels)
        measured = size
        val begun = kotlin.time.TimeSource.Monotonic.markNow()
        host?.renderTo(canvas, widthInPixels, heightInPixels)
        onFrameRendered?.invoke(begun.elapsedNow().inWholeNanoseconds)
        val scale = window.backingScaleFactor
        view.frame.useContents {
            resizeStats.presentAgainst(
                widthInPixels, heightInPixels, (this.size.width * scale).toInt(), (this.size.height * scale).toInt(),
            )
        }
    }

    fun requestFrame() {
        view.needsDisplay = true
        host?.requestFrame()
    }

    var onFrameDrawn: ((widthInPixels: Int, heightInPixels: Int) -> Unit)? = null

    fun measure(): WindowMeasurement {
        val scale = window.backingScaleFactor
        return view.bounds.useContents {
            WindowMeasurement((size.width * scale).toInt(), (size.height * scale).toInt(), scale.toFloat())
        }
    }

    private var readerIsListening = false

    val caption = mutableStateOf(WindowCaption.None)

    private fun measureCaption() {
        val close = window.standardWindowButton(NSWindowCloseButton)
        val zoom = window.standardWindowButton(NSWindowZoomButton)
        caption.value = macosWindowCaption(
            chrome = chrome,
            windowHeight = window.frame.useContents { size.height },
            contentLayoutHeight = window.contentLayoutRect.useContents { size.height },
            closeMinX = close?.frame?.useContents { origin.x },
            zoomMaxX = zoom?.frame?.useContents { origin.x + size.width },
            cornerRadius = systemCornerRadius(chrome),
        ) ?: return
    }

    val window = object : NSWindow(
        contentRect = NSMakeRect(0.0, 0.0, width.toDouble(), height.toDouble()),
        styleMask = NSWindowStyleMaskTitled or NSWindowStyleMaskMiniaturizable or
            NSWindowStyleMaskClosable or NSWindowStyleMaskResizable or
            (if (chrome.fullSizeContentView) NSWindowStyleMaskFullSizeContentView else 0uL),
        backing = NSBackingStoreBuffered,
        defer = true,
    ) {
        override fun canBecomeKeyWindow() = true
        override fun canBecomeMainWindow() = true

        override fun becomeKeyWindow() {
            super.becomeKeyWindow()
            isTheKeyWindow = true
            view.needsDisplay = true
        }

        override fun resignKeyWindow() {
            super.resignKeyWindow()
            isTheKeyWindow = false
            view.needsDisplay = true
        }
    }

    private val backdrop = object : NSVisualEffectView(contentBounds(width, height)) {
        override fun setFrameSize(newSize: CValue<CGSize>) {
            super.setFrameSize(newSize)
            measureCaption()
        }

        override fun accessibilityRole(): String? = MacosAx.GROUP
        override fun isAccessibilityElement(): Boolean = true
        override fun accessibilityChildren(): List<*>? {
            if (!readerIsListening) {
                readerIsListening = true
                view.needsDisplay = true
            }
            pumpHost(0)
            host?.describeForReaders()
            return reader.roots
        }
    }.also {
        it.material = NSVisualEffectMaterialUnderWindowBackground
        it.blendingMode = NSVisualEffectBlendingMode.NSVisualEffectBlendingModeBehindWindow
        it.state = NSVisualEffectState.NSVisualEffectStateFollowsWindowActiveState
        it.autoresizingMask = NSViewWidthSizable or NSViewHeightSizable
    }

    internal val view: NSView = object : NSView(contentBounds(width, height)), CALayerDelegateProtocol, NSTextInputClientProtocol,
        NSDraggingDestinationProtocol {
        private var tracking: NSTrackingArea? = null

        override fun draggingEntered(sender: NSDraggingInfoProtocol): NSDragOperation =
            if (FileDrop.carriesFiles(sender.draggingPasteboard)) NSDragOperationCopy
            else NSDragOperationNone

        override fun draggingUpdated(sender: NSDraggingInfoProtocol): NSDragOperation =
            draggingEntered(sender)

        override fun performDragOperation(sender: NSDraggingInfoProtocol): Boolean {
            val paths = FileDrop.paths(sender.draggingPasteboard)
            if (paths.isEmpty()) return false
            FileDrop.dropped.value = paths
            return true
        }

        private var marked: String = ""

        override fun insertText(string: Any, replacementRange: CValue<NSRange>) {
            marked = ""
            val text = string.asText()
            val inserted = insertableText(text)
            KeyLog.insertText(text, inserted)
            if (inserted.isNotEmpty()) {
                onEvent?.invoke(
                    WindowEvent(
                        kind = WindowEvent.TEXT_COMMIT,
                        x = 0f, y = 0f, buttons = 0, modifiers = 0, keyCode = 0, codePoint = 0,
                        text = inserted,
                    ),
                )
            }
        }

        override fun setMarkedText(
            string: Any,
            selectedRange: CValue<NSRange>,
            replacementRange: CValue<NSRange>,
        ) {
            marked = string.asText()
            onEvent?.invoke(
                WindowEvent(
                    kind = WindowEvent.TEXT_COMPOSE,
                    x = 0f, y = 0f, buttons = 0, modifiers = 0, keyCode = 0, codePoint = 0,
                    text = marked,
                ),
            )
        }

        override fun unmarkText() {
            marked = ""
            onEvent?.invoke(
                WindowEvent(
                    kind = WindowEvent.TEXT_COMPOSE,
                    x = 0f, y = 0f, buttons = 0, modifiers = 0, keyCode = 0, codePoint = 0,
                    text = "",
                ),
            )
        }

        override fun hasMarkedText(): Boolean = marked.isNotEmpty()

        override fun markedRange(): CValue<NSRange> =
            if (marked.isEmpty()) NSMakeRange(NSNotFound.toULong(), 0u)
            else NSMakeRange(0u, marked.length.toULong())

        override fun selectedRange(): CValue<NSRange> = NSMakeRange(NSNotFound.toULong(), 0u)

        override fun validAttributesForMarkedText(): List<*> = emptyList<Any>()

        override fun attributedSubstringForProposedRange(
            range: CValue<NSRange>,
            actualRange: NSRangePointer?,
        ): NSAttributedString? = null

        override fun firstRectForCharacterRange(
            range: CValue<NSRange>,
            actualRange: NSRangePointer?,
        ): CValue<CGRect> {
            val origin = window?.frame?.useContents { CGRectMake(origin.x, origin.y, 0.0, 0.0) }
            return origin ?: CGRectMake(0.0, 0.0, 0.0, 0.0)
        }

        override fun characterIndexForPoint(point: CValue<CGPoint>): ULong =
            NSNotFound.toULong()

        override fun doCommandBySelector(selector: CPointer<out CPointed>?) {
            val name = NSStringFromSelector(selector)
            if (inKeyDown) {
                KeyLog.line("doCommandBySelector $name during-key=1")
                return
            }
            if (name == null) return
            val keys = editingKeyEvents(name)
            KeyLog.command(name, keys != null)
            if (keys != null) {
                for (keyEvent in keys) {
                    val code = MAC_KEY_CODES[keyEvent.key] ?: 0
                    var modifiers = 0
                    if (keyEvent.isCtrlPressed) modifiers = modifiers or (1 shl 18)
                    if (keyEvent.isMetaPressed) modifiers = modifiers or (1 shl 20)
                    if (keyEvent.isShiftPressed) modifiers = modifiers or (1 shl 17)
                    if (keyEvent.isAltPressed) modifiers = modifiers or (1 shl 19)
                    onEvent?.invoke(
                        WindowEvent(
                            kind = if (keyEvent.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) WindowEvent.KEY_DOWN else WindowEvent.KEY_UP,
                            x = 0f,
                            y = 0f,
                            buttons = 0,
                            modifiers = modifiers,
                            keyCode = code,
                            codePoint = 0,
                            text = "",
                        ),
                    )
                }
            } else {
                onEvent?.invoke(
                    WindowEvent(
                        kind = WindowEvent.EDIT_COMMAND,
                        x = 0f,
                        y = 0f,
                        buttons = 0,
                        modifiers = 0,
                        keyCode = 0,
                        codePoint = 0,
                        text = name,
                    ),
                )
            }
        }

        private var inKeyDown = false

        private fun Any.asText(): String = when (this) {
            is NSAttributedString -> string
            else -> toString()
        }

        override fun isFlipped() = true

        override fun makeBackingLayer(): CALayer = metal.layer

        override fun wantsUpdateLayer() = true

        override fun displayLayer(layer: CALayer) {
            VerticalResizeProbe.via = "display"
            if (resizePainter != null) {
                resizePainter?.invoke()
            } else {
                updateLayer()
            }
            VerticalResizeProbe.via = "loop"
        }

        override fun updateLayer() {
            val scale = window?.backingScaleFactor ?: 1.0
            bounds.useContents { metal.resize(size.width, size.height, scale) }
            ResizeMetrics.frameBegin()
            val drew = metal.draw(::paintFrame)
            bounds.useContents {
                ResizeMetrics.frameEnd((size.width * scale).toInt(), (size.height * scale).toInt())
            }
            if (drew) onFrameDrawn?.invoke(measured.width, measured.height)
        }

        override fun setFrameSize(newSize: CValue<CGSize>) {
            val oldSize = frame.useContents { this.size.width to this.size.height }
            super.setFrameSize(newSize)
            val size = newSize.useContents { this.width to this.height }
            VerticalResizeProbe.setFrame(oldSize, size)
            val scale = window?.backingScaleFactor ?: 1.0
            resizeStats.step((size.first * scale).toInt(), (size.second * scale).toInt())
            measureCaption()
            if (window != null) {
                VerticalResizeProbe.via = "set-frame"
                if (resizePainter != null) {
                    resizePainter?.invoke()
                } else {
                    updateLayer()
                }
                VerticalResizeProbe.via = "loop"
            }
        }

        override fun acceptsFirstResponder() = true

        override fun hitTest(aPoint: CValue<CGPoint>): NSView? =
            if (NSPointInRect(convertPoint(aPoint, fromView = superview), bounds)) this
            else null

        override fun isAccessibilityElement(): Boolean = false

        override fun acceptsFirstMouse(event: NSEvent?) = true
        override fun viewWillMoveToWindow(newWindow: NSWindow?) = updateTrackingAreas()

        override fun updateTrackingAreas() {
            tracking?.let { removeTrackingArea(it) }
            val area = NSTrackingArea(
                rect = bounds,
                options = NSTrackingActiveAlways or NSTrackingMouseEnteredAndExited or
                    NSTrackingMouseMoved or NSTrackingActiveInKeyWindow or
                    NSTrackingAssumeInside or NSTrackingInVisibleRect,
                owner = this,
                userInfo = null,
            )
            tracking = area
            addTrackingArea(area)
        }

        private fun postPointer(event: NSEvent, kind: Int, secondary: Boolean = false) {
            val position = event.offsetInView
            var buttons = NSEvent.pressedMouseButtons.toInt()
            if (secondary) buttons = buttons or WindowEvent.SECONDARY_BUTTON
            onEvent?.invoke(
                WindowEvent(
                    kind = kind,
                    x = position.x,
                    y = position.y,
                    buttons = buttons,
                    modifiers = event.modifierFlags.toInt(),
                    keyCode = 0,
                    codePoint = 0,
                    text = "",
                ),
            )
        }

        override fun mouseDown(event: NSEvent) {
            if (hasMarkedText()) inputContext?.discardMarkedText()
            val control = event.modifierFlags and NSEventModifierFlagControl != 0uL
            postPointer(event, WindowEvent.POINTER_DOWN, secondary = control)
        }

        override fun mouseUp(event: NSEvent) =
            postPointer(event, WindowEvent.POINTER_UP)

        override fun rightMouseDown(event: NSEvent) =
            postPointer(event, WindowEvent.POINTER_DOWN, secondary = true)

        override fun rightMouseUp(event: NSEvent) =
            postPointer(event, WindowEvent.POINTER_UP, secondary = true)

        override fun mouseMoved(event: NSEvent) =
            postPointer(event, WindowEvent.POINTER_MOVE)

        override fun mouseDragged(event: NSEvent) =
            postPointer(event, WindowEvent.POINTER_MOVE)

        override fun scrollWheel(event: NSEvent) {
            val divisor = if (event.hasPreciseScrollingDeltas) 10f else 1f
            onEvent?.invoke(
                WindowEvent(
                    kind = WindowEvent.SCROLL,
                    x = (-event.scrollingDeltaX.toFloat() / divisor),
                    y = (-event.scrollingDeltaY.toFloat() / divisor),
                    buttons = NSEvent.pressedMouseButtons.toInt(),
                    modifiers = event.modifierFlags.toInt(),
                    keyCode = 0,
                    codePoint = 0,
                    text = "",
                ),
            )
        }

        override fun menuForEvent(event: NSEvent): NSMenu? = null

        override fun keyDown(event: NSEvent) {
            KeyLog.platform(event.keyCode.toInt(), event.modifierFlags.toLong(), event.characters, down = true)
            val codePoint = event.charactersIgnoringModifiers?.firstOrNull()?.code ?: 0
            onEvent?.invoke(
                WindowEvent(
                    kind = WindowEvent.KEY_DOWN,
                    x = 0f,
                    y = 0f,
                    buttons = 0,
                    modifiers = event.modifierFlags.toInt(),
                    keyCode = event.keyCode.toInt(),
                    codePoint = codePoint,
                    text = "",
                ),
            )
            if (!reachesInputMethod(event.modifierFlags.toLong())) return
            inKeyDown = true
            try {
                inputContext?.handleEvent(event)
            } finally {
                inKeyDown = false
            }
        }

        override fun keyUp(event: NSEvent) {
            KeyLog.platform(event.keyCode.toInt(), event.modifierFlags.toLong(), event.characters, down = false)
            val codePoint = event.charactersIgnoringModifiers?.firstOrNull()?.code ?: 0
            onEvent?.invoke(
                WindowEvent(
                    kind = WindowEvent.KEY_UP,
                    x = 0f,
                    y = 0f,
                    buttons = 0,
                    modifiers = event.modifierFlags.toInt(),
                    keyCode = event.keyCode.toInt(),
                    codePoint = codePoint,
                    text = "",
                ),
            )
        }

        override fun performKeyEquivalent(event: NSEvent): Boolean {
            if (window?.firstResponder != this) return super.performKeyEquivalent(event)
            if (!isEditingShortcut(event.keyCode.toInt(), event.modifierFlags.toLong())) {
                return super.performKeyEquivalent(event)
            }
            keyDown(event)
            keyUp(event)
            return true
        }
    }

    private var windowInitialized = false

    fun initWindow() {
        if (windowInitialized) return
        windowInitialized = true
        window.setTitle(name)
        applyChrome(window, chrome)
        installContent(window, backdrop, view)
        window.opaque = false
        window.backgroundColor = NSColor.clearColor

        view.wantsLayer = true
        view.layerContentsRedrawPolicy = NSViewLayerContentsRedrawDuringViewResize

        minimumSize?.let { (minWidth, minHeight) ->
            window.contentMinSize = NSMakeSize(minWidth, minHeight)
        }
        window.center()
        window.makeKeyAndOrderFront(null)
        window.makeFirstResponder(view)
        dispatch_async(dispatch_get_main_queue()) {
            measureCaption()
        }
        view.registerForDraggedTypes(listOf(NSFilenamesPboardType))
        measureCaption()
    }

    fun setContent(content: @Composable () -> Unit) {
        initWindow()
        val platform = MacosWindowPlatform()
        platform.attachWindow(this)
        val config = WindowConfig(name, width, height)
        val createdHost = ComposeWindowHost(platform, config, ::macosWindowSurface, content = content)
        this.host = createdHost
        createdHost.open()
        startLoopTimer()

        if (ResizeMetrics.enabled) {
            dispatch_after(
                dispatch_time(DISPATCH_TIME_NOW, 3_000_000_000L),
                dispatch_get_main_queue(),
            ) { runMetrics() }
        }
        if (ResizeSoak.enabled) ResizeSoak.start(window, metal)
        if (VerticalResizeProbe.enabled) {
            metal.overlay = { canvas, w, h ->
                VerticalResizeProbe.mark(canvas, w, h, window.backingScaleFactor, metal.drawableWaitMillis)
            }
            VerticalResizeProbe.start(window, view, metal.layer)
        }
    }

    private fun runMetrics() {
        val phase = { phaseName: String ->
            val (footprint, resident) = processMemory()
            printError(
                "compose-rust: metrics phase $phaseName ${ResizeMetrics.summary()} " +
                    "skia_cache_limit_mb=${metal.cacheLimitBytes / 1048576} " +
                    "metal_allocated_mb=${metal.allocatedBytes / 1048576} " +
                    "footprint_mb=${footprint / 1048576} resident_mb=${resident / 1048576}",
            )
            if (platform.posix.getenv("DXC_METRICS_BREAKDOWN")?.toKString() == "1") {
                val pid = platform.posix.getpid()
                platform.posix.fflush(null)
                platform.posix.system(
                    "echo 'compose-rust: breakdown $phaseName' 1>&2; /usr/bin/footprint $pid 1>&2",
                )
            }
        }
        ResizeMetrics.run(
            phase = phase,
            resize = { from, to, steps ->
                for (step in 1..steps) kotlinx.cinterop.autoreleasepool {
                    val t = step.toDouble() / steps
                    window.setContentSize(
                        NSMakeSize(
                            from.first + (to.first - from.first) * t,
                            from.second + (to.second - from.second) * t,
                        ),
                    )
                    window.displayIfNeeded()
                    platform.QuartzCore.CATransaction.flush()
                }
            },
        )
        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, 2_000_000_000L),
            dispatch_get_main_queue(),
        ) {
            phase("settled")
            dragAnEdge(phase)
        }
    }

    private fun dragAnEdge(phase: (String) -> Unit) {
        val events = 300
        val distance = 450.0
        val pause = 15
        val checkAt = setOf(100, 200, 300)
        val startFrame = window.frame.useContents { size.width to size.height }
        val rightEdgeX = window.frame.useContents { origin.x + size.width }
        val midY = window.frame.useContents { origin.y + size.height / 2 }

        val startEvent = NSEvent.mouseEventWithType(
            type = platform.AppKit.NSEventTypeLeftMouseDown,
            location = platform.CoreGraphics.CGPointMake(rightEdgeX, midY),
            modifierFlags = 0u,
            timestamp = NSProcessInfo.processInfo.systemUptime,
            windowNumber = window.windowNumber,
            context = null,
            eventNumber = 0L,
            clickCount = 1,
            pressure = 1f,
        ) ?: return
        NSApplication.sharedApplication().postEvent(startEvent, atStart = false)

        var eventIndex = 0
        var timer: platform.Foundation.NSTimer? = null
        timer = platform.Foundation.NSTimer.scheduledTimerWithTimeInterval(
            interval = 1.0 / 60.0,
            repeats = true,
        ) { _: platform.Foundation.NSTimer? ->
            eventIndex++
            if (eventIndex in checkAt) {
                checkOnScreen(eventIndex)
            }
            if (eventIndex <= events) {
                val t = eventIndex.toDouble() / events
                val currentX = rightEdgeX + distance * t
                val moveEvent = NSEvent.mouseEventWithType(
                    type = platform.AppKit.NSEventTypeLeftMouseDragged,
                    location = platform.CoreGraphics.CGPointMake(currentX, midY),
                    modifierFlags = 0u,
                    timestamp = NSProcessInfo.processInfo.systemUptime,
                    windowNumber = window.windowNumber,
                    context = null,
                    eventNumber = eventIndex.toLong(),
                    clickCount = 1,
                    pressure = 1f,
                )
                if (moveEvent != null) {
                    NSApplication.sharedApplication().postEvent(moveEvent, atStart = false)
                }
            } else {
                timer?.invalidate()
                val upEvent = NSEvent.mouseEventWithType(
                    type = platform.AppKit.NSEventTypeLeftMouseUp,
                    location = platform.CoreGraphics.CGPointMake(rightEdgeX + distance, midY),
                    modifierFlags = 0u,
                    timestamp = NSProcessInfo.processInfo.systemUptime,
                    windowNumber = window.windowNumber,
                    context = null,
                    eventNumber = eventIndex.toLong(),
                    clickCount = 1,
                    pressure = 0f,
                )
                if (upEvent != null) {
                    NSApplication.sharedApplication().postEvent(upEvent, atStart = false)
                }
                phase("dragged")
            }
        }
    }

    private fun checkOnScreen(atEvent: Int) {
        val scale = window.backingScaleFactor
        @Suppress("DEPRECATION")
        val image = platform.CoreGraphics.CGWindowListCreateImage(
            platform.CoreGraphics.CGRectNull.readValue(),
            1u shl 3,
            window.windowNumber.toUInt(),
            (1u shl 0) or (1u shl 3),
        ) ?: return
        platform.CoreGraphics.CGImageRelease(image)
    }

    private fun processMemory(): Pair<Long, Long> = memScoped {
        val info = alloc<platform.darwin.task_vm_info_data_t>()
        val count = alloc<platform.darwin.mach_msg_type_number_tVar>()
        count.value = (kotlinx.cinterop.sizeOf<platform.darwin.task_vm_info_data_t>() / 4).toUInt()
        platform.darwin.task_info(
            platform.darwin.mach_task_self_,
            22u,
            info.ptr.reinterpret(),
            count.ptr,
        )
        info.phys_footprint.toLong() to info.resident_size.toLong()
    }

    fun inject(event: WindowEvent) {
        when (event.kind) {
            WindowEvent.POINTER_DOWN, WindowEvent.POINTER_UP -> {
                val scale = window.backingScaleFactor
                val height = view.bounds.useContents { size.height }
                val posted = NSEvent.mouseEventWithType(
                    type = if (event.kind == WindowEvent.POINTER_DOWN) {
                        platform.AppKit.NSEventTypeLeftMouseDown
                    } else {
                        platform.AppKit.NSEventTypeLeftMouseUp
                    },
                    location = platform.Foundation.NSMakePoint(event.x / scale, height - event.y / scale),
                    modifierFlags = 0u,
                    timestamp = NSProcessInfo.processInfo.systemUptime,
                    windowNumber = window.windowNumber,
                    context = null,
                    eventNumber = 0L,
                    clickCount = 1,
                    pressure = if (event.kind == WindowEvent.POINTER_DOWN) 1f else 0f,
                ) ?: return
                NSApplication.sharedApplication().postEvent(posted, atStart = false)
            }
            WindowEvent.TEXT_COMMIT -> {
                if (event.text.isNotEmpty()) onEvent?.invoke(event)
            }
            else -> onEvent?.invoke(event)
        }
        view.needsDisplay = true
    }

    fun scriptedResize(toWidth: Double, toHeight: Double, steps: Int, pauseSeconds: Double) {
        if (steps <= 0) return
        resizeStats.reset()
        val from = view.frame.useContents { size.width to size.height }
        for (step in 1..steps) {
            val t = step.toDouble() / steps
            window.setContentSize(
                NSMakeSize(from.first + (toWidth - from.first) * t, from.second + (toHeight - from.second) * t),
            )
            platform.QuartzCore.CATransaction.flush()
            NSRunLoop.currentRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(pauseSeconds))
        }
    }

    private fun printError(message: String) {
        platform.posix.fputs("$message\n", platform.posix.stderr)
    }

    private val NSEvent.offsetInView: Offset
        get() = scenePoint(view, locationInWindow, view.window?.backingScaleFactor ?: 1.0)
}

/** Holds the closure a menu item runs, because a menu item calls a selector on a target. */
class MenuShortcut(private val run: () -> Unit) : platform.darwin.NSObject() {
    @kotlinx.cinterop.ObjCAction
    fun perform() = run()
}

/** A rectangle the size of the window's content, at the origin of its parent. */
internal fun contentBounds(width: Int, height: Int): CValue<CGRect> =
    platform.Foundation.NSMakeRect(0.0, 0.0, width.toDouble(), height.toDouble())

/**
 * Makes [backdrop] the window's content view and puts [view] over the whole of it.
 */
internal fun installContent(window: NSWindow, backdrop: NSView, view: NSView) {
    window.contentView = backdrop
    view.setFrame(backdrop.bounds)
    view.autoresizingMask = NSViewWidthSizable or NSViewHeightSizable
    backdrop.addSubview(view)
}

/**
 * Where a point in the window's coordinates falls in the scene, in pixels.
 */
internal fun scenePoint(view: NSView, locationInWindow: CValue<CGPoint>, scale: Double): Offset =
    view.convertPoint(locationInWindow, fromView = null).useContents {
        Offset((x * scale).toFloat(), (y * scale).toFloat())
    }

/**
 * The radius the system draws on [window]'s corners under [chrome], from the table in the
 * common window module, or null for a release that table does not cover.
 */
internal fun systemCornerRadius(chrome: MacosWindowChrome): Double? =
    macosCornerRadius(
        toolbar = chrome.unifiedToolbar,
        macosMajor = NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion }.toInt(),
    )

/**
 * Builds [window]'s title bar the way [chrome] says, as the GraalVM window does when it opens.
 */
internal fun applyChrome(window: NSWindow, chrome: MacosWindowChrome) {
    window.titlebarAppearsTransparent = chrome.titlebarAppearsTransparent
    window.titleVisibility = if (chrome.titleHidden) NSWindowTitleHidden else NSWindowTitleVisible
    if (chrome.unifiedToolbar) {
        val toolbar = NSToolbar(identifier = "org.thisisthepy.compose.window")
        toolbar.showsBaselineSeparator = false
        window.toolbar = toolbar
        window.toolbarStyle = NSWindowToolbarStyle.NSWindowToolbarStyleUnified
    } else {
        window.toolbar = null
    }
}

/**
 * The time now in nanoseconds of host time, the clock AppKit stamps events with and the one
 * Rust's `Instant` reads on macOS.
 */
internal fun uptimeNanos(): Long = (NSProcessInfo.processInfo.systemUptime * 1_000_000_000.0).toLong()
