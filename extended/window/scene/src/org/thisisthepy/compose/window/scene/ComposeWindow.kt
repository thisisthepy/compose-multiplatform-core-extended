@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package org.thisisthepy.compose.window.scene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlin.time.TimeSource
import org.jetbrains.skia.Surface
import org.thisisthepy.compose.window.FrameRequestCoalescer
import org.thisisthepy.compose.window.FramePresentLog
import org.thisisthepy.compose.window.ImeSession
import org.thisisthepy.compose.window.SystemTheme
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowEventLog
import org.thisisthepy.compose.window.WindowFrames
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.WindowPlatform
import org.thisisthepy.compose.window.candidateSpot

/** The system appearance the window reported, for content that follows it. */
val LocalSystemTheme = staticCompositionLocalOf { SystemTheme.Light }

/**
 * One frame's pixels, taken from the window and given back by [close].
 *
 * Whatever [begin][WindowSurface.begin] allocated (a render target, a drawable) is released
 * in [close], which the host calls after the frame is flushed and before it is presented.
 */
interface FrameTarget : AutoCloseable {
    /** The surface the scene paints into, exactly the size [WindowSurface.begin] was asked for. */
    val surface: Surface
}

/**
 * Where a window's frames are drawn: Metal on macOS, GL on Linux.
 *
 * Implemented beside the platform layer that owns the native handles, because the handles
 * (the Metal device and queue, the GL context) do not cross [WindowPlatform]. The host never
 * names a graphics API.
 */
interface WindowSurface : AutoCloseable {
    /**
     * Takes the next frame, [width] by [height] pixels, or null where the system has none to
     * give. Null is not a failure: it means frames are made faster than the screen takes
     * them, and the host skips one.
     */
    fun begin(width: Int, height: Int): FrameTarget?

    /**
     * Registers what draws a frame from inside the window's own resize handling, or removes
     * it when null. The display server has already moved the edge when a resize arrives, so
     * a frame drawn a turn of the loop later leaves a strip claimed and empty. The painter
     * answers whether it drew a frame.
     */
    fun setResizePainter(paint: (() -> Boolean)?)
}

/**
 * A Compose scene hosted in a window opened through [WindowPlatform].
 *
 * Everything the host decides is here and everything the operating system does is behind
 * [platform] and the surface: it pumps the platform, runs the scene's own work on the
 * thread that draws, routes what the window heard to the scene, draws a frame when
 * something changed and presents it through [WindowPlatform.present]. Each presented frame
 * is drawn at the size the window has at that moment and recorded into [presentLog], whose
 * mismatched count must stay zero through a resize.
 *
 * One thread owns it: the one that calls [turn]. [requestFrame] is the only call another
 * thread may make.
 */
class ComposeWindowHost(
    private val platform: WindowPlatform,
    private val config: WindowConfig,
    private val surfaceFor: (WindowPlatform) -> WindowSurface?,
    private val content: @Composable () -> Unit,
) {
    /** Every presented frame, for the resize check. */
    val presentLog = FramePresentLog()

    /** True once the window manager asked for the window to close and it was allowed to. */
    var closed: Boolean = false
        private set

    private val requests = FrameRequestCoalescer()
    private val log = WindowEventLog()
    private val textInput = SceneTextInput()
    private val work = FrameDispatcher()
    private val clock = TimeSource.Monotonic
    private val started = clock.markNow()
    private val heard = ArrayList<WindowEvent>()
    private val ime = ImeSession { event -> log.heard(event) }
    private var scene: ComposeScene? = null
    private var surface: WindowSurface? = null
    private var frames: WindowFrames? = null
    private var theme by mutableStateOf(SystemTheme.Light)
    private var size = IntSize.Zero
    private var painted = false

    private val listener = object : WindowListener {
        override fun onEvent(event: WindowEvent) {
            when (event.kind) {
                WindowEvent.PREEDIT_START -> ime.preeditStart()
                WindowEvent.PREEDIT_DRAW -> ime.preeditDraw(event.keyCode, event.codePoint, event.text, event.x.toInt())
                WindowEvent.PREEDIT_DONE -> ime.preeditDone()
                WindowEvent.TEXT_COMMIT -> ime.commit(event.text)
                else -> log.heard(event)
            }
        }

        override fun onContextMenuChosen(id: Int) {
            val chord = editChord(id, usesCommandKey(platform.name)) ?: return
            for (press in chord) scene?.sendKeyEvent(press)
        }

        override fun onThemeChanged(theme: SystemTheme) {
            this@ComposeWindowHost.theme = theme
        }

        override fun onCloseRequested(): Boolean {
            closed = true
            return true
        }
    }

    /** Asks for a frame. Safe from any thread; requests made before the next turn coalesce. */
    fun requestFrame() = requests.request()

    /** Opens the window and the scene. False where the operating system refused. */
    fun open(): Boolean {
        if (!platform.open(config, listener)) return false
        val drawable = surfaceFor(platform)
        if (drawable == null) {
            platform.close()
            return false
        }
        surface = drawable
        theme = platform.systemTheme()
        val measured = platform.measure()
        size = IntSize(measured.width, measured.height)
        val created = CanvasLayersComposeScene(
            density = Density(measured.scale),
            size = size,
            coroutineContext = work,
            platformContext = ScenePlatformContext({ size }, textInput),
        )
        created.setContent {
            CompositionLocalProvider(LocalSystemTheme provides theme) { content() }
        }
        scene = created
        frames = WindowFrames({ platform.measure() }) { width, height, scale -> paint(width, height, scale) }
        // Before the first frame and the first event is read: the events that mapped the
        // window are already waiting and one of them is its first real size.
        drawable.setResizePainter { frames?.draw() == true && drewLast }
        requests.markServed()
        return true
    }

    private var drewLast = false

    /**
     * One turn of the loop: the window's own turn first, then the scene's pending work, then
     * what the window heard, then a frame if there is anything to draw.
     */
    fun turn(timeoutMillis: Long) {
        val current = scene ?: return
        // The one place the operating system is read. A frame drawn inside a resize must not
        // read it again, or the rest of the drag is taken out of the queue unhandled.
        log.read { platform.pump(timeoutMillis) }
        if (closed) return
        work.runPending()
        heard.clear()
        log.drain(heard)
        for (event in heard) {
            current.receive(event)
            when (event.kind) {
                WindowEvent.TEXT_COMMIT -> textInput.commit(event.text)
                WindowEvent.TEXT_COMPOSE -> textInput.compose(event.text)
            }
        }
        val asked = requests.take()
        if (!painted || heard.isNotEmpty() || asked || current.hasInvalidations()) {
            frames?.draw()
        }
        placeInputMethod()
    }

    /** Runs [turn] until the window closes, then releases everything. */
    fun run(turnMillis: Long = FRAME_MILLIS) {
        try {
            while (!closed) turn(turnMillis)
        } finally {
            close()
        }
    }

    /** Releases the scene, the surface and the window. Safe to call twice. */
    fun close() {
        // The painter first. A resize arriving between the two would otherwise ask a scene
        // that has gone to draw into a surface that has gone with it.
        surface?.setResizePainter(null)
        scene?.close()
        surface?.close()
        platform.close()
        scene = null
        surface = null
        frames = null
        closed = true
    }

    private fun paint(width: Int, height: Int, scale: Float) {
        drewLast = false
        val current = scene ?: return
        val target = surface?.begin(width, height) ?: return
        // Told to the scene here, in the frame about to be drawn at that size, because a
        // drawable that fits and a scene that does not is a window drawing its old size
        // into a corner of its new one.
        val fitted = IntSize(width, height)
        if (current.size != fitted) current.size = fitted
        if (current.density.density != scale) current.density = Density(scale)
        size = fitted
        try {
            current.render(target.surface.canvas.asComposeCanvas(), started.elapsedNow().inWholeNanoseconds)
            // Submitted, not only recorded: a buffer presented before the work behind it
            // runs is a buffer with nothing in it.
            target.surface.flushAndSubmit(true)
        } finally {
            target.close()
        }
        presentLog.record(platform.present(width, height))
        painted = true
        drewLast = true
    }

    private fun placeInputMethod() {
        if (!textInput.isActive) return
        val spot = candidateSpot(textInput.caret(), 1f) ?: return
        platform.setImeSpot(spot.first, spot.second)
    }

    private companion object {
        const val FRAME_MILLIS = 16L
    }
}

/**
 * Opens a window through [platform], hosts [content] in it, and runs it until it closes.
 *
 * [surfaceFor] is called once with the opened platform and answers the surface frames are
 * drawn into. Returns false without running where the window could not be opened.
 *
 * The thread that calls this is the one every later call is made from: the display server
 * delivers on it and the scene's work runs on it.
 */
fun runComposeWindow(
    platform: WindowPlatform,
    config: WindowConfig,
    surfaceFor: (WindowPlatform) -> WindowSurface?,
    content: @Composable () -> Unit,
): Boolean {
    val host = ComposeWindowHost(platform, config, surfaceFor, content)
    if (!host.open()) return false
    host.run()
    return true
}
