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

package androidx.compose.ui.input.pointer

/**
 * The shape the pointer takes, named rather than drawn.
 *
 * There is no one cursor object on this platform: X11 and Wayland each have their own, and
 * which of them is running is not something this module can see. So an icon here is the
 * name of a shape, from the set every cursor theme is required to carry, and the window
 * that has a connection to the display turns the name into a cursor.
 */
data class LinuxCursor(val name: String) : PointerIcon

internal actual val pointerIconDefault: PointerIcon = LinuxCursor("default")
internal actual val pointerIconCrosshair: PointerIcon = LinuxCursor("crosshair")
internal actual val pointerIconText: PointerIcon = LinuxCursor("text")
internal actual val pointerIconHand: PointerIcon = LinuxCursor("pointer")
