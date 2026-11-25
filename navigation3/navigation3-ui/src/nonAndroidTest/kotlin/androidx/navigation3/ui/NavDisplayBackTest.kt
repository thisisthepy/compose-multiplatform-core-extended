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

package androidx.navigation3.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation3.runtime.NavEntry
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// A back event reaches NavDisplay through the dispatcher the window provides. On Kotlin/Native
// this also checks that navigationevent-compose links against this fork's Compose UI: JetBrains'
// prebuilt one names org.jetbrains.compose.ui:ui, and reading its local threw IrLinkageError.
@OptIn(ExperimentalTestApi::class)
class NavDisplayBackTest {
    @Test
    fun backEventPopsTheTopEntry() = runComposeUiTest {
        val backStack = mutableStateListOf("first", "second")
        val shown = mutableListOf<String>()
        val input = DirectNavigationEventInput()

        setContent {
            val owner = LocalNavigationEventDispatcherOwner.current
            assertNotNull(owner, "the window provides a navigation event dispatcher")
            LaunchedEffect(owner) { owner.navigationEventDispatcher.addInput(input) }
            NavDisplay(backStack = backStack) { key ->
                NavEntry(key) { LaunchedEffect(key) { shown += key } }
            }
        }
        waitForIdle()
        assertEquals(listOf("second"), shown)

        input.backCompleted()
        waitForIdle()
        assertEquals(listOf("first"), backStack.toList())
        assertEquals(listOf("second", "first"), shown)
    }
}
