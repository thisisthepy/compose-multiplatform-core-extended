package org.thisisthepy.compose.window.graalvm.linux

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.SurfaceProps
import org.thisisthepy.compose.window.WindowPlatform
import org.thisisthepy.compose.window.scene.FrameTarget
import org.thisisthepy.compose.window.scene.WindowSurface

/** GL's sized internal format for 8-bit RGBA, which the window's GLX framebuffer is. */
private const val GL_RGBA8 = 0x8058

/**
 * Where a scene hosted by `ComposeWindowHost` draws on the GraalVM X11 window: Skia over the
 * window's GLX framebuffer, which the window makes current when it opens.
 *
 * Pass it as `surfaceFor` to `runComposeWindow` or `ComposeWindowHost`.
 */
fun x11WindowSurface(platform: WindowPlatform): WindowSurface? =
    (platform as? X11Window)?.let(::GlWindowSurface)

/** Skia over [window]'s GLX framebuffer. */
class GlWindowSurface(private val window: X11Window) : WindowSurface {
    private val context = DirectContext.makeGL().apply {
        resourceCacheLimit = (System.getenv("DXC_SKIA_CACHE_MB")?.toLongOrNull()?.takeIf { it > 0L } ?: 16L) * 1048576L
    }

    override fun begin(width: Int, height: Int): FrameTarget? {
        if (!window.beginFrame()) return null
        val target = BackendRenderTarget.makeGL(width, height, 0, 0, 0, GL_RGBA8)
        val surface = Surface.makeFromBackendRenderTarget(
            context,
            target,
            SurfaceOrigin.BOTTOM_LEFT,
            SurfaceColorFormat.RGBA_8888,
            ColorSpace.sRGB,
            SurfaceProps(PixelGeometry.RGB_H),
        )
        if (surface == null) {
            target.close()
            return null
        }
        return object : FrameTarget {
            override val surface: Surface = surface
            override fun close() {
                surface.close()
                target.close()
            }
        }
    }

    override fun setResizePainter(paint: (() -> Boolean)?) {
        if (paint == null) X11Upcalls.clearFramePainter() else X11Upcalls.setFramePainter(paint)
    }

    override fun close() = context.close()
}
