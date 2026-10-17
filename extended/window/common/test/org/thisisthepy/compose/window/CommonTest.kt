package org.thisisthepy.compose.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommonTest {
    @Test
    fun resizeSyncPaysAfterPresenting() {
        val order = ArrayList<String>()
        val sync = ResizeSync(present = { order.add("present") }, tell = { order.add("tell $it") })
        sync.requested(7)
        sync.frameDrawn()
        assertEquals(listOf("present", "tell 7"), order)
        assertFalse(sync.isOwed)
    }

    @Test
    fun resizeSyncNoFramePays() {
        val told = ArrayList<Long>()
        val sync = ResizeSync(present = {}, tell = { told.add(it) })
        sync.requested(1)
        sync.requested(2)
        sync.noFrame()
        sync.noFrame()
        assertEquals(listOf(2L), told)
    }

    @Test
    fun windowFramesDrawsAtTheCurrentSizeAndRefusesReentry() {
        var measurement = WindowMeasurement(100, 50, 2f)
        val drawn = ArrayList<Triple<Int, Int, Float>>()
        lateinit var frames: WindowFrames
        var inner: Boolean? = null
        frames = WindowFrames({ measurement }, { w, h, s ->
            drawn.add(Triple(w, h, s))
            inner = frames.draw()
        })
        assertTrue(frames.draw())
        assertEquals(false, inner)
        measurement = WindowMeasurement(0, 50, 2f)
        assertFalse(frames.draw())
        assertEquals(listOf(Triple(100, 50, 2f)), drawn)
    }

    @Test
    fun windowEventLogGuardsReentryAndDrainsInOrder() {
        val log = WindowEventLog()
        var nested: Boolean? = null
        assertTrue(log.read { nested = log.read { } ; log.heard(WindowEvent(WindowEvent.RESIZE, 0f, 0f, 0, 0, 0, 0, "")) })
        assertEquals(false, nested)
        val out = ArrayList<WindowEvent>()
        log.drain(out)
        assertEquals(1, out.size)
        log.drain(out)
        assertEquals(1, out.size)
    }

    @Test
    fun framePresentLogCountsMismatchedFrames() {
        val log = FramePresentLog()
        log.record(FramePresentRecord(10, 10, 10, 10))
        log.record(FramePresentRecord(10, 10, 12, 10))
        assertEquals(2, log.frames)
        assertEquals(1, log.mismatched)
    }

    @Test
    fun policyClampsToTheMinimum() {
        val policy = object : WindowPolicy {
            override fun minimumSize() = WindowSize(200, 100)
        }
        assertEquals(WindowSize(200, 150), policy.clamp(WindowSize(50, 150)))
    }
}
