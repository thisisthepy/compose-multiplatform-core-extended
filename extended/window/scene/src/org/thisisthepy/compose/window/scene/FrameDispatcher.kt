@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.thisisthepy.compose.window.scene

import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable

/**
 * Where a scene's own work runs: on the thread that draws it, once a frame.
 *
 * A scene left to choose for itself hands its work to a toolkit queue, which is a thread
 * this host has nothing on. Whatever the frame loop's thread keeps (the window, the Host a
 * renderer talks to) is invisible from every other thread. Holding the work instead of
 * running it is the whole of this: the frame that asks for it runs it, on the thread it is
 * drawn from.
 */
internal class FrameDispatcher : CoroutineDispatcher() {
    private val waiting = AtomicReference<List<Runnable>>(emptyList())

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        while (true) {
            val now = waiting.load()
            if (waiting.compareAndSet(now, now + block)) return
        }
    }

    /**
     * Runs what was waiting when this was called, and no more than that. Work that asks for
     * more work waits for the next frame, so a frame never chases its own tail.
     */
    fun runPending() {
        for (block in waiting.exchange(emptyList())) block.run()
    }
}
