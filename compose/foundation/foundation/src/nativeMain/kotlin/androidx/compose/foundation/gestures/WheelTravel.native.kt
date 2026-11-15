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

package androidx.compose.foundation.gestures

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

// How far, and which way, a wheel scrolls on the Windows window with no AWT.
//
// Those windows record a scroll as its travel, in lines, with a positive y asking for what is
// below and a positive x for what is to the right: the sign of AWT's wheel rotation. A scroll
// config answers the other way round (desktop Compose negates AWT's rotation the same way):
// a negative answer moves a verticalScroll forward. The distance of a line is desktop Compose's
// for the same system, so a list scrolls as far as it does there and in the GraalVM windows,
// which go through the desktop configs. The window has already multiplied a notch by the lines
// it moves (AWT's scrollAmount), so the travel counts lines once.
//
// Subtracted from zero rather than negated, so an axis with no travel answers 0 and not -0.

/** Desktop WindowsWinUIConfig's line: a twentieth of the scrolled side. */
internal fun windowsWheelScroll(travel: Offset, bounds: IntSize): Offset =
    Offset(
        x = 0f - travel.x * (bounds.width / 20f),
        y = 0f - travel.y * (bounds.height / 20f),
    )
