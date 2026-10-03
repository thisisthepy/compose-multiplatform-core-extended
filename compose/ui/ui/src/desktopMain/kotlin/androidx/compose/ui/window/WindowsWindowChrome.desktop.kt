/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalPlatformWindowInsets
import androidx.compose.ui.platform.PlatformInsets
import androidx.compose.ui.platform.PlatformWindowInsets
import androidx.compose.ui.platform.union
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Frame
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import org.jetbrains.skiko.compose.WindowsWindowChrome as Native

/**
 * The caption band a Compose window draws on Windows, in device independent pixels.
 *
 * The window procedure answers the hit test for the same band (which part drags the window
 * and which part is buttons), so it is handed these numbers when the window is taken over
 * rather than keeping its own. Windows 11's own caption: 32 high, buttons 46 wide.
 */
internal object WindowsCaption {
    val Height: Dp = 32.dp
    val ButtonWidth: Dp = 46.dp
    const val ButtonCount: Int = 3
    val ButtonsWidth: Dp get() = ButtonWidth * ButtonCount

    /** The side of the square the three glyphs are drawn in. */
    val GlyphSize: Dp = 10.dp

    /** The close button's hover fill, as Windows 11 draws it. */
    val CloseHover: Color = Color(0xFFC42B1C)
}

/** What each of the three caption buttons is, in the order they sit from the leading edge. */
internal enum class WindowsCaptionButton(val label: String) {
    Minimise("Minimize"),
    Maximise("Maximize"),
    Close("Close");
}

/** The button under [x] pixels from the start of the button strip, or null outside it. */
internal fun windowsCaptionButtonAt(x: Float, buttonWidth: Float): WindowsCaptionButton? {
    if (x < 0f || buttonWidth <= 0f) return null
    return WindowsCaptionButton.entries.getOrNull((x / buttonWidth).toInt())
}

/** The extended state that the maximise button moves a window at [state] to. */
internal fun toggledMaximised(state: Int): Int =
    if (state and Frame.MAXIMIZED_BOTH != 0) {
        state and Frame.MAXIMIZED_BOTH.inv()
    } else {
        state or Frame.MAXIMIZED_BOTH
    }

/**
 * Which of the window behaviours on Windows this process wants, read from system properties.
 *
 * All on unless switched off, because they are what a window on Windows is expected to do and
 * an application should not have to ask for them. Each has a property that turns it off, for
 * the application that wants the system's own caption back or that sees a problem with the
 * other two:
 *
 * - `compose.windows.caption=system` keeps the system caption.
 * - `compose.windows.caption=content` takes the caption strip but draws no band and no
 *   buttons there: the content runs to the top of the window, is told the strip's height as
 *   the caption bar and system bar insets, and draws whatever it wants under the caption,
 *   its own window buttons included. For an application, or a framework on top of Compose,
 *   that lays out its own title bar.
 * - `compose.windows.liveResize=false` stops holding a live resize for the frame.
 * - `compose.windows.executableIcon=false` keeps the toolkit's icon on a window with none.
 *
 * Nothing is taken over off Windows, whatever the properties say.
 */
internal data class WindowsChromeSettings(
    val takeCaption: Boolean,
    val syncResize: Boolean,
    val executableIcon: Boolean,
    val contentUnderCaption: Boolean = false,
) {
    val any: Boolean get() = takeCaption || syncResize || executableIcon

    companion object {
        const val CaptionProperty = "compose.windows.caption"
        const val LiveResizeProperty = "compose.windows.liveResize"
        const val ExecutableIconProperty = "compose.windows.executableIcon"
        const val DebugProperty = "compose.windows.chrome.debug"

        val Off = WindowsChromeSettings(takeCaption = false, syncResize = false, executableIcon = false)

        fun read(
            osName: String = System.getProperty("os.name").orEmpty(),
            property: (String) -> String? = System::getProperty,
        ): WindowsChromeSettings {
            if (!osName.startsWith("Windows")) return Off
            val caption = property(CaptionProperty)
            return WindowsChromeSettings(
                takeCaption = !caption.equals("system", ignoreCase = true),
                contentUnderCaption = caption.equals("content", ignoreCase = true),
                syncResize = !property(LiveResizeProperty).equals("false", ignoreCase = true),
                executableIcon = !property(ExecutableIconProperty).equals("false", ignoreCase = true),
            )
        }
    }
}

/**
 * Whether a window takes the caption band. Only a decorated, opaque window that is not
 * full screen has a caption to take: an undecorated one has none, a transparent one is
 * undecorated by AWT's rules, and a full screen one shows no caption at all.
 */
internal fun takesCaption(
    settings: WindowsChromeSettings,
    undecorated: Boolean,
    transparent: Boolean,
    fullscreen: Boolean,
): Boolean = settings.takeCaption && !undecorated && !transparent && !fullscreen

/**
 * A Compose window's behaviour on Windows that AWT does not give it.
 *
 * - **The caption.** The frame is kept whole, because on Windows the frame is also the drop
 *   shadow, the resize border and Snap Layouts, none of which a window can draw for itself.
 *   Only the caption strip is taken into the client area, and Compose draws a band there
 *   in the colour the content has along its top edge, with the three buttons at the
 *   trailing end. The content is laid out below the band, so nothing an application draws
 *   is covered and its layout keeps the height it had under the system caption.
 * - **The live resize.** Each step of a drag of the window's edge is held, for a bounded
 *   time, until a frame at the new size is on screen, so the edge and what is inside it
 *   move together instead of the inside trailing by a frame or two.
 * - **The icon.** A window with no icon of its own wears the executable's icon instead of the
 *   toolkit's coffee cup, when the executable has one. Only in a native image: a JVM's
 *   executable is java.exe, and its icon is not the application's.
 *
 * The native half is in skiko's Windows natives. Where skiko's library was built without it,
 * the window keeps what AWT gave it and none of this shows.
 */
internal class WindowsWindowChrome(private val window: ComposeWindow) {
    private val settings = WindowsChromeSettings.read()

    /** True once the window procedure has given the caption strip to the client area. */
    var captionTaken: Boolean by mutableStateOf(false)
        private set

    /** The band and its buttons are drawn here. */
    val bandDrawn: Boolean get() = captionTaken && !settings.contentUnderCaption

    /** The content runs under the caption and draws its own; it is told the strip's height. */
    val contentUnderCaption: Boolean get() = captionTaken && settings.contentUnderCaption

    var maximised: Boolean by mutableStateOf(false)
        private set

    var active: Boolean by mutableStateOf(true)
        private set

    private var installed = false
    private var listening = false

    /** Called once the window has a native peer, and again when anything the band depends on changes. */
    fun update(fullscreen: Boolean = false) {
        if (!settings.any || !window.isDisplayable) return
        val takeCaption = takesCaption(settings, window.isUndecorated, window.isTransparent, fullscreen)
        val handle = try {
            window.windowHandle
        } catch (e: Throwable) {
            debug("no window handle yet: $e")
            return
        }
        if (handle == 0L) return
        val firstTime = !installed
        val ok = try {
            Native.install(
                handle,
                WindowsCaption.Height.value.toInt(),
                WindowsCaption.ButtonsWidth.value.toInt(),
                takeCaption,
                settings.syncResize,
            )
        } catch (e: LinkageError) {
            // skiko's library was built without the window procedure.
            debug("skiko has no Compose window procedure: $e")
            false
        }
        installed = ok
        captionTaken = ok && takeCaption
        debug(
            "installed=$ok caption=$captionTaken liveResize=${ok && settings.syncResize} " +
                "dpiAwareness=${if (ok) Native.dpiAwareness() else -1}"
        )
        if (ok && firstTime) {
            listen()
            if (settings.executableIcon && window.iconImages.isNullOrEmpty() && isNativeImage()) {
                debug("executable icon=${Native.useExecutableIcon(handle)}")
            }
        }
    }

    private fun listen() {
        if (listening) return
        listening = true
        maximised = window.extendedState and Frame.MAXIMIZED_BOTH != 0
        active = window.isActive
        window.addWindowStateListener { event ->
            maximised = event.newState and Frame.MAXIMIZED_BOTH != 0
        }
        window.addWindowListener(object : WindowAdapter() {
            // AWT works out its insets again when the window is shown; asking for the frame
            // once more then makes sure they are worked out from the frame without a caption.
            override fun windowOpened(e: WindowEvent) {
                if (captionTaken) {
                    try {
                        Native.refreshFrame(window.windowHandle)
                    } catch (_: Throwable) {
                    }
                }
            }

            override fun windowActivated(e: WindowEvent) {
                active = true
            }

            override fun windowDeactivated(e: WindowEvent) {
                active = false
            }
        })
    }

    fun press(button: WindowsCaptionButton) {
        when (button) {
            WindowsCaptionButton.Minimise ->
                window.extendedState = window.extendedState or Frame.ICONIFIED
            WindowsCaptionButton.Maximise ->
                window.extendedState = toggledMaximised(window.extendedState)
            WindowsCaptionButton.Close ->
                window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
        }
    }

    private fun debug(message: String) {
        if (System.getProperty(WindowsChromeSettings.DebugProperty).toBoolean()) {
            System.err.println("compose windows chrome: $message")
        }
    }

    private fun isNativeImage(): Boolean =
        System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime"
}

/**
 * Lays [content] out below the caption band and paints the band.
 *
 * The band takes the colour of the content's top edge: the content's first row of pixels is
 * drawn again, stretched up over the band. That is the colour the window reads as having,
 * whether the application painted a surface, a bar or a theme background, and it follows
 * the application as it changes, light to dark and back, with no colour to choose here.
 * The content is recorded once and drawn twice, so the second drawing replays what was
 * recorded rather than running the content's drawing again.
 *
 * The same layout whether the band is there or not, so a window that gains or loses it keeps
 * its content's state. [taken] is read while measuring and drawing, so a change to the state
 * behind it moves the content and repaints the band.
 */
@Composable
internal fun WindowsCaptionBand(taken: () -> Boolean, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    Layout(
        content = content,
        modifier = Modifier.drawWithContent {
            val band = if (taken()) WindowsCaption.Height.roundToPx().toFloat() else 0f
            if (band <= 0f) {
                drawContent()
                return@drawWithContent
            }
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
            clipRect(0f, 0f, size.width, band) {
                // Row `band` of the content maps to the top of the window and row `band + 1`
                // to the bottom of the band.
                withTransform({
                    scale(scaleX = 1f, scaleY = band, pivot = Offset.Zero)
                    translate(top = -band)
                }) {
                    drawLayer(layer)
                }
            }
        },
    ) { measurables, constraints ->
        val band = if (taken()) WindowsCaption.Height.roundToPx() else 0
        val inner = Constraints(
            minWidth = constraints.minWidth,
            maxWidth = constraints.maxWidth,
            minHeight = (constraints.minHeight - band).coerceAtLeast(0),
            maxHeight = if (constraints.hasBoundedHeight) {
                (constraints.maxHeight - band).coerceAtLeast(0)
            } else {
                Constraints.Infinity
            },
        )
        val placeables = measurables.map { it.measure(inner) }
        val width = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
        val height = (placeables.maxOfOrNull { it.height } ?: 0) + band
        layout(constraints.constrainWidth(width), constraints.constrainHeight(height)) {
            placeables.forEach { it.place(0, band) }
        }
    }
}

/**
 * The minimise, maximise and close buttons at the trailing end of the band.
 *
 * Always at the right edge whatever the layout direction, because that is where the window
 * procedure answers the hit test for them. Their glyphs are drawn in the difference of white
 * with what is beneath, which is dark on a light band and light on a dark one, the band
 * being whatever colour the application drew.
 */
@Composable
internal fun WindowsCaptionButtons(chrome: WindowsWindowChrome?, modifier: Modifier = Modifier) {
    if (chrome == null || !chrome.bandDrawn) return
    Layout(
        modifier = modifier,
        content = {
            for (button in WindowsCaptionButton.entries) {
                WindowsCaptionButtonView(chrome, button)
            }
        },
    ) { measurables, constraints ->
        val width = WindowsCaption.ButtonWidth.roundToPx()
        val height = WindowsCaption.Height.roundToPx()
        val placeables = measurables.map { it.measure(Constraints.fixed(width, height)) }
        val total = constraints.maxWidth.takeIf { it != Constraints.Infinity } ?: (width * placeables.size)
        layout(total, height) {
            var x = total - width * placeables.size
            for (placeable in placeables) {
                placeable.place(x, 0)
                x += width
            }
        }
    }
}

@Composable
private fun WindowsCaptionButtonView(chrome: WindowsWindowChrome, button: WindowsCaptionButton) {
    var hovered by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val label = if (button == WindowsCaptionButton.Maximise && chrome.maximised) "Restore" else button.label
    Layout(
        modifier = Modifier
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick { chrome.press(button); true }
            }
            .pointerInput(button) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter -> hovered = true
                            PointerEventType.Exit -> {
                                hovered = false
                                pressed = false
                            }
                            PointerEventType.Press -> {
                                pressed = true
                                event.changes.forEach { it.consume() }
                            }
                            PointerEventType.Release -> {
                                val inside = event.changes.all { change ->
                                    change.position.x in 0f..size.width.toFloat() &&
                                        change.position.y in 0f..size.height.toFloat()
                                }
                                val wasPressed = pressed
                                pressed = false
                                event.changes.forEach { it.consume() }
                                if (wasPressed && inside) chrome.press(button)
                            }
                        }
                    }
                }
            }
            .drawWithContent {
                drawCaptionButton(button, hovered, pressed, chrome.active, chrome.maximised)
            },
    ) { _, constraints ->
        layout(constraints.minWidth, constraints.minHeight) {}
    }
}

private fun DrawScope.drawCaptionButton(
    button: WindowsCaptionButton,
    hovered: Boolean,
    pressed: Boolean,
    active: Boolean,
    maximised: Boolean,
) {
    val closeLit = button == WindowsCaptionButton.Close && (hovered || pressed)
    if (closeLit) {
        drawRect(if (pressed) WindowsCaption.CloseHover.copy(alpha = 0.9f) else WindowsCaption.CloseHover)
    } else if (hovered || pressed) {
        drawRect(Color.White.copy(alpha = if (pressed) 0.2f else 0.1f), blendMode = BlendMode.Difference)
    }
    val ink = if (closeLit) Color.White else Color.White.copy(alpha = if (active) 1f else 0.45f)
    val blend = if (closeLit) BlendMode.SrcOver else BlendMode.Difference
    val stroke = 1.dp.toPx()
    val side = WindowsCaption.GlyphSize.toPx()
    val left = ((size.width - side) / 2f).toInt().toFloat() + stroke / 2f
    val top = ((size.height - side) / 2f).toInt().toFloat() + stroke / 2f
    val right = left + side - stroke
    val bottom = top + side - stroke
    when (button) {
        WindowsCaptionButton.Minimise -> {
            val y = (top + bottom) / 2f
            drawLine(ink, Offset(left, y), Offset(right, y), stroke, blendMode = blend)
        }
        WindowsCaptionButton.Maximise -> if (maximised) {
            // Restore: a square with a second one showing behind its top right corner.
            val inset = 2.dp.toPx()
            drawRect(
                ink,
                topLeft = Offset(left, top + inset),
                size = androidx.compose.ui.geometry.Size(right - left - inset, bottom - top - inset),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                blendMode = blend,
            )
            drawLine(ink, Offset(left + inset, top), Offset(right, top), stroke, blendMode = blend)
            drawLine(ink, Offset(right, top), Offset(right, bottom - inset), stroke, blendMode = blend)
        } else {
            drawRect(
                ink,
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                blendMode = blend,
            )
        }
        WindowsCaptionButton.Close -> {
            drawLine(ink, Offset(left, top), Offset(right, bottom), stroke, blendMode = blend)
            drawLine(ink, Offset(left, bottom), Offset(right, top), stroke, blendMode = blend)
        }
    }
}

/**
 * Tells [content] how high the caption strip it runs under is, as the caption bar and system
 * bar insets, while [underCaption] says it does. Everything else the platform reports is
 * passed through.
 */
@OptIn(InternalComposeUiApi::class)
@Composable
internal fun WindowsCaptionInsets(underCaption: () -> Boolean, content: @Composable () -> Unit) {
    val platform = LocalPlatformWindowInsets.current
    val density = LocalDensity.current
    val insets = remember(platform, density) {
        val caption = PlatformInsets(
            getTop = { if (underCaption()) with(density) { WindowsCaption.Height.roundToPx() } else 0 }
        )
        object : PlatformWindowInsets by platform {
            override val captionBar: PlatformInsets get() = caption
            override val systemBars: PlatformInsets get() = platform.systemBars.union(caption)

            // The strip is a safe inset like the system bars, so excluding those excludes it.
            override fun excluding(safeInsets: Boolean, ime: Boolean): PlatformWindowInsets =
                if (safeInsets) platform.excluding(safeInsets, ime) else this
        }
    }
    CompositionLocalProvider(LocalPlatformWindowInsets provides insets, content = content)
}
