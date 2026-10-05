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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skiko.MainUIDispatcher

/**
 * Lets an embedder that owns its event loop say where Compose's main-thread work runs.
 *
 * By default that work goes to Skiko's [MainUIDispatcher], which is the Swing event queue, and
 * the first dispatch to it loads java.awt.Toolkit and with it the native AWT library. An
 * embedder that draws and pumps events itself has no use for either, and says so in one of
 * two ways, both before Compose starts:
 *
 *  - [override] names a dispatcher, for code that is compiled against this fork;
 *  - the system property `compose.main.dispatcher` set to `coroutines` sends the work to
 *    `Dispatchers.Main`, whose provider the embedder supplies through the ordinary
 *    kotlinx.coroutines service. That needs nothing from this fork at compile time, so an
 *    embedder can build against upstream Compose and still run on this one.
 *
 * Without either, every call goes to Skiko's dispatcher unchanged. This is not public API:
 * the package is ignored by the API dump.
 */
@InternalComposeUiApi
object ExtendedMainDispatcher {
    @Volatile
    var override: CoroutineDispatcher? = null

    /** Read once, when the first main-thread work is asked for, which is after an embedder's setup. */
    private val usesCoroutinesMain: Boolean =
        System.getProperty("compose.main.dispatcher") == "coroutines"

    /** What Compose uses for its main-thread work: [override], `Dispatchers.Main` or Skiko's. */
    val dispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
        private val current: CoroutineDispatcher
            get() = override ?: if (usesCoroutinesMain || !ExtendedAwt.available) Dispatchers.Main else MainUIDispatcher

        override fun isDispatchNeeded(context: CoroutineContext): Boolean =
            current.isDispatchNeeded(context)

        override fun dispatch(context: CoroutineContext, block: Runnable) =
            current.dispatch(context, block)
    }
}
