package org.thisisthepy.compose.window.scene

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.internal.ExtendedMainDispatcher
import kotlinx.coroutines.CoroutineDispatcher

/**
 * On the JVM, Compose's main-thread work goes to skiko's Swing dispatcher unless an embedder
 * says otherwise, and an image built without the Java toolkit has neither Swing nor a
 * `Dispatchers.Main` provider: the first scene would stop on "Module with the Main dispatcher
 * is missing". The core fork's [ExtendedMainDispatcher] is how an embedder says otherwise.
 */
@OptIn(InternalComposeUiApi::class)
internal actual fun useAsComposeMainDispatcher(dispatcher: CoroutineDispatcher) {
    // An embedder that set one itself keeps its own.
    if (ExtendedMainDispatcher.override == null) ExtendedMainDispatcher.override = dispatcher
}

internal actual fun isIdleSoakEnabled(): Boolean =
    (System.getenv("DXC_IDLE_SOAK_SECONDS")?.toIntOrNull() ?: 0) > 0
