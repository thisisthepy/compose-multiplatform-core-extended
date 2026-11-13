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
import platform.AppKit.layoutIfNeeded
import platform.AppKit.postEvent
import platform.AppKit.voiceOverEnabled
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGPointMake
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
    /**
     * Where the outer frame opens: its top left corner in screen points from the top left of
     * the primary screen, y down. Null centres the first window and cascades the ones after it.
     */
    val topLeft: Pair<Int, Int>? = null,
) {
    private var measured = IntSize(width, height)
    private var isTheKeyWindow by mutableStateOf(true)

    internal val metal = MetalSurface()
    val metalSurface: MetalSurface get() = metal

    internal var frameTime: () -> Long = ::uptimeNanos

    var onEvent: ((WindowEvent) -> Unit)? = null

    /**
     * Stands in for the view's input context, for a test that has to know what the input
     * method answers a key with. Null is the system's input method.
     */
    internal var inputMethod: ((NSEvent) -> Boolean)? = null
    /** The close button was pressed. Returns true to let the window close, false to keep it. */
    var onClose: (() -> Boolean)? = null

    /** The window became or stopped being the key window. */
    var onFocus: ((Boolean) -> Unit)? = null

    /** The frame moved or changed size since the platform last reported it. Set by the delegate. */
    internal var movedPending = false

    private val windowDelegate = MacosWindowDelegate(this)

    /**
     * Put up when the secondary button is pressed, after the press has reached the scene so the
     * field under the pointer has taken focus. The menu tracks the pointer until it is chosen
     * from or dismissed, and takes the release with it, so the release is sent once it returns.
     * Null puts up nothing, which is what an application that draws the menu itself wants.
     */
    var onContextMenu: (() -> Unit)? = null

    /**
     * True to have the platform put up the system's edit menu (Cut, Copy, Paste, Select All) on
     * a right click. Off by default: an application whose Compose answers a text field's menu
     * with an `NSMenu` of its own would show two.
     */
    var editMenuOnRightClick: Boolean = false

    var resizePainter: (() -> Boolean)? = null
    var host: ComposeWindowHost? = null

    private var inTurn = false

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

    /**
     * Reads the caption off the window as it is now, and asks for a frame when it changed.
     *
     * Never from inside a change of the window's frame. The strip's height is the window's
     * height less its content layout rect's, and AppKit moves that rect when it lays the
     * title bar out, which is after the views have been given their new size. Read in
     * between, a window taken from 360 to 808 points in one step has a strip 448 points
     * taller than it is, and the frame drawn for the new size is laid out under it. That
     * frame then stays on the screen: the display that follows reads the strip right and
     * draws again, but it presents in the same transaction, and of two frames presented in
     * one transaction the layer shows the first.
     */
    internal fun measureCaption() {
        // A frame set without displaying leaves the title bar where it was until the window
        // is next laid out, and the rect with it.
        window.layoutIfNeeded()
        val close = window.standardWindowButton(NSWindowCloseButton)
        val zoom = window.standardWindowButton(NSWindowZoomButton)
        val measured = macosWindowCaption(
            chrome = chrome,
            windowHeight = window.frame.useContents { size.height },
            contentLayoutHeight = window.contentLayoutRect.useContents { size.height },
            closeMinX = close?.frame?.useContents { origin.x },
            zoomMaxX = zoom?.frame?.useContents { origin.x + size.width },
            cornerRadius = systemCornerRadius(chrome),
        ) ?: return
        if (measured == caption.value) return
        caption.value = measured
        // Content that reads the strip is laid out again by the next frame, so there has to
        // be one: a window that is not being resized draws nothing until something asks.
        requestFrame()
    }

    // With a full size content view the title bar is not laid out yet when the first reads
    // happen (contentLayoutRect equals the frame, so the height is 0), and a window that
    // never resizes would keep that 0. Measure again after the window is shown, a bounded
    // number of times, until a non-zero height arrives.
    private var closed = false

    private fun remeasureCaption(attempt: Int) {
        if (closed) return
        measureCaption()
        if (!shouldRetryCaptionMeasure(attempt, caption.value.height.value, chrome.fullSizeContentView)) return
        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, CAPTION_RETRY_DELAYS_NANOS[attempt]),
            dispatch_get_main_queue(),
        ) {
            remeasureCaption(attempt + 1)
        }
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
            if (chrome.fullSizeContentView && caption.value.height.value <= 0f) measureCaption()
            view.needsDisplay = true
        }

        override fun resignKeyWindow() {
            super.resignKeyWindow()
            isTheKeyWindow = false
            view.needsDisplay = true
        }
    }

    private val backdrop = object : NSVisualEffectView(contentBounds(width, height)) {
        override fun accessibilityRole(): String? = MacosAx.GROUP
        override fun isAccessibilityElement(): Boolean = true
        override fun accessibilityChildren(): List<*>? {
            if (!readerIsListening) {
                readerIsListening = true
                view.needsDisplay = true
            }
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
            // Where the caret is, so the candidate window opens beside what is being typed
            // and follows the caret when a key moves it. The scene reports it in pixels from
            // the top left; the view is flipped, so only the scale is taken out.
            val w = window ?: return CGRectMake(0.0, 0.0, 0.0, 0.0)
            val scale = if (w.backingScaleFactor > 0.0) w.backingScaleFactor else 1.0
            val local = CGRectMake(imeSpot.first / scale, imeSpot.second / scale, 1.0, IME_LINE_POINTS)
            return w.convertRectToScreen(convertRect(local, toView = null))
        }

        override fun characterIndexForPoint(point: CValue<CGPoint>): ULong =
            NSNotFound.toULong()

        override fun doCommandBySelector(selector: CPointer<out CPointed>?) {
            val name = NSStringFromSelector(selector)
            if (inKeyDown) {
                KeyLog.line("doCommandBySelector $name during-key=1")
                commandDuringKey = true
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

        // Whether the input method handed the key being handled back as a command, which is
        // how it says the key was not part of what it is composing.
        private var commandDuringKey = false

        private fun Any.asText(): String = when (this) {
            is NSAttributedString -> string
            else -> toString()
        }

        override fun isFlipped() = true

        override fun makeBackingLayer(): CALayer = metal.layer

        override fun wantsUpdateLayer() = true

        override fun displayLayer(layer: CALayer) {
            VerticalResizeProbe.via = "display"
            updateLayer()
            VerticalResizeProbe.via = "loop"
        }

        override fun updateLayer() {
            val scale = window?.backingScaleFactor ?: 1.0
            bounds.useContents { metal.resize(size.width, size.height, scale) }
            ResizeMetrics.frameBegin()
            val drew = if (host != null) {
                metal.draw(::paintFrame)
            } else {
                val painted = resizePainter?.invoke() == true
                if (painted) {
                    frame.useContents {
                        val w = (this.size.width * scale).toInt()
                        val h = (this.size.height * scale).toInt()
                        measured = IntSize(w, h)
                        resizeStats.presentAgainst(w, h, w, h)
                    }
                }
                painted
            }
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
            // The caption is not measured here: the window's frame is new and its content
            // layout rect is not yet, so the strip would come out taller or shorter by the
            // change in height. This frame is drawn with the last reading, which a change of
            // size does not alter, and the platform reads it again on its next turn.
            if (window != null) {
                VerticalResizeProbe.via = "set-frame"
                updateLayer()
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

        // Whether the primary button's press went to the scene as a secondary one (control was
        // held), so that its release is marked the same, whatever is held by then.
        private var controlClick = false

        private fun postPointer(event: NSEvent, kind: Int, eventButton: Int = 0) {
            val position = event.offsetInView
            val buttons = WindowEvent.pointerButtons(NSEvent.pressedMouseButtons.toInt(), eventButton)
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
            // Control with the primary button is the secondary button on this platform.
            if (control && onContextMenu != null) return rightMouseDown(event)
            controlClick = control
            postPointer(event, WindowEvent.POINTER_DOWN, if (control) WindowEvent.SECONDARY_BUTTON else 0)
        }

        override fun mouseUp(event: NSEvent) {
            val was = controlClick
            controlClick = false
            postPointer(event, WindowEvent.POINTER_UP, if (was) WindowEvent.SECONDARY_BUTTON else 0)
        }

        override fun rightMouseDown(event: NSEvent) {
            postPointer(event, WindowEvent.POINTER_DOWN, WindowEvent.SECONDARY_BUTTON)
            val menu = onContextMenu ?: return
            menu()
            // The menu's tracking swallowed the release; without this the scene would go on
            // believing a button is held down.
            postPointer(event, WindowEvent.POINTER_UP, WindowEvent.SECONDARY_BUTTON)
        }

        override fun rightMouseUp(event: NSEvent) {
            if (onContextMenu != null) return
            postPointer(event, WindowEvent.POINTER_UP, WindowEvent.SECONDARY_BUTTON)
        }

        override fun otherMouseDown(event: NSEvent) =
            postPointer(event, WindowEvent.POINTER_DOWN, eventButtonOfMacButton(event.buttonNumber.toInt()))

        override fun otherMouseUp(event: NSEvent) =
            postPointer(event, WindowEvent.POINTER_UP, eventButtonOfMacButton(event.buttonNumber.toInt()))

        override fun mouseMoved(event: NSEvent) =
            postPointer(event, WindowEvent.POINTER_MOVE)

        override fun mouseDragged(event: NSEvent) =
            postPointer(event, WindowEvent.POINTER_MOVE)

        override fun rightMouseDragged(event: NSEvent) =
            postPointer(event, WindowEvent.POINTER_MOVE)

        override fun otherMouseDragged(event: NSEvent) =
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
            val toInputMethod = reachesInputMethod(event.modifierFlags.toLong())
            val key = WindowEvent(
                kind = WindowEvent.KEY_DOWN,
                x = 0f,
                y = 0f,
                buttons = 0,
                modifiers = event.modifierFlags.toInt(),
                keyCode = event.keyCode.toInt(),
                codePoint = keyCodePoint(event.charactersIgnoringModifiers, toInputMethod),
                text = "",
            )
            if (toInputMethod && marked.isNotEmpty()) {
                // Something is being composed, so the key is the input method's first. An
                // arrow with a Korean syllable marked makes it commit the syllable and hand
                // the arrow back as a command; the scene then reads the commit before the
                // key, and the caret moves from the end of the finished syllable. Read the
                // other way round, the key moved the caret under an open composition and the
                // commit that followed pulled it back. A key the input method keeps (the next
                // letter, or an arrow inside a Japanese conversion) is not the scene's at all.
                commandDuringKey = false
                var handled = false
                inKeyDown = true
                try {
                    handled = handleByInputMethod(event)
                } finally {
                    inKeyDown = false
                }
                KeyLog.line("composing key handled=$handled command=$commandDuringKey")
                if (keyIsLeftForScene(handled, commandDuringKey)) onEvent?.invoke(key)
                return
            }
            onEvent?.invoke(key)
            if (!toInputMethod) return
            inKeyDown = true
            try {
                handleByInputMethod(event)
            } finally {
                inKeyDown = false
            }
        }

        private fun handleByInputMethod(event: NSEvent): Boolean =
            inputMethod?.invoke(event) ?: (inputContext?.handleEvent(event) == true)

        override fun keyUp(event: NSEvent) {
            KeyLog.platform(event.keyCode.toInt(), event.modifierFlags.toLong(), event.characters, down = false)
            val codePoint = keyCodePoint(
                event.charactersIgnoringModifiers,
                reachesInputMethod(event.modifierFlags.toLong()),
            )
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

    // What the input method is composing, as it last said. Held here and not in the field,
    // which is on the scene's side.
    private var marked: String = ""

    /** Where the focused field's caret is, in pixels from the content's top left. */
    var imeSpot: Pair<Int, Int> = 0 to 0

    /**
     * Makes the input method let go of what it is composing, sending nothing. The field has
     * kept the text; see [org.thisisthepy.compose.window.WindowPlatform.endComposition].
     */
    fun endComposition() {
        if (marked.isEmpty()) return
        marked = ""
        view.inputContext?.discardMarkedText()
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
        // The delegate is weak on the AppKit side; windowDelegate holds it for the window's life.
        window.delegate = windowDelegate
        // The window is released by whoever created it, never by AppKit on close, so one window
        // closing cannot free memory another reference still uses.
        window.releasedWhenClosed = false
        placeWindow()
        window.makeKeyAndOrderFront(null)
        window.makeFirstResponder(view)
        dispatch_async(dispatch_get_main_queue()) {
            remeasureCaption(0)
        }
        view.registerForDraggedTypes(listOf(NSFilenamesPboardType))
        measureCaption()
    }

    private fun placeWindow() {
        MacosCascade.alive++
        val at = topLeft
        if (at != null) {
            window.setFrameTopLeftPoint(topLeftToAppKit(at.first.toDouble(), at.second.toDouble()))
            return
        }
        window.center()
        val next = MacosCascade.next
        MacosCascade.next = if (next == null) {
            window.frame.useContents { CGPointMake(origin.x, origin.y + size.height) }
        } else {
            window.cascadeTopLeftFromPoint(next)
        }
    }

    internal fun windowClosed() {
        closed = true
        MacosCascade.closed()
    }

    fun setContent(content: @Composable () -> Unit) {
        initWindow()
        val platform = MacosWindowPlatform()
        platform.nativeEditMenu = editMenuOnRightClick
        platform.attachWindow(this)
        val config = WindowConfig(name, width, height)
        val createdHost = ComposeWindowHost(
            platform = platform,
            config = config,
            surfaceFor = ::macosWindowSurface,
            padContentForCaption = false,
            content = content,
        )
        this.host = createdHost
        createdHost.open()

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
        val startWidth = window.frame.useContents { size.width }
        val y = window.frame.useContents { size.height } / 2
        // Two points inside the edge, which AppKit counts as the edge.
        val x0 = startWidth - 2
        var sent = 0
        var paused = 0

        fun post(type: platform.AppKit.NSEventType, x: Double, pressure: Float) {
            val event = NSEvent.mouseEventWithType(
                type = type,
                location = platform.CoreGraphics.CGPointMake(x, y),
                modifierFlags = 0u,
                timestamp = NSProcessInfo.processInfo.systemUptime,
                windowNumber = window.windowNumber,
                context = null,
                eventNumber = sent.toLong(),
                clickCount = 1,
                pressure = pressure,
            ) ?: return
            NSApplication.sharedApplication().postEvent(event, atStart = false)
        }

        post(platform.AppKit.NSEventTypeLeftMouseDown, x0, 1f)
        // In the common modes, so the events arrive inside the tracking loop AppKit runs for
        // a live resize, as a hand's would.
        val timer = platform.Foundation.NSTimer.timerWithTimeInterval(
            interval = 1.0 / 60.0,
            repeats = true,
        ) { timer: platform.Foundation.NSTimer? ->
            when {
                paused > 0 -> {
                    paused--
                    if (paused == 0) {
                        checkOnScreen(sent)
                        metal.overlay = null
                    }
                }
                sent < events -> {
                    sent++
                    post(platform.AppKit.NSEventTypeLeftMouseDragged, x0 + distance * sent / events, 1f)
                    if (sent in checkAt) {
                        // Drawn now with the pattern over it, and looked at once the window
                        // server has had a few refreshes to show it.
                        metal.overlay = ::drawCheckPattern
                        view.needsDisplay = true
                        view.displayIfNeeded()
                        platform.QuartzCore.CATransaction.flush()
                        paused = pause
                    }
                }
                else -> {
                    timer?.invalidate()
                    val widened = DragMetrics.widenedBy(startWidth, window.frame.useContents { size.width })
                    printError(DragMetrics.eventDragLine(widened, events))
                    phase("event-drag")
                    post(platform.AppKit.NSEventTypeLeftMouseUp, x0 + distance, 0f)
                    dispatch_after(
                        dispatch_time(DISPATCH_TIME_NOW, 2_000_000_000L),
                        dispatch_get_main_queue(),
                    ) {
                        phase("event-settled")
                        printError("compose-rust: metrics done")
                    }
                }
            }
        }
        platform.Foundation.NSRunLoop.currentRunLoop.addTimer(timer, forMode = platform.Foundation.NSRunLoopCommonModes)
    }

    /** Black and white blocks four pixels square over the whole frame, each block's colour a function of where it is. */
    private fun drawCheckPattern(canvas: Canvas, width: Int, height: Int) {
        val black = org.jetbrains.skia.Paint().apply { color = 0xFF000000.toInt() }
        val white = org.jetbrains.skia.Paint().apply { color = 0xFFFFFFFF.toInt() }
        canvas.drawRect(org.jetbrains.skia.Rect.makeWH(width.toFloat(), height.toFloat()), black)
        for (by in 0 until (height + 3) / 4) {
            for (bx in 0 until (width + 3) / 4) {
                if (DragMetrics.blockIsWhite(bx, by)) {
                    canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(bx * 4f, by * 4f, 4f, 4f), white)
                }
            }
        }
    }

    /**
     * Takes the window's own image through CGWindowListCreateImage, which captures a
     * process's own windows without permission, and compares it pixel for pixel with the
     * pattern the last frame was drawn with, inside the view and clear of the title bar and
     * the rounded corners.
     */
    private fun checkOnScreen(atEvent: Int) {
        val scale = window.backingScaleFactor
        // kCGWindowListOptionIncludingWindow; kCGWindowImageBoundsIgnoreFraming and
        // kCGWindowImageBestResolution, so one image pixel is one backing pixel.
        @Suppress("DEPRECATION")
        val image = platform.CoreGraphics.CGWindowListCreateImage(
            platform.CoreGraphics.CGRectNull.readValue(),
            1u shl 3,
            window.windowNumber.toUInt(),
            (1u shl 0) or (1u shl 3),
        )
        if (image == null) {
            printError("compose-rust: metrics capture at=$atEvent error=no-image")
            return
        }
        val imageWidth = platform.CoreGraphics.CGImageGetWidth(image).toInt()
        val imageHeight = platform.CoreGraphics.CGImageGetHeight(image).toInt()
        val pixels = ByteArray(imageWidth * imageHeight * 4)
        pixels.usePinned { pinned ->
            val space = platform.CoreGraphics.CGColorSpaceCreateDeviceRGB()
            val context = platform.CoreGraphics.CGBitmapContextCreate(
                pinned.addressOf(0),
                imageWidth.toULong(),
                imageHeight.toULong(),
                8u,
                (imageWidth * 4).toULong(),
                space,
                platform.CoreGraphics.CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            )
            platform.CoreGraphics.CGContextDrawImage(
                context,
                platform.CoreGraphics.CGRectMake(0.0, 0.0, imageWidth.toDouble(), imageHeight.toDouble()),
                image,
            )
            platform.CoreGraphics.CGContextRelease(context)
            platform.CoreGraphics.CGColorSpaceRelease(space)
        }
        platform.CoreGraphics.CGImageRelease(image)

        val windowWidth = window.frame.useContents { size.width }
        val windowHeight = window.frame.useContents { size.height }
        val imageScale = imageWidth / windowWidth
        // Where the view is in the window, counted from the window's top left in pixels.
        val viewRect = view.convertRect(view.bounds, toView = null)
        val viewLeft = viewRect.useContents { origin.x } * scale
        val viewTop = viewRect.useContents { (windowHeight - (origin.y + size.height)) } * scale
        val viewWidth = viewRect.useContents { size.width } * scale
        val viewHeight = viewRect.useContents { size.height } * scale
        val barBottom = window.contentLayoutRect.useContents { windowHeight - (origin.y + size.height) } * scale
        val margin = (32 * scale).toInt()
        val fromX = margin
        val toX = (viewWidth - margin).toInt()
        val fromY = maxOf(margin / 4, (barBottom - viewTop).toInt() + margin / 4)
        val toY = (viewHeight - margin).toInt()

        var same = 0L
        var total = 0L
        if (kotlin.math.abs(imageScale - scale) < 0.01) {
            for (py in fromY until toY) {
                val iy = (viewTop + py).toInt()
                if (iy < 0 || iy >= imageHeight) continue
                for (px in fromX until toX) {
                    val ix = (viewLeft + px).toInt()
                    if (ix < 0 || ix >= imageWidth) continue
                    val at = (iy * imageWidth + ix) * 4
                    val luminance = (pixels[at].toInt() and 0xFF) + (pixels[at + 1].toInt() and 0xFF) +
                        (pixels[at + 2].toInt() and 0xFF)
                    if ((luminance > 3 * 128) == DragMetrics.blockIsWhite(px / 4, py / 4)) same++
                    total++
                }
            }
        }
        printError(
            DragMetrics.captureLine(
                atEvent, metal.widthInPixels, metal.heightInPixels, imageScale, scale,
                DragMetrics.matchFraction(same, total),
            ),
        )
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

/** The height the input method is told a line has, in points: enough to clear the caret. */
private const val IME_LINE_POINTS = 20.0

/**
 * The [WindowEvent] button flag of the button AppKit numbers [buttonNumber] in an `otherMouse`
 * event: 2 is the middle button, 3 and 4 the back and forward ones. The left and right buttons
 * have their own event types and are not numbered here, so any other number has no flag.
 */
internal fun eventButtonOfMacButton(buttonNumber: Int): Int = when (buttonNumber) {
    2 -> WindowEvent.MIDDLE_BUTTON
    3 -> WindowEvent.BACK_BUTTON
    4 -> WindowEvent.FORWARD_BUTTON
    else -> 0
}

private val CAPTION_RETRY_DELAYS_NANOS = longArrayOf(50_000_000L, 150_000_000L, 300_000_000L)

/**
 * Whether the caption is measured again after [attempt] (0 is the first measure after the
 * window is shown). Only a full size chrome that still reads a zero height retries, and at most
 * once per entry of [CAPTION_RETRY_DELAYS_NANOS].
 */
internal fun shouldRetryCaptionMeasure(attempt: Int, height: Float, fullSize: Boolean): Boolean =
    fullSize && height <= 0f && attempt < CAPTION_RETRY_DELAYS_NANOS.size
