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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import java.awt.Frame
import org.junit.Test

class WindowsWindowChromeTest {

    // The window procedure answers the hit test for the band from the numbers it is handed
    // at install, so these are the only copy. They are Windows 11's caption.
    @Test
    fun `caption band has the Windows 11 metrics`() {
        assertThat(WindowsCaption.Height).isEqualTo(32.dp)
        assertThat(WindowsCaption.ButtonWidth).isEqualTo(46.dp)
        assertThat(WindowsCaption.ButtonCount).isEqualTo(3)
        assertThat(WindowsCaption.ButtonsWidth).isEqualTo(138.dp)
        assertThat(WindowsCaptionButton.entries).hasSize(WindowsCaption.ButtonCount)
    }

    @Test
    fun `buttons run minimise maximise close from the leading edge of the strip`() {
        val width = 46f
        assertThat(windowsCaptionButtonAt(0f, width)).isEqualTo(WindowsCaptionButton.Minimise)
        assertThat(windowsCaptionButtonAt(45.9f, width)).isEqualTo(WindowsCaptionButton.Minimise)
        assertThat(windowsCaptionButtonAt(46f, width)).isEqualTo(WindowsCaptionButton.Maximise)
        assertThat(windowsCaptionButtonAt(92f, width)).isEqualTo(WindowsCaptionButton.Close)
        assertThat(windowsCaptionButtonAt(137.9f, width)).isEqualTo(WindowsCaptionButton.Close)
        assertThat(windowsCaptionButtonAt(138f, width)).isNull()
        assertThat(windowsCaptionButtonAt(-1f, width)).isNull()
    }

    @Test
    fun `maximise toggles and leaves the other state bits alone`() {
        assertThat(toggledMaximised(Frame.NORMAL)).isEqualTo(Frame.MAXIMIZED_BOTH)
        assertThat(toggledMaximised(Frame.MAXIMIZED_BOTH)).isEqualTo(Frame.NORMAL)
        assertThat(toggledMaximised(Frame.MAXIMIZED_BOTH or Frame.ICONIFIED)).isEqualTo(Frame.ICONIFIED)
        assertThat(toggledMaximised(Frame.ICONIFIED)).isEqualTo(Frame.ICONIFIED or Frame.MAXIMIZED_BOTH)
    }

    @Test
    fun `nothing is taken over off Windows whatever the properties say`() {
        for (os in listOf("Mac OS X", "Linux", "")) {
            assertThat(WindowsChromeSettings.read(os) { null }).isEqualTo(WindowsChromeSettings.Off)
        }
    }

    @Test
    fun `everything is on by default on Windows`() {
        val settings = WindowsChromeSettings.read("Windows 11") { null }
        assertThat(settings.takeCaption).isTrue()
        assertThat(settings.syncResize).isTrue()
        assertThat(settings.executableIcon).isTrue()
    }

    @Test
    fun `each behaviour has a property that turns it off`() {
        fun read(vararg properties: Pair<String, String>) =
            WindowsChromeSettings.read("Windows 10") { mapOf(*properties)[it] }

        assertThat(read(WindowsChromeSettings.CaptionProperty to "system").takeCaption).isFalse()
        assertThat(read(WindowsChromeSettings.CaptionProperty to "SYSTEM").takeCaption).isFalse()
        assertThat(read(WindowsChromeSettings.CaptionProperty to "app").takeCaption).isTrue()
        assertThat(read(WindowsChromeSettings.LiveResizeProperty to "false").syncResize).isFalse()
        assertThat(read(WindowsChromeSettings.ExecutableIconProperty to "false").executableIcon).isFalse()

        val captionOnly = read(
            WindowsChromeSettings.LiveResizeProperty to "false",
            WindowsChromeSettings.ExecutableIconProperty to "false",
        )
        assertThat(captionOnly.takeCaption).isTrue()
        assertThat(captionOnly.any).isTrue()
        assertThat(
            read(
                WindowsChromeSettings.CaptionProperty to "system",
                WindowsChromeSettings.LiveResizeProperty to "false",
                WindowsChromeSettings.ExecutableIconProperty to "false",
            ).any
        ).isFalse()
    }

    @Test
    fun `content mode takes the caption and leaves the band to the content`() {
        fun read(value: String?) =
            WindowsChromeSettings.read("Windows 11") { if (it == WindowsChromeSettings.CaptionProperty) value else null }

        assertThat(read("content").takeCaption).isTrue()
        assertThat(read("content").contentUnderCaption).isTrue()
        assertThat(read("Content").contentUnderCaption).isTrue()
        assertThat(read(null).contentUnderCaption).isFalse()
        assertThat(read("system").contentUnderCaption).isFalse()
    }

    @Test
    fun `content under the caption is told its height as the caption and system bar insets`() {
        var caption = -1
        var systemBars = -1
        var contentTop = -1f
        val scene = ImageComposeScene(200, 120, Density(1f)) {
            WindowsCaptionBand(taken = { false }) {
                WindowsCaptionInsets(underCaption = { true }) {
                    caption = WindowInsets.captionBar.getTop(LocalDensity.current)
                    systemBars = WindowInsets.systemBars.getTop(LocalDensity.current)
                    Box(Modifier.fillMaxSize().onGloballyPositioned { contentTop = it.positionInRoot().y })
                }
            }
        }
        try {
            scene.render()
        } finally {
            scene.close()
        }
        assertThat(caption).isEqualTo(32)
        assertThat(systemBars).isEqualTo(32)
        assertThat(contentTop).isEqualTo(0f)
    }

    @Test
    fun `only a decorated opaque window that is not full screen takes the caption`() {
        val on = WindowsChromeSettings.read("Windows 11") { null }
        assertThat(takesCaption(on, undecorated = false, transparent = false, fullscreen = false)).isTrue()
        assertThat(takesCaption(on, undecorated = true, transparent = false, fullscreen = false)).isFalse()
        assertThat(takesCaption(on, undecorated = true, transparent = true, fullscreen = false)).isFalse()
        assertThat(takesCaption(on, undecorated = false, transparent = false, fullscreen = true)).isFalse()
        val system = on.copy(takeCaption = false)
        assertThat(takesCaption(system, undecorated = false, transparent = false, fullscreen = false)).isFalse()
    }

    @Test
    fun `content is laid out below the band and keeps the rest of the window`() {
        var contentTop = -1f
        var contentHeight = -1
        render(width = 200, height = 120, taken = true) {
            Box(
                Modifier.fillMaxSize().background(Color.White).onGloballyPositioned {
                    contentTop = it.positionInRoot().y
                    contentHeight = it.size.height
                }
            )
        }
        assertThat(contentTop).isEqualTo(32f)
        assertThat(contentHeight).isEqualTo(120 - 32)
    }

    @Test
    fun `without the band the content starts at the top`() {
        var contentTop = -1f
        render(width = 200, height = 120, taken = false) {
            Box(Modifier.fillMaxSize().onGloballyPositioned { contentTop = it.positionInRoot().y })
        }
        assertThat(contentTop).isEqualTo(0f)
    }

    @Test
    fun `band takes the colour of the content's top edge`() {
        val dark = Color(0xFF1C1B1F)
        val pixels = render(width = 200, height = 120, taken = true) {
            Box(Modifier.fillMaxSize().background(dark))
        }
        assertThat(pixels[100, 0]).isEqualTo(dark)
        assertThat(pixels[100, 16]).isEqualTo(dark)
        assertThat(pixels[100, 31]).isEqualTo(dark)
        assertThat(pixels[100, 80]).isEqualTo(dark)
    }

    @Test
    fun `band follows each part of the top edge, as a bar across the top would be`() {
        val bar = Color(0xFF6750A4)
        val page = Color(0xFFFFFBFE)
        val pixels = render(width = 200, height = 120, taken = true) {
            Column(Modifier.fillMaxSize().background(page)) {
                Box(Modifier.size(width = 50.dp, height = 20.dp).background(bar))
            }
        }
        // Left of x = 50 the content's top row is the bar, right of it the page.
        assertThat(pixels[10, 4]).isEqualTo(bar)
        assertThat(pixels[150, 4]).isEqualTo(page)
        // And the content itself is where it was put, below the band.
        assertThat(pixels[10, 40]).isEqualTo(bar)
        assertThat(pixels[10, 60]).isEqualTo(page)
    }

    @Test
    fun `band follows the content when it changes from light to dark`() {
        for (colour in listOf(Color.White, Color.Black, Color(0xFF2B2930))) {
            val pixels = render(width = 120, height = 80, taken = true) {
                Box(Modifier.fillMaxWidth().height(80.dp).background(colour))
            }
            assertThat(pixels[60, 10]).isEqualTo(colour)
        }
    }

    private fun render(
        width: Int,
        height: Int,
        taken: Boolean,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ): PixelMap {
        val scene = ImageComposeScene(width, height, Density(1f)) {
            WindowsCaptionBand(taken = { taken }, content = content)
        }
        try {
            return scene.render().toComposeImageBitmap().toPixelMap()
        } finally {
            scene.close()
        }
    }
}
