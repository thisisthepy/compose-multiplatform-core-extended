@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.linux

import androidx.compose.ui.geometry.Offset
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import org.thisisthepy.compose.window.WindowEvent
import x11.*

/**
 * The receiving end of the X drag-and-drop protocol (XDND version 5) for one window.
 *
 * It accepts a drag only if the source offers `text/uri-list`, asks for that selection on
 * the drop, and reports the paths in it as [WindowEvent.FILES_ENTERED], [WindowEvent.FILES_DROPPED]
 * and [WindowEvent.FILES_EXITED], the same events the macOS window reports. The positions are in
 * the window's own pixels. For a drop, [WindowEvent.text] carries the paths with a NUL between them.
 * [FileDrop] is updated alongside.
 */
internal class XdndTarget(
    private val display: CPointer<Display>,
    private val window: Window,
    private val report: (WindowEvent) -> Unit,
) {
    private val aware = XInternAtom(display, "XdndAware", 0)
    private val enter = XInternAtom(display, "XdndEnter", 0)
    private val position = XInternAtom(display, "XdndPosition", 0)
    private val status = XInternAtom(display, "XdndStatus", 0)
    private val leave = XInternAtom(display, "XdndLeave", 0)
    private val drop = XInternAtom(display, "XdndDrop", 0)
    private val finished = XInternAtom(display, "XdndFinished", 0)
    private val selection = XInternAtom(display, "XdndSelection", 0)
    private val copy = XInternAtom(display, "XdndActionCopy", 0)
    private val typeList = XInternAtom(display, "XdndTypeList", 0)
    private val uriList = XInternAtom(display, "text/uri-list", 0)
    private val transfer = XInternAtom(display, "COMPOSE_WINDOW_DROP", 0)

    private var source: Window = 0uL
    private var hasFiles = false
    private var lastX = 0f
    private var lastY = 0f

    /** Marks the window as a drop target, which is what makes a source offer anything. */
    fun attach() {
        memScoped {
            val version = allocArray<ULongVar>(1)
            version[0] = PROTOCOL_VERSION
            XChangeProperty(
                display, window, aware, XA_ATOM, 32, PropModeReplace,
                version.reinterpret<UByteVar>(), 1,
            )
        }
    }

    /** Takes a client message if it belongs to the protocol; false means it is not ours. */
    fun handleClientMessage(event: XEvent): Boolean {
        val type = event.xclient.message_type
        when (type) {
            enter -> {
                source = event.xclient.data.l[0].toULong()
                val moreThanThree = (event.xclient.data.l[1] and 1L) != 0L
                hasFiles = if (moreThanThree) {
                    offersUriList(readTypeList())
                } else {
                    (2..4).any { event.xclient.data.l[it].toULong() == uriList }
                }
            }

            position -> {
                if (event.xclient.data.l[0].toULong() != source) return true
                val packed = event.xclient.data.l[2]
                val rootX = ((packed shr 16) and 0xFFFF).toInt()
                val rootY = (packed and 0xFFFF).toInt()
                toWindow(rootX, rootY)
                if (hasFiles) {
                    report(WindowEvent(WindowEvent.FILES_ENTERED, lastX, lastY, 0, 0, 0, 0, ""))
                    FileDrop.hovering.value = Offset(lastX, lastY)
                }
                sendStatus()
            }

            leave -> {
                if (event.xclient.data.l[0].toULong() != source) return true
                if (hasFiles) exited()
                source = 0uL
                hasFiles = false
            }

            drop -> {
                if (event.xclient.data.l[0].toULong() != source) return true
                if (!hasFiles) {
                    sendFinished(false)
                    source = 0uL
                    return true
                }
                val time = event.xclient.data.l[2].toULong()
                XConvertSelection(display, selection, uriList, transfer, window, time)
                XFlush(display)
            }

            else -> return false
        }
        return true
    }

    /** Takes the answer to the request made on a drop; false means it is not ours. */
    fun handleSelectionNotify(event: XEvent): Boolean {
        if (event.xselection.selection != selection) return false
        var paths = emptyList<String>()
        if (event.xselection.property != 0uL) {
            paths = pathsFromUriList(readTransfer())
        }
        FileDrop.hovering.value = null
        if (paths.isNotEmpty()) {
            FileDrop.dropped.value = paths
            report(
                WindowEvent(
                    WindowEvent.FILES_DROPPED, lastX, lastY, 0, 0, 0, 0,
                    paths.joinToString("\u0000"),
                ),
            )
        } else {
            report(WindowEvent(WindowEvent.FILES_EXITED, 0f, 0f, 0, 0, 0, 0, ""))
        }
        sendFinished(paths.isNotEmpty())
        source = 0uL
        hasFiles = false
        return true
    }

    private fun exited() {
        FileDrop.hovering.value = null
        report(WindowEvent(WindowEvent.FILES_EXITED, 0f, 0f, 0, 0, 0, 0, ""))
    }

    private fun offersUriList(types: List<ULong>): Boolean = types.any { it == uriList }

    private fun readTypeList(): List<ULong> = memScoped {
        val actual = alloc<ULongVar>()
        val format = alloc<IntVar>()
        val count = alloc<ULongVar>()
        val remaining = alloc<ULongVar>()
        val data = alloc<CPointerVar<UByteVar>>()
        val status = XGetWindowProperty(
            display, source, typeList, 0, 1024, 0, XA_ATOM,
            actual.ptr, format.ptr, count.ptr, remaining.ptr, data.ptr,
        )
        val bytes = data.value
        val atoms = ArrayList<ULong>()
        if (status == 0 && bytes != null && format.value == 32) {
            val words = bytes.reinterpret<ULongVar>()
            for (index in 0 until count.value.toInt()) atoms.add(words[index])
        }
        if (bytes != null) XFree(bytes)
        atoms
    }

    private fun readTransfer(): String = memScoped {
        val actual = alloc<ULongVar>()
        val format = alloc<IntVar>()
        val count = alloc<ULongVar>()
        val remaining = alloc<ULongVar>()
        val data = alloc<CPointerVar<UByteVar>>()
        val status = XGetWindowProperty(
            display, window, transfer, 0, MAX_WORDS, 1, 0uL,
            actual.ptr, format.ptr, count.ptr, remaining.ptr, data.ptr,
        )
        val bytes = data.value
        val text = if (status == 0 && bytes != null && format.value == 8) {
            bytes.readBytes(count.value.toInt()).decodeToString()
        } else {
            ""
        }
        if (bytes != null) XFree(bytes)
        text
    }

    private fun toWindow(rootX: Int, rootY: Int) = memScoped {
        val x = alloc<IntVar>()
        val y = alloc<IntVar>()
        val child = alloc<ULongVar>()
        XTranslateCoordinates(
            display, XRootWindow(display, XDefaultScreen(display)), window,
            rootX, rootY, x.ptr, y.ptr, child.ptr,
        )
        lastX = x.value.toFloat()
        lastY = y.value.toFloat()
    }

    private fun sendStatus() {
        memScoped {
            val reply = alloc<XEvent>()
            reply.xclient.type = ClientMessage
            reply.xclient.window = source
            reply.xclient.message_type = status
            reply.xclient.format = 32
            reply.xclient.data.l[0] = window.toLong()
            // Bit 0: the drop would be accepted. Bit 1: keep sending positions in the rectangle.
            reply.xclient.data.l[1] = if (hasFiles) 1L else 0L
            reply.xclient.data.l[2] = 0
            reply.xclient.data.l[3] = 0
            reply.xclient.data.l[4] = if (hasFiles) copy.toLong() else 0L
            XSendEvent(display, source, 0, 0, reply.ptr)
        }
        XFlush(display)
    }

    private fun sendFinished(accepted: Boolean) {
        if (source == 0uL) return
        memScoped {
            val reply = alloc<XEvent>()
            reply.xclient.type = ClientMessage
            reply.xclient.window = source
            reply.xclient.message_type = finished
            reply.xclient.format = 32
            reply.xclient.data.l[0] = window.toLong()
            reply.xclient.data.l[1] = if (accepted) 1L else 0L
            reply.xclient.data.l[2] = if (accepted) copy.toLong() else 0L
            XSendEvent(display, source, 0, 0, reply.ptr)
        }
        XFlush(display)
    }

    private companion object {
        const val PROTOCOL_VERSION = 5uL
        const val XA_ATOM = 4uL
        const val MAX_WORDS = 1_000_000L
    }
}
