/*
 * Copyright 2023 The Android Open Source Project
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

package androidx.compose.ui

import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual fun classKeyForObject(a: Any): Any {
    return a::class
}

// TODO: For non-JVM platforms, you can revive the kotlin-reflect implementation from
//  https://android-review.googlesource.com/c/platform/frameworks/support/+/2441379
@OptIn(ExperimentalComposeUiApi::class)
internal actual fun InspectorInfo.tryPopulateReflectively(
    element: ModifierNodeElement<*>
) {
}

/**
 * The main dispatcher, or nothing where the platform has not got one.
 *
 * Asked once and kept. Every target that reaches this file has one except Linux, which has
 * no loop for coroutines to attach to.
 *
 * Asked for by `immediate` rather than by name, because asking by name succeeds everywhere:
 * where there is none the answer is a stub that throws the first time something dispatches
 * to it, which is inside a coroutine start and a long way from here. `immediate` is the
 * property that stub refuses, so it is the question that gets an answer now rather than a
 * crash later. Reading only the name is what the first version of this did, and the window
 * still died, in `GlobalSnapshotManager.ensureStarted`.
 */
private val mainDispatcherIfThereIsOne: CoroutineDispatcher? = try {
    Dispatchers.Main.immediate
} catch (missing: Throwable) {
    null
}

/**
 * Where work that was asked to happen a moment from now is run.
 *
 * Unconfined where there is no main dispatcher, which means the thread that asked. A
 * platform without one owns its own loop and everything that composes and draws is already
 * on it, so that thread is where this work belongs anyway.
 */
internal actual val PostDelayedDispatcher: CoroutineContext
    get() = mainDispatcherIfThereIsOne ?: Dispatchers.Unconfined

/** The same question, for whatever else in this build needs to know. */
internal val platformMainDispatcher: CoroutineDispatcher? get() = mainDispatcherIfThereIsOne
