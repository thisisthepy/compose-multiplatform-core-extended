package org.thisisthepy.compose.window.scene

import kotlinx.coroutines.CoroutineDispatcher

/**
 * On Kotlin/Native, Compose's main-thread work goes to the event queue on the thread the window
 * pumps on, so there is nothing to change.
 */
internal actual fun useAsComposeMainDispatcher(dispatcher: CoroutineDispatcher) = Unit
