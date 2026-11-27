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

package androidx.compose.ui.test

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import platform.posix.nanosleep
import platform.posix.timespec

// A Linux window of this fork owns its loop on the thread that composes and draws, and a test
// drives that same thread: there is no other UI thread to hand work to.

/**
 * Runs the given action on the UI thread, which on Linux is the calling one.
 */
internal actual fun <T> runOnUiThread(action: () -> T): T = action()

/**
 * As on the web: `false`, so `setContent` waits for the composition to be idle.
 */
internal actual fun isOnUiThread(): Boolean = false

/**
 * Blocks the calling thread for [timeMillis] milliseconds.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun sleep(timeMillis: Long) {
    val time = cValue<timespec> {
        tv_sec = timeMillis / 1000
        tv_nsec = timeMillis.mod(1000L) * NanoSecondsPerMilliSecond
    }
    nanosleep(time, null)
}
