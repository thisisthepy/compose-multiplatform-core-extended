package org.thisisthepy.compose.window.graalvm.macos

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

/**
 * Where a scene hosted by `ComposeWindowHost` draws on the GraalVM macOS window: Skia over the
 * Metal drawable the window's layer hands out, one frame at a time.
 *
 * Pass it as `surfaceFor` to `runComposeWindow` or `ComposeWindowHost`. It answers null until
 * the window has opened, because the Metal device and layer are the window's.
 */
fun appKitWindowSurface(platform: WindowPlatform): WindowSurface? {
    val window = (platform as? AppKitWindowPlatform)?.nativeWindow ?: return null
    return MetalWindowSurface(window)
}

/** Skia over the Metal drawable of [window]'s layer. */
class MetalWindowSurface(private val window: NativeWindow) : WindowSurface {
    private val context = DirectContext.makeMetal(window.device, window.queue).apply {
        resourceCacheLimit = defaultSkiaCacheLimitBytes()
    }

    override fun begin(width: Int, height: Int): FrameTarget? {
        // Zero means the system had no drawable to give: frames are made faster than the
        // screen takes them, and the answer is to skip one.
        val texture = window.beginFrame()
        if (texture == 0L) return null
        val target = BackendRenderTarget.makeMetal(width, height, texture)
        val surface = Surface.makeFromBackendRenderTarget(
            context,
            target,
            SurfaceOrigin.TOP_LEFT,
            SurfaceColorFormat.BGRA_8888,
            ColorSpace.sRGB,
            SurfaceProps(PixelGeometry.RGB_H),
        )
        if (surface == null) {
            target.close()
            window.endFrame()
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
        if (paint == null) {
            AppKitUpcallSlots.frame = null
            forgetFrameCallback()
        } else {
            AppKitUpcallSlots.frame = Runnable { paint() }
            registerFrameCallback()
        }
    }

    override fun close() = context.close()
}

/**
 * Skia's default GPU resource cache limit is 256 MB. Capped at 16 MB unless `DXC_SKIA_CACHE_MB`
 * asks for another size so offscreen scratch textures do not accumulate up to 256 MB.
 */
internal fun defaultSkiaCacheLimitBytes(env: String? = System.getenv("DXC_SKIA_CACHE_MB")): Long =
    (env?.toLongOrNull()?.takeIf { it > 0L } ?: 16L) * 1048576L
