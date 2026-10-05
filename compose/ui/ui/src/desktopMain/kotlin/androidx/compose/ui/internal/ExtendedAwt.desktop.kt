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

/**
 * Whether this process may use the Java toolkit at all.
 *
 * An embedder that draws its own windows has no use for java.awt, and a GraalVM native image
 * of it cannot drop the toolkit while some code on the way to the screen still names it:
 * the analysis walks every branch, so a run-time check does not remove what is behind it.
 * An embedder builds its image with `-Dcompose.awt=false` and initialises this class while
 * the image is built. [available] is then a constant, every branch guarded by it is dead,
 * and what only those branches reach is never compiled in. Anywhere else the property is
 * unset and nothing changes.
 *
 * With it false the system clipboard, the clipboard entry fallback that reads a Transferable,
 * the key names and Skiko's Swing dispatcher are not reached. This is not public API: the
 * package is ignored by the API dump.
 */
@InternalComposeUiApi
object ExtendedAwt {
    @JvmField
    val available: Boolean = System.getProperty("compose.awt") != "false"
}
