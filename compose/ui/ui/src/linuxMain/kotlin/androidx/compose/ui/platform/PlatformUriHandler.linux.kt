/*
 * Copyright 2024 The Android Open Source Project
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

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.system

/**
 * Opening a link, by asking the desktop to do it.
 *
 * `xdg-open` is the one command every desktop environment on this platform answers, and
 * what it does with a link is whatever the person using the machine chose. There is no
 * library call underneath it worth reaching for: the desktops disagree on everything
 * except this.
 */
private class LinuxUriHandler : UriHandler {
    @OptIn(ExperimentalForeignApi::class)
    override fun openUri(uri: String) {
        // Quoted, and single quotes inside it closed and reopened, so that a link is a
        // link rather than the rest of a command line.
        val quoted = "'" + uri.replace("'", "'\\''") + "'"
        system("xdg-open $quoted >/dev/null 2>&1 &")
    }
}

internal actual fun createPlatformUriHandler(): UriHandler = LinuxUriHandler()
