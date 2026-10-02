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
 * The shape the pointer takes, as the system cursor it is.
 *
 * Windows has one set of system cursors, named by number. An icon here carries that
 * number, and the window turns it into a cursor with LoadCursor, because the window is
 * what owns the cursor while the pointer is over it.
 */
data class WindowsCursor(val id: Int) : PointerIcon

// IDC_ARROW, IDC_CROSS, IDC_IBEAM and IDC_HAND.
internal actual val pointerIconDefault: PointerIcon = WindowsCursor(32512)
internal actual val pointerIconCrosshair: PointerIcon = WindowsCursor(32515)
internal actual val pointerIconText: PointerIcon = WindowsCursor(32513)
internal actual val pointerIconHand: PointerIcon = WindowsCursor(32649)
