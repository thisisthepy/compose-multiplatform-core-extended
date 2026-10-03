/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.window

import java.awt.AWTEvent
import java.awt.Image
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.WindowEvent
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * The application's icon on every window it opens, once any of its Compose windows has one.
 *
 * A window with no icon of its own wears the toolkit's, which on Windows is the Java coffee
 * cup, and Windows lists windows rather than processes in the taskbar, in Alt+Tab and under
 * a process in Task Manager. A window owned by another inherits its owner's icon from the
 * toolkit, but a window with no owner (a second `Window {}`, a dialog opened without a
 * parent) is born with the cup even when the application gave its first window an icon.
 *
 * So the icon a Compose window is given becomes the application's: it goes on every
 * ownerless window that is open with no icon of its own, and on every one that opens later.
 * A window that was given an icon of its own keeps it. When the application's icon changes,
 * the windows that wore the old one only because of this wear the new one.
 *
 * `compose.window.applicationIcon=false` leaves every window with exactly what it was given.
 */
internal object ApplicationIcon {
    const val Property = "compose.window.applicationIcon"

    private var images: List<Image> = emptyList()
    private var listening = false

    // The window whose icon is the application's: the first Compose window given one, until
    // it is disposed.
    private var source: WeakReference<Window>? = null

    // True while an icon is being put on a window here, so that the window telling us its
    // icon changed is not taken for the application choosing one.
    private var assigning = false

    // Windows wearing the application's icon because it was put on them here. Weak, so a
    // closed window is not kept alive by having once been given an icon.
    private val given: MutableSet<Window> = Collections.newSetFromMap(WeakHashMap())

    private val enabled: Boolean
        get() = !System.getProperty(Property).equals("false", ignoreCase = true)

    /** A Compose window was given [icons]. */
    @Synchronized
    fun windowIconChanged(window: Window, icons: List<Image>) {
        if (!enabled || assigning) return
        // The application chose this window's icon itself, so it is no longer one of ours.
        given -= window
        val current = source?.get()
        if (current != null && current !== window) return
        if (icons.isEmpty()) return
        source = WeakReference(window)
        if (icons == images) return
        images = icons
        listen()
        applyTo(Window.getWindows().asList())
    }

    /** A Compose window is gone; if its icon was the application's, the next one given an icon takes over. */
    @Synchronized
    fun windowDisposed(window: Window) {
        given -= window
        if (source?.get() === window) source = null
    }

    @Synchronized
    private fun applyTo(windows: List<Window>) {
        val ours = images
        val taking = windowsTakingApplicationIcon(
            windows = windows,
            ours = ours,
            ownerless = { it.owner == null },
            current = { it.iconImages.orEmpty() },
            givenHere = { it in given },
        )
        assigning = true
        try {
            for (window in taking) {
                given += window
                window.iconImages = ours
            }
        } finally {
            assigning = false
        }
    }

    private fun listen() {
        if (listening) return
        listening = true
        // Every window passes through here as it opens, whoever created it.
        Toolkit.getDefaultToolkit().addAWTEventListener(
            { event ->
                if (event.id == WindowEvent.WINDOW_OPENED) {
                    (event as? WindowEvent)?.window?.let { applyTo(listOf(it)) }
                }
            },
            AWTEvent.WINDOW_EVENT_MASK,
        )
    }
}

/**
 * Which of [windows] take the application's icon [ours]: the ownerless ones that have no icon,
 * and the ones that wear an older application icon only because it was given to them here.
 *
 * Apart from AWT so it can be tested without a display. Which windows are chosen is the part
 * that can be wrong in a way nobody notices; how an icon is put on a window is AWT's.
 */
internal fun <W> windowsTakingApplicationIcon(
    windows: List<W>,
    ours: List<Image>,
    ownerless: (W) -> Boolean,
    current: (W) -> List<Image>,
    givenHere: (W) -> Boolean,
): List<W> {
    if (ours.isEmpty()) return emptyList()
    return windows.filter { window ->
        val icons = current(window)
        ownerless(window) && icons != ours && (icons.isEmpty() || givenHere(window))
    }
}
