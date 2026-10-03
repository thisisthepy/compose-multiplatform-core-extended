/*
 * Copyright 2021 The Android Open Source Project
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import java.awt.Cursor
import java.awt.Dimension
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window

internal class UndecoratedWindowResizer(
    private val window: Window,
) {
    var enabled: Boolean by mutableStateOf(false)
    var resizerThickness: Dp by mutableStateOf(WindowDecorationDefaults.ResizerThickness)

    private var initialPointPos = Point()
    private var initialWindowPos = Point()
    private var initialWindowSize = Dimension()

    @Composable
    fun Content(modifier: Modifier) {
        if (!enabled) return

        Layout(
            {
                Side(Cursor.W_RESIZE_CURSOR, ResizerSide.Left)
                Side(Cursor.E_RESIZE_CURSOR, ResizerSide.Right)
                Side(Cursor.N_RESIZE_CURSOR, ResizerSide.Top)
                Side(Cursor.S_RESIZE_CURSOR, ResizerSide.Bottom)
                Side(Cursor.NW_RESIZE_CURSOR, ResizerSide.Left or ResizerSide.Top)
                Side(Cursor.NE_RESIZE_CURSOR, ResizerSide.Right or ResizerSide.Top)
                Side(Cursor.SW_RESIZE_CURSOR, ResizerSide.Left or ResizerSide.Bottom)
                Side(Cursor.SE_RESIZE_CURSOR, ResizerSide.Right or ResizerSide.Bottom)
            },
            modifier = modifier,
            measurePolicy = { measurables, constraints ->
                val b = resizerThickness.roundToPx()
                fun Measurable.measureSide(width: Int, height: Int) = measure(
                    Constraints.fixed(width.coerceAtLeast(0), height.coerceAtLeast(0))
                )

                val left = measurables[0].measureSide(b, constraints.maxHeight - 2 * b)
                val right = measurables[1].measureSide(b, constraints.maxHeight - 2 * b)
                val top = measurables[2].measureSide(constraints.maxWidth - 2 * b, b)
                val bottom = measurables[3].measureSide(constraints.maxWidth - 2 * b, b)
                val leftTop = measurables[4].measureSide(b, b)
                val rightTop = measurables[5].measureSide(b, b)
                val leftBottom = measurables[6].measureSide(b, b)
                val rightBottom = measurables[7].measureSide(b, b)
                layout(constraints.maxWidth, constraints.maxHeight) {
                    left.place(0, b)
                    right.place(constraints.maxWidth - b, b)
                    top.place(b, 0)
                    bottom.place(0, constraints.maxHeight - b)
                    leftTop.place(0, 0)
                    rightTop.place(constraints.maxWidth - b, 0)
                    leftBottom.place(0, constraints.maxHeight - b)
                    rightBottom.place(constraints.maxWidth - b, constraints.maxHeight - b)
                }
            }
        )
    }

    private fun Modifier.resizeOnDrag(sides: Int) = pointerInput(Unit) {
        var isResizing = false
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.first()
                val changedToPressed = !change.previousPressed && change.pressed

                val mouseLocation = mouseLocationOnScreen()
                if (event.buttons.isPrimaryPressed && changedToPressed && (mouseLocation != null)) {
                    initialPointPos = mouseLocation
                    initialWindowPos = Point(window.x, window.y)
                    initialWindowSize = Dimension(window.width, window.height)
                    isResizing = true
                }

                if (!event.buttons.isPrimaryPressed || (mouseLocation == null)) {
                    isResizing = false
                }

                if ((event.type == PointerEventType.Move) && (mouseLocation != null)) {
                    if (isResizing) {
                        resize(sides, mouseLocation)
                    }
                }
            }
        }
    }

    @Composable
    private fun Side(cursorId: Int, sides: Int) = Layout(
        {},
        Modifier.cursor(cursorId).resizeOnDrag(sides),
        measurePolicy = { _, constraints ->
            layout(constraints.maxWidth, constraints.maxHeight) {}
        }
    )

    private fun Modifier.cursor(awtCursorId: Int) =
        pointerHoverIcon(PointerIcon(Cursor(awtCursorId)))

    private fun resize(sides: Int, pointPos: Point) {
        val bounds = resizedWindowBounds(
            sides = sides,
            initial = Rectangle(initialWindowPos, initialWindowSize),
            current = window.bounds,
            diffX = pointPos.x - initialPointPos.x,
            diffY = pointPos.y - initialPointPos.y,
            minimum = window.minimumSize,
        )
        window.setLocation(bounds.x, bounds.y)
        window.setSize(bounds.width, bounds.height)
    }
}

@Suppress("ConstPropertyName")
internal object ResizerSide {
    const val Left = 0x0001
    const val Top = 0x0010
    const val Right = 0x0100
    const val Bottom = 0x1000
}

/**
 * Where a window dragged by [sides] ends up, [diffX] and [diffY] pixels from where the drag
 * began on a window that had [initial] bounds then and has [current] bounds now.
 *
 * A leading edge moves the window as well as sizing it, and the two have to agree or the
 * opposite edge creeps. The window never goes below [minimum] on either axis, whichever edge
 * is pulled: a trailing edge pulled past it stops there too, rather than leaving the size to
 * the toolkit, which on some platforms takes it below the minimum before correcting it.
 */
internal fun resizedWindowBounds(
    sides: Int,
    initial: Rectangle,
    current: Rectangle,
    diffX: Int,
    diffY: Int,
    minimum: Dimension,
): Rectangle {
    var x = current.x
    var y = current.y
    var width = current.width
    var height = current.height

    fun Int.contains(value: Int) = this and value == value

    if (sides.contains(ResizerSide.Left)) {
        width = (initial.width - diffX).coerceAtLeast(minimum.width)
        x = initial.x + initial.width - width
    } else if (sides.contains(ResizerSide.Right)) {
        width = (initial.width + diffX).coerceAtLeast(minimum.width)
    }
    if (sides.contains(ResizerSide.Top)) {
        height = (initial.height - diffY).coerceAtLeast(minimum.height)
        y = initial.y + initial.height - height
    } else if (sides.contains(ResizerSide.Bottom)) {
        height = (initial.height + diffY).coerceAtLeast(minimum.height)
    }
    return Rectangle(x, y, width, height)
}

/**
 * Returns the mouse pointer's location on the screen or `null` if none.
 *
 * Note that this can return null at any time, even if it previously returned a non-null value, and
 * even during a mouse-dispatching event.
 */
private fun mouseLocationOnScreen(): Point? = MouseInfo.getPointerInfo()?.location