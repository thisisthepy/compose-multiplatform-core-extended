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

import com.google.common.truth.Truth.assertThat
import java.awt.Dimension
import java.awt.Rectangle
import org.junit.Test

class UndecoratedWindowResizerBoundsTest {
    private val start = Rectangle(100, 200, 800, 600)
    private val minimum = Dimension(300, 200)

    private fun drag(sides: Int, dx: Int, dy: Int) =
        resizedWindowBounds(sides, initial = start, current = start, diffX = dx, diffY = dy, minimum = minimum)

    @Test
    fun `a trailing edge sizes the window and leaves it where it is`() {
        assertThat(drag(ResizerSide.Right, 50, 0)).isEqualTo(Rectangle(100, 200, 850, 600))
        assertThat(drag(ResizerSide.Bottom, 0, -40)).isEqualTo(Rectangle(100, 200, 800, 560))
    }

    @Test
    fun `a leading edge moves the window so the opposite edge stays put`() {
        val left = drag(ResizerSide.Left, 50, 0)
        assertThat(left).isEqualTo(Rectangle(150, 200, 750, 600))
        assertThat(left.x + left.width).isEqualTo(start.x + start.width)
        val top = drag(ResizerSide.Top, 0, -30)
        assertThat(top).isEqualTo(Rectangle(100, 170, 800, 630))
        assertThat(top.y + top.height).isEqualTo(start.y + start.height)
    }

    @Test
    fun `a leading edge pulled past the minimum stops, and so does the window`() {
        val left = drag(ResizerSide.Left, 700, 0)
        assertThat(left.width).isEqualTo(300)
        assertThat(left.x + left.width).isEqualTo(start.x + start.width)
        val top = drag(ResizerSide.Top, 0, 500)
        assertThat(top.height).isEqualTo(200)
        assertThat(top.y + top.height).isEqualTo(start.y + start.height)
    }

    @Test
    fun `a trailing edge pulled past the minimum stops there too`() {
        assertThat(drag(ResizerSide.Right, -700, 0).width).isEqualTo(300)
        assertThat(drag(ResizerSide.Bottom, 0, -500).height).isEqualTo(200)
    }

    @Test
    fun `a corner moves both of its edges`() {
        assertThat(drag(ResizerSide.Left or ResizerSide.Top, -10, -20))
            .isEqualTo(Rectangle(90, 180, 810, 620))
        assertThat(drag(ResizerSide.Right or ResizerSide.Bottom, 10, 20))
            .isEqualTo(Rectangle(100, 200, 810, 620))
    }

    @Test
    fun `an edge leaves the other axis as the window has it now`() {
        val moved = Rectangle(140, 260, 800, 600)
        val bounds = resizedWindowBounds(
            ResizerSide.Right, initial = start, current = moved, diffX = 10, diffY = 0, minimum = minimum,
        )
        assertThat(bounds).isEqualTo(Rectangle(140, 260, 810, 600))
    }
}
