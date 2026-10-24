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

/** Whether `DXC_IDLE_SOAK_SECONDS` is set to a positive integer, disabling cursor blink during idle soak. */
internal expect fun isIdleSoakEnabled(): Boolean

/**
 * Compose's main-thread work for every host in the process, run by whichever host turns next.
 *
 * Compose has one main dispatcher, not one per window, and keeps it for the life of the process.
 * Were it one host's own, the work would stop when that host closed, and a second window's scene
 * would stop seeing state changes made outside its composition. Every host drains this one in
 * its turn instead, so the work runs while any window is open.
 */
internal val sceneMainWork = FrameDispatcher()
