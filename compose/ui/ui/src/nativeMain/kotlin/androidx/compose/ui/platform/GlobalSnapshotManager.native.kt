/*
 * Copyright 2020 The Android Open Source Project
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

package androidx.compose.ui.platform

import androidx.compose.ui.platformMainDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Where a snapshot's apply notifications are sent from.
 *
 * The main dispatcher on the platforms that have one, which here is Apple's main run loop.
 * Linux has none, and asking for it does not fail where it is used but where this file is
 * initialised, so a window died before it opened with "Dispatchers.Main is missing on the
 * current platform".
 *
 * Unconfined there means the thread that wrote the state. A window on Linux owns its loop
 * and everything that composes, draws and applies state runs on it, so that thread is the
 * one an apply notification should come from anyway. What this does not survive is a second
 * thread writing composable state, and neither would a queue nobody drains, which is the
 * only other thing a platform without a loop of its own can offer.
 */
internal actual val GlobalSnapshotManagerDispatcher: CoroutineDispatcher =
    platformMainDispatcher ?: Dispatchers.Unconfined
