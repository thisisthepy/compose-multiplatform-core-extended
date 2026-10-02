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
import platform.windows.SW_SHOWNORMAL
import platform.windows.ShellExecuteW

/**
 * Opening a link, by asking the shell to.
 *
 * ShellExecute with the "open" verb is what Explorer does with a link, and what it opens
 * it in is whatever the person using the machine chose as the default.
 */
private class WindowsUriHandler : UriHandler {
    @OptIn(ExperimentalForeignApi::class)
    override fun openUri(uri: String) {
        ShellExecuteW(null, "open", uri, null, null, SW_SHOWNORMAL)
    }
}

internal actual fun createPlatformUriHandler(): UriHandler = WindowsUriHandler()
