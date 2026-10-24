package org.thisisthepy.compose.window.scene

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.thisisthepy.compose.window.CaptionAction
import org.thisisthepy.compose.window.SystemTheme
import org.thisisthepy.compose.window.WindowCaption
import org.thisisthepy.compose.window.WindowEdge
import org.thisisthepy.compose.window.WindowPlatform

/**
 * The window's caption as the platform last measured it, in points.
 *
 * Content that sits on the caption's line, such as a top bar, reads it to step clear of the
 * window buttons ([WindowCaption.buttonsWidth] at the end [WindowCaption.buttonsAtStart] names)
 * and to be as tall as the strip. A window with no caption has [WindowCaption.None].
 */
val LocalWindowCaption = staticCompositionLocalOf { WindowCaption.None }

/**
 * Steps content clear of the caption: below the strip, so nothing the content draws is hidden
 * under it or under the window buttons. The host applies it to the content unless told the
 * content lays itself out against [LocalWindowCaption].
 */
fun Modifier.windowCaptionPadding(): Modifier = composed {
    val caption = LocalWindowCaption.current
    padding(top = (caption.insetTop + caption.height).dp)
}

/**
 * What the host puts around the content for the caption: a press in an empty part of the strip
 * moves the window, a press at an edge resizes it, and where the platform draws no buttons
 * ([WindowCaption.buttonsDrawnByScene]) the minimise, maximise and close buttons are drawn here,
 * with their roles and labels, so a reader finds them.
 */
@Composable
internal fun WindowFrame(
    platform: WindowPlatform,
    caption: WindowCaption,
    theme: SystemTheme,
    resizable: Boolean,
    padContent: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (caption.height > 0f) {
            // Behind the content: the content's own controls take their presses first, and
            // only a press nothing else wanted moves the window.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height((caption.insetTop + caption.height).dp)
                    .background(stripColor(theme))
                    .pressed { platform.startMove() },
            )
        }
        Box(if (padContent) Modifier.fillMaxSize().windowCaptionPadding() else Modifier.fillMaxSize(), content = content)
        if (caption.buttonsDrawnByScene) {
            CaptionButtons(platform, caption, theme, Modifier.align(if (caption.buttonsAtStart) Alignment.TopStart else Alignment.TopEnd))
            if (resizable) ResizeEdges(platform)
        }
    }
}

private fun stripColor(theme: SystemTheme) = if (theme == SystemTheme.Dark) Color(0xFF202020) else Color(0xFFF3F3F3)

private fun glyphColor(theme: SystemTheme) = if (theme == SystemTheme.Dark) Color(0xFFEDEDED) else Color(0xFF1A1A1A)

/** Runs [onPress] on every press that reaches this element. */
private fun Modifier.pressed(onPress: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = true)
        onPress()
    }
}

@Composable
private fun CaptionButtons(platform: WindowPlatform, caption: WindowCaption, theme: SystemTheme, modifier: Modifier) {
    val each = if (caption.buttonsWidth > 0f) (caption.buttonsWidth / 3f).dp else 46.dp
    val color = glyphColor(theme)
    Row(modifier.padding(top = caption.insetTop.dp).height(caption.height.dp)) {
        CaptionButton("Minimize", each, { platform.performCaptionAction(CaptionAction.Minimize) }) { size ->
            drawLine(color, Offset(size * 0.35f, size * 0.5f), Offset(size * 0.65f, size * 0.5f), strokeWidth = 1.dp.toPx())
        }
        CaptionButton("Maximize", each, { platform.performCaptionAction(CaptionAction.ToggleMaximize) }) { size ->
            drawRect(color, Offset(size * 0.36f, size * 0.36f), androidx.compose.ui.geometry.Size(size * 0.28f, size * 0.28f), style = Stroke(1.dp.toPx()))
        }
        CaptionButton("Close", each, { platform.performCaptionAction(CaptionAction.Close) }) { size ->
            drawLine(color, Offset(size * 0.36f, size * 0.36f), Offset(size * 0.64f, size * 0.64f), strokeWidth = 1.dp.toPx())
            drawLine(color, Offset(size * 0.64f, size * 0.36f), Offset(size * 0.36f, size * 0.64f), strokeWidth = 1.dp.toPx())
        }
    }
}

@Composable
private fun CaptionButton(
    label: String,
    width: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    glyph: androidx.compose.ui.graphics.drawscope.DrawScope.(Float) -> Unit,
) {
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(minOf(width, 32.dp))) { glyph(size.minDimension) }
    }
}

/** How far in from an edge a press resizes rather than reaching the content. */
private val EDGE = 5.dp

@Composable
private fun BoxScope.ResizeEdges(platform: WindowPlatform) {
    fun Modifier.edge(edge: WindowEdge) = pressed { platform.startResize(edge) }
    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(EDGE).edge(WindowEdge.Top))
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(EDGE).edge(WindowEdge.Bottom))
    Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(EDGE).edge(WindowEdge.Left))
    Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(EDGE).edge(WindowEdge.Right))
    Box(Modifier.align(Alignment.TopStart).size(EDGE * 2).edge(WindowEdge.TopLeft))
    Box(Modifier.align(Alignment.TopEnd).size(EDGE * 2).edge(WindowEdge.TopRight))
    Box(Modifier.align(Alignment.BottomStart).size(EDGE * 2).edge(WindowEdge.BottomLeft))
    Box(Modifier.align(Alignment.BottomEnd).size(EDGE * 2).edge(WindowEdge.BottomRight))
}
