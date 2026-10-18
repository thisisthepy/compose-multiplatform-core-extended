package org.thisisthepy.compose.window.scene

import kotlinx.coroutines.CoroutineDispatcher

/**
 * On Kotlin/Native for macOS, Compose's main-thread work goes to the main queue, which is the
 * thread the window pumps on, so there is nothing to change.
 */
internal actual fun useAsComposeMainDispatcher(dispatcher: CoroutineDispatcher) = Unit
