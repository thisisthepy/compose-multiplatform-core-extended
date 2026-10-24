package org.thisisthepy.compose.window.scene

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineDispatcher
import platform.posix.getenv

/**
 * On Kotlin/Native, Compose's main-thread work goes to the event queue on the thread the window
 * pumps on, so there is nothing to change.
 */
internal actual fun useAsComposeMainDispatcher(dispatcher: CoroutineDispatcher) = Unit

@OptIn(ExperimentalForeignApi::class)
internal actual fun isIdleSoakEnabled(): Boolean =
    (getenv("DXC_IDLE_SOAK_SECONDS")?.toKString()?.toIntOrNull() ?: 0) > 0
