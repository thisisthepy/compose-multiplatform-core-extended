@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.linux

import org.thisisthepy.compose.window.PixelRect
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import x11.XCreateSimpleWindow
import x11.XDefaultRootWindow
import x11.XDestroyWindow
import x11.XFlush
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LinuxNativeViewsTest {
    @Test
    fun fr81_linux_native_views_attach_place_focus_and_detach() {
        val window = X11Window()
        val focusEvents = mutableListOf<Pair<Int, Int>>()
        val listener = object : WindowListener {
            override fun onEvent(event: WindowEvent) {
                if (event.kind == WindowEvent.NATIVE_VIEW_FOCUS_IN || event.kind == WindowEvent.NATIVE_VIEW_FOCUS_OUT) {
                    focusEvents += event.kind to event.keyCode
                }
            }
        }
        if (!window.open(WindowConfig(title = "NativeViews", width = 400, height = 300), listener)) {
            return
        }
        try {
            val views = window.nativeViews
            assertNotEquals(0L, views.parentHandle)
            assertFalse(views.attach(1, 0L))

            val display = window.sharedConnection!!.display
            val child = XCreateSimpleWindow(
                display,
                XDefaultRootWindow(display),
                0,
                0,
                100u,
                80u,
                0u,
                0UL,
                0UL,
            )
            XFlush(display)
            try {
                assertTrue(views.attach(1, child.toLong()))
                assertFalse(views.attach(2, child.toLong()))

                views.setFrameAndVisibleRegion(1, 10, 20, 200, 120, PixelRect(10, 20, 200, 120), emptyList())
                views.setVisible(1, true)
                views.focus(1)
                views.focusScene()
                views.detach(1)

                assertEquals(
                    listOf(
                        WindowEvent.NATIVE_VIEW_FOCUS_IN to 1,
                        WindowEvent.NATIVE_VIEW_FOCUS_OUT to 1,
                    ),
                    focusEvents,
                )
            } finally {
                XDestroyWindow(display, child)
                XFlush(display)
            }
        } finally {
            window.close()
        }
    }
}
