@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
)

package org.thisisthepy.compose.window.macos

import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.objcPtr
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.SurfaceProps
import org.thisisthepy.compose.window.WindowPlatform
import org.thisisthepy.compose.window.scene.FrameTarget
import org.thisisthepy.compose.window.scene.WindowSurface

/**
 * Where a scene hosted by `ComposeWindowHost` draws on the Kotlin/Native macOS window: Skia over
 * the CAMetalLayer drawable the window hands out, one frame at a time.
 *
 * Pass it as `surfaceFor` to `runComposeWindow` or `ComposeWindowHost`. It answers null until
 * the window has opened.
 */
fun macosWindowSurface(platform: WindowPlatform): WindowSurface? {
    val window = (platform as? MacosWindowPlatform)?.window ?: return null
    return MetalWindowSurface(window)
}

/** Skia over the Metal drawable of [window]'s [MetalSurface] layer. */
class MetalWindowSurface(private val window: MacosWindow) : WindowSurface {
    private val metal: MetalSurface = window.metalSurface

    override fun begin(width: Int, height: Int): FrameTarget? {
        if (width <= 0 || height <= 0) return null
        val begun = kotlin.time.TimeSource.Monotonic.markNow()
        val scale = window.window.backingScaleFactor
        metal.resize(width / scale, height / scale, scale)
        val drawableWidth = MetalSurface.roundUp(width)
        val drawableHeight = MetalSurface.roundUp(height)
        val drawable = metal.nextDrawable() ?: return null
        val texture = MetalSurface.sendForObject(drawable, MetalSurface.TEXTURE) ?: return null
        val target = BackendRenderTarget.makeMetal(drawableWidth, drawableHeight, texture.rawValue)
        val surface = Surface.makeFromBackendRenderTarget(
            metal.context,
            target,
            SurfaceOrigin.TOP_LEFT,
            SurfaceColorFormat.BGRA_8888,
            ColorSpace.sRGB,
            SurfaceProps(PixelGeometry.RGB_H),
        )
        if (surface == null) {
            target.close()
            return null
        }
        MetalSurface.startFrame(surface.canvas)
        surface.canvas.save()
        surface.canvas.translate(0f, (drawableHeight - height).toFloat())

        return object : FrameTarget {
            override val surface: Surface = surface

            override fun close() {
                try {
                    metal.overlay?.invoke(surface.canvas, width, height)
                    surface.canvas.restore()
                    surface.flushAndSubmit()
                    val commands = metal.queue.commandBuffer()
                    if (commands != null) {
                        commands.commit()
                        commands.waitUntilScheduled()
                    }
                    MetalSurface.sendForNothing(drawable, MetalSurface.PRESENT)
                } finally {
                    surface.close()
                    target.close()
                }
                val elapsed = begun.elapsedNow().inWholeNanoseconds
                window.onFrameRendered?.invoke(elapsed)
                window.onFrameDrawn?.invoke(width, height)
            }
        }
    }

    override fun setResizePainter(paint: (() -> Boolean)?) {
        window.resizePainter = paint
    }

    override fun close() {
        // The MetalSurface context and layer belong to the window.
    }
}
