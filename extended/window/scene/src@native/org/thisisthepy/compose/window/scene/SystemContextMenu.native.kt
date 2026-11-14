package org.thisisthepy.compose.window.scene

import androidx.compose.runtime.Composable

/**
 * Nothing to give on Kotlin/Native. Compose on macOS answers a field's secondary press with
 * the system's `NSMenu` itself on its new context menu path, which the macOS window turns on,
 * and keeps an application's own dropdown or toolbar provider when one is given; elsewhere it
 * draws its common menu.
 */
@Composable
internal actual fun ProvideSystemContextMenu(menus: SystemMenus, content: @Composable () -> Unit) = content()
