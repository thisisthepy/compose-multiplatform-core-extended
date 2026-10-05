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

package androidx.compose.ui.internal

import androidx.compose.ui.InternalComposeUiApi
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.CoroutineDispatcher

@OptIn(InternalComposeUiApi::class)
class AwtFreeMainDispatcherTest {
    @Test
    fun anInstalledDispatcherTakesTheMainThreadWork() {
        val seen = mutableListOf<Runnable>()
        val mine = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                seen.add(block)
            }
        }
        ExtendedMainDispatcher.override = mine
        try {
            val work = Runnable { }
            ExtendedMainDispatcher.dispatcher.dispatch(EmptyCoroutineContext, work)
            assertEquals(1, seen.size)
            assertSame(work, seen[0])
            assertEquals(true, ExtendedMainDispatcher.dispatcher.isDispatchNeeded(EmptyCoroutineContext))
        } finally {
            ExtendedMainDispatcher.override = null
        }
    }
}
