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
import java.awt.Image
import java.awt.image.BufferedImage
import org.junit.Test

class ApplicationIconTest {
    private class FakeWindow(
        val name: String,
        var icons: List<Image> = emptyList(),
        val owned: Boolean = false,
        val givenHere: Boolean = false,
    )

    private val ours: List<Image> = listOf(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB))
    private val older: List<Image> = listOf(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB))
    private val theirOwn: List<Image> = listOf(BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB))

    private fun taking(vararg windows: FakeWindow, icon: List<Image> = ours): List<String> =
        windowsTakingApplicationIcon(
            windows = windows.toList(),
            ours = icon,
            ownerless = { !it.owned },
            current = { it.icons },
            givenHere = { it.givenHere },
        ).map { it.name }

    @Test
    fun `an ownerless window with no icon takes the application's`() {
        assertThat(taking(FakeWindow("second"))).containsExactly("second")
    }

    @Test
    fun `an owned window is left to inherit its owner's`() {
        assertThat(taking(FakeWindow("popup", owned = true))).isEmpty()
    }

    @Test
    fun `a window given an icon of its own keeps it`() {
        assertThat(taking(FakeWindow("tool", icons = theirOwn))).isEmpty()
    }

    @Test
    fun `a window already wearing the application's icon is not touched again`() {
        assertThat(taking(FakeWindow("first", icons = ours))).isEmpty()
    }

    @Test
    fun `a window that wore an older application icon because of this takes the new one`() {
        assertThat(taking(FakeWindow("second", icons = older, givenHere = true))).containsExactly("second")
    }

    @Test
    fun `no application icon puts nothing anywhere`() {
        assertThat(taking(FakeWindow("second"), icon = emptyList())).isEmpty()
    }

    @Test
    fun `several windows are chosen together`() {
        assertThat(
            taking(
                FakeWindow("first", icons = ours),
                FakeWindow("second"),
                FakeWindow("dialog", owned = true),
                FakeWindow("tool", icons = theirOwn),
                FakeWindow("third", icons = older, givenHere = true),
            )
        ).containsExactly("second", "third").inOrder()
    }
}
