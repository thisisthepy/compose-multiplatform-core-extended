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

package org.jetbrains.skiko.compose

/**
 * The native half of a Compose window's chrome on Windows.
 *
 * Implemented in skiko's Windows natives (extended/skiko/windows-chrome/composeWindowChrome.cc
 * in compose-multiplatform-core-extended), which is why this class sits in skiko's package
 * inside a Compose module: a GraalVM native image links the JNI methods of skiko's packages
 * statically, from the same archive as the rest of skiko, and nothing has to be registered
 * or exported for these to be found. A JVM finds them in skiko's library once it is loaded.
 *
 * Where skiko's library was built without them (the one published to Maven Central) every
 * call throws [UnsatisfiedLinkError], and the caller keeps the window as AWT made it.
 */
internal object WindowsWindowChrome {
    /**
     * Takes over the window procedure of the top level window that holds [windowHandle].
     *
     * [takeCaption] gives the caption strip to the client area while the frame stays whole,
     * with a band [captionHeightDip] high whose trailing [buttonsWidthDip] take ordinary
     * clicks and whose rest drags the window. [syncResize] holds each step of a live resize
     * until a frame at the new size has been presented. Called again for the same window, it
     * changes those settings.
     *
     * False when the window could not be taken over; it is then left as it was.
     */
    @JvmStatic
    external fun install(
        windowHandle: Long,
        captionHeightDip: Int,
        buttonsWidthDip: Int,
        takeCaption: Boolean,
        syncResize: Boolean,
    ): Boolean

    /** Asks the window to compute its frame again, after AWT has shown it. */
    @JvmStatic
    external fun refreshFrame(windowHandle: Long)

    /**
     * What the process declared about scaling before any window existed: 2 for per-monitor
     * (v2), 1 for per-monitor (8.1), 0 for nothing, which is the answer inside a JVM, where
     * java.exe's manifest has already declared it.
     */
    @JvmStatic
    external fun dpiAwareness(): Int

    /**
     * Puts the executable's own icon on the window, if the executable has one. False when it
     * has none, and the window keeps the toolkit's.
     */
    @JvmStatic
    external fun useExecutableIcon(windowHandle: Long): Boolean
}
