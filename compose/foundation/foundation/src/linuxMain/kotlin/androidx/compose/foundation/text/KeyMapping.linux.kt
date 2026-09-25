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

package androidx.compose.foundation.text

/**
 * The shortcuts a text field answers to.
 *
 * The one every platform but Apple uses: control for the word and line movements, where
 * Apple uses command for some and option for others. Shared rather than written out,
 * because there is nothing about this platform that differs from the default.
 */
internal actual val platformDefaultKeyMapping: KeyMapping = defaultKeyMapping
