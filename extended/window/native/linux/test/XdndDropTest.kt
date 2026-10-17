@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import platform.posix.getenv
import x11.*

/**
 * A drag from another X client onto the window, played by a test client on a second
 * connection to the same display: XdndEnter, XdndPosition and XdndDrop, with the files
 * served as `text/uri-list` when the window asks for the selection.
 *
 * Skipped where there is no display. It runs under a virtual display on CI.
 */
class XdndDropTest {

    @Test
    fun fr_drop_of_a_uri_list_reaches_the_window_as_paths() {
        if (getenv("DISPLAY")?.toKString().isNullOrEmpty()) return
        val heard = mutableListOf<WindowEvent>()
        val window = X11Window()
        val opened = window.open(
            WindowConfig("xdnd", 320, 240),
            object : WindowListener {
                override fun onEvent(event: WindowEvent) {
                    heard += event
                }
            },
        )
        assertTrue(opened, "a display is present and the window did not open")
        val client = DragClient(window)
        client.enter("text/uri-list")
        client.position(10, 20)
        val accepted = client.settle(window) { client.statusSeen }
        assertTrue(accepted, "the window never answered XdndPosition with XdndStatus")
        assertTrue(client.statusAccepted, "a drag offering text/uri-list was not accepted")
        client.drop("file:///tmp/a%20b.txt\r\nfile://localhost/tmp/%C3%BC.txt\r\n# a comment\r\n")
        assertTrue(client.settle(window) { client.finishedSeen }, "the window never sent XdndFinished")
        assertTrue(client.finishedAccepted, "a drop of files was reported as not accepted")

        val entered = heard.first { it.kind == WindowEvent.FILES_ENTERED }
        assertEquals(10f, entered.x)
        assertEquals(20f, entered.y)
        val dropped = heard.first { it.kind == WindowEvent.FILES_DROPPED }
        assertEquals("/tmp/a b.txt\u0000/tmp/ü.txt", dropped.text)
        assertEquals(listOf("/tmp/a b.txt", "/tmp/ü.txt"), FileDrop.dropped.value)
        client.close()
        window.close()
    }

    @Test
    fun fr_drag_of_something_else_is_refused() {
        if (getenv("DISPLAY")?.toKString().isNullOrEmpty()) return
        val heard = mutableListOf<WindowEvent>()
        val window = X11Window()
        val opened = window.open(
            WindowConfig("xdnd refuse", 320, 240),
            object : WindowListener {
                override fun onEvent(event: WindowEvent) {
                    heard += event
                }
            },
        )
        assertTrue(opened, "a display is present and the window did not open")
        val client = DragClient(window)
        client.enter("text/plain")
        client.position(10, 20)
        assertTrue(client.settle(window) { client.statusSeen }, "the window never answered XdndPosition")
        assertFalse(client.statusAccepted, "a drag of text was accepted as a drag of files")
        assertTrue(heard.none { it.kind == WindowEvent.FILES_ENTERED })
        client.close()
        window.close()
    }

    @Test
    fun fr_uri_list_keeps_file_uris_and_decodes_escapes() {
        assertEquals(
            listOf("/home/me/a b", "/x/é"),
            pathsFromUriList("file:///home/me/a%20b\r\nhttp://example.com/\r\nfile:///x/%C3%A9\n"),
        )
    }
}

/** The dragging program: a window of its own on a second connection to the display. */
private class DragClient(private val target: X11Window) {
    private val display: CPointer<Display> = XOpenDisplay(null)!!
    private val source: Window = XCreateSimpleWindow(
        display, XDefaultRootWindow(display), 0, 0, 10u, 10u, 0u, 0uL, 0uL,
    )
    private val enterAtom = XInternAtom(display, "XdndEnter", 0)
    private val positionAtom = XInternAtom(display, "XdndPosition", 0)
    private val statusAtom = XInternAtom(display, "XdndStatus", 0)
    private val dropAtom = XInternAtom(display, "XdndDrop", 0)
    private val finishedAtom = XInternAtom(display, "XdndFinished", 0)
    private val selectionAtom = XInternAtom(display, "XdndSelection", 0)
    private val copyAtom = XInternAtom(display, "XdndActionCopy", 0)
    private var served = ""
    private var servedType: Atom = 0uL

    var statusSeen = false
    var statusAccepted = false
    var finishedSeen = false
    var finishedAccepted = false

    fun enter(type: String) {
        servedType = XInternAtom(display, type, 0)
        XSetSelectionOwner(display, selectionAtom, source, 0uL)
        send(enterAtom, source.toLong(), 5L shl 24, servedType.toLong(), 0, 0)
    }

    fun position(x: Int, y: Int) {
        val rootX = memScoped {
            val rx = alloc<IntVar>()
            val ry = alloc<IntVar>()
            val child = alloc<ULongVar>()
            XTranslateCoordinates(display, target.windowId, XDefaultRootWindow(display), x, y, rx.ptr, ry.ptr, child.ptr)
            (rx.value.toLong() shl 16) or (ry.value.toLong() and 0xFFFF)
        }
        send(positionAtom, source.toLong(), 0, rootX, 0, copyAtom.toLong())
    }

    fun drop(uriList: String) {
        served = uriList
        send(dropAtom, source.toLong(), 0, 0, 0, 0)
    }

    /** Runs both ends until [done] holds or a few seconds pass. */
    fun settle(window: X11Window, done: () -> Boolean): Boolean {
        var rounds = 0
        while (!done() && rounds < 300) {
            window.pump(10)
            serve()
            rounds++
        }
        return done()
    }

    fun close() {
        XDestroyWindow(display, source)
        XCloseDisplay(display)
    }

    private fun send(type: Atom, l0: Long, l1: Long, l2: Long, l3: Long, l4: Long) {
        memScoped {
            val event = alloc<XEvent>()
            event.xclient.type = ClientMessage
            event.xclient.window = target.windowId
            event.xclient.message_type = type
            event.xclient.format = 32
            event.xclient.data.l[0] = l0
            event.xclient.data.l[1] = l1
            event.xclient.data.l[2] = l2
            event.xclient.data.l[3] = l3
            event.xclient.data.l[4] = l4
            XSendEvent(display, target.windowId, 0, 0, event.ptr)
        }
        XFlush(display)
    }

    private fun serve() {
        memScoped {
            val event = alloc<XEvent>()
            while (XPending(display) > 0) {
                XNextEvent(display, event.ptr)
                when (event.type) {
                    ClientMessage -> when (event.xclient.message_type) {
                        statusAtom -> {
                            statusSeen = true
                            statusAccepted = (event.xclient.data.l[1] and 1L) != 0L
                        }

                        finishedAtom -> {
                            finishedSeen = true
                            finishedAccepted = (event.xclient.data.l[1] and 1L) != 0L
                        }
                    }

                    SelectionRequest -> answer(event)
                }
            }
        }
    }

    private fun answer(event: XEvent) {
        val request = event.xselectionrequest
        val bytes = served.encodeToByteArray()
        memScoped {
            val data = allocArray<UByteVar>(bytes.size + 1)
            for (index in bytes.indices) data[index] = bytes[index].toUByte()
            XChangeProperty(display, request.requestor, request.property, servedType, 8, PropModeReplace, data, bytes.size)
            val reply = alloc<XEvent>()
            reply.xselection.type = SelectionNotify
            reply.xselection.requestor = request.requestor
            reply.xselection.selection = request.selection
            reply.xselection.target = request.target
            reply.xselection.property = request.property
            reply.xselection.time = request.time
            XSendEvent(display, request.requestor, 0, 0, reply.ptr)
        }
        XFlush(display)
    }
}
