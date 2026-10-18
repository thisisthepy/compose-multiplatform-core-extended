package org.thisisthepy.compose.window.scene

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Makes [dispatcher] the place Compose runs its main-thread work, before the first scene exists.
 *
 * Compose asks once, when its first scene starts the global snapshot observer, and keeps the
 * answer. A window that pumps its own events runs that work on the thread that draws, between
 * frames, so the host's dispatcher has to be the answer.
 */
internal expect fun useAsComposeMainDispatcher(dispatcher: CoroutineDispatcher)
