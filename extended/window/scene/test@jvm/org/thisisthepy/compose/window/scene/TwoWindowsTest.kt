package org.thisisthepy.compose.window.scene

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.internal.ExtendedMainDispatcher
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Runnable
import org.thisisthepy.compose.window.WindowConfig

class TwoWindowsTest {
    @OptIn(InternalComposeUiApi::class)
    @Test
    fun a_second_window_keeps_compose_main_work_running_after_the_first_closes() {
        val shared = mutableStateOf(0)
        val seenBySecond = ArrayList<Int>()
        val first = ComposeWindowHost(FakePlatform(), WindowConfig("first", 200, 100), { FakeSurface() }) {
            Box(Modifier.fillMaxSize().background(Color.Red))
        }
        val second = ComposeWindowHost(FakePlatform(), WindowConfig("second", 200, 100), { FakeSurface() }) {
            val value = shared.value
            SideEffect { seenBySecond += value }
            Box(Modifier.fillMaxSize().background(Color.Blue))
        }
        assertTrue(first.open())
        assertTrue(second.open())
        first.turn(0)
        second.turn(0)
        first.close()

        // Compose's main-thread work, asked for while only the second window is open, runs in
        // the second window's turn.
        var ran = false
        ExtendedMainDispatcher.dispatcher.dispatch(EmptyCoroutineContext, Runnable { ran = true })
        second.turn(0)
        assertTrue(ran, "main-thread work asked for after the first window closed never ran")

        // And so does what that work is for: a state change made outside any composition
        // reaches the second window's scene.
        shared.value = 1
        repeat(5) {
            second.requestFrame()
            second.turn(0)
        }
        assertEquals(1, seenBySecond.last(), "the second window saw ${seenBySecond.joinToString()}")
        second.close()
    }
}
