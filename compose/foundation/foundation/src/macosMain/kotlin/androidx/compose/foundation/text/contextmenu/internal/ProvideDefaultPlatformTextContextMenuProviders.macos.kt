/*
 * Copyright 2024 The Android Open Source Project
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

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package androidx.compose.foundation.text.contextmenu.internal

import androidx.compose.foundation.text.contextmenu.data.TextContextMenuComponent
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItemWithComposableLeadingIcon
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSeparator
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuDropdownProvider
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import platform.AppKit.NSApplication
import platform.AppKit.NSEvent
import platform.AppKit.NSMenu
import platform.AppKit.NSMenuItem
import platform.AppKit.NSView
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGPointMake
import platform.darwin.NSObject
import platform.darwin.sel_registerName

/**
 * The menu a selection offers, drawn by the system rather than by Compose.
 *
 * A menu that came from the platform is the one the reader already knows: it takes the
 * same keys, it reads to a screen reader, it dismisses the way every other menu does, and
 * it looks like the rest of the machine. Compose draws a very good imitation and an
 * imitation is what it stays.
 *
 * What is in the menu is still Compose's: the components it hands over are turned into
 * items here and nothing is added or left out.
 */
@Composable
internal actual fun ProvideDefaultPlatformTextContextMenuProviders(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    // Where the menu is put is in the coordinates of whatever the menu is for, so the
    // layout it belongs to has to be known before one can be shown.
    val anchor = remember { AnchorCoordinates() }
    val provider = remember { NativeTextContextMenuProvider(anchor) }
    CompositionLocalProvider(
        LocalTextContextMenuDropdownProvider provides provider,
        LocalTextContextMenuToolbarProvider provides provider,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier.onGloballyPositioned { anchor.coordinates = it }
        ) {
            content()
        }
    }
}

/** Where the screen is, so that a point in it can be turned into a point on the screen. */
private class AnchorCoordinates {
    var coordinates: LayoutCoordinates? = null
}

private class NativeTextContextMenuProvider(
    private val anchor: AnchorCoordinates,
) : TextContextMenuProvider {

    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        val coordinates = anchor.coordinates ?: return
        val view = NSApplication.sharedApplication.keyWindow?.contentView ?: return

        val menu = NSMenu()
        menu.setAutoenablesItems(false)
        val actions = mutableListOf<MenuAction>()
        var wanted = 0
        suspendCancellableCoroutine { waiting ->
            val session = object : TextContextMenuSession {
                override fun close() = menu.cancelTracking()
            }
            for (component in dataProvider.data().components) {
                when (component) {
                    is TextContextMenuSeparator -> menu.addItem(NSMenuItem.separatorItem())
                    is TextContextMenuItemWithComposableLeadingIcon -> {
                        // The icon is Compose's to draw and the system draws this menu, so
                        // the label is what crosses. Every item Compose offers here has
                        // one.
                        val item = NSMenuItem()
                        item.setTitle(component.label)
                        item.setEnabled(component.enabled)
                        val action = MenuAction { component.onClick(session) }
                        item.setTarget(action)
                        item.setAction(sel_registerName("perform"))
                        // A menu item holds its target weakly, so without this the object
                        // that carries the closure is collected before the menu is shown
                        // and every entry does nothing when it is chosen.
                        actions += action
                        menu.addItem(item)
                        wanted++
                    }
                    else -> Unit
                }
            }
            if (wanted == 0) {
                waiting.resume(Unit)
                return@suspendCancellableCoroutine
            }

            // At the pointer, which is where a right click opens a menu on this platform.
            // The layout's position is in pixels from the layout's own corner, and the view
            // counts points from its corner, upward unless it is flipped: reading one as the
            // other put the menu twice as far from the bottom as the click on a Retina
            // screen, so it opened above the pointer instead of below it.
            val where = menuLocationInView(
                view = view,
                screenPoint = NSEvent.mouseLocation,
                fallback = dataProvider.position(coordinates),
            )
            waiting.invokeOnCancellation { menu.cancelTracking() }
            // Returns when the menu closes, whichever way it closed.
            menu.popUpMenuPositioningItem(null, where, view)
            waiting.resume(Unit)
        }
    }
}

/**
 * Where in [view] a menu opened at [screenPoint] belongs, in the view's own points.
 *
 * AppKit converts from the screen to the window and from the window to the view, which
 * accounts for where the view sits and for whether it is flipped. A view that is in no
 * window has no screen to convert from, and then [fallback], a position from the top left
 * of the content, is used, turned the right way up for a view that is not flipped.
 */
internal fun menuLocationInView(
    view: NSView,
    screenPoint: CValue<CGPoint>,
    fallback: Offset,
): CValue<CGPoint> {
    val window = view.window
    if (window != null) {
        return view.convertPoint(window.convertPointFromScreen(screenPoint), fromView = null)
    }
    return view.frame.useContents {
        val x = fallback.x.toDouble()
        val y = fallback.y.toDouble()
        CGPointMake(x, if (view.isFlipped()) y else size.height - y)
    }
}

/** Holds the closure an item runs, because a menu item calls a selector on a target. */
private class MenuAction(private val run: () -> Unit) : NSObject() {
    @kotlinx.cinterop.ObjCAction
    fun perform() = run()
}
