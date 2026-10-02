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

package androidx.compose.animation.core

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.pthread_self

/**
 * Which thread this is, as something that can be compared with another answer.
 *
 * What asks is a transition checking that it is being remembered on the thread it will be
 * driven from, so the answer only has to be equal to itself and different from any other
 * thread's. The thread handle is that: it is the identity the C library gives a thread and
 * it does not change while the thread lives.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun getCurrentThread(): Any = pthread_self()
