package org.thisisthepy.compose.adaptive

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import org.thisisthepy.compose.designsystem.DesignSystemId
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
actual fun platformDefaultDesignSystemId(): DesignSystemId {
    val desktop = getenv("XDG_CURRENT_DESKTOP")?.toKString()?.lowercase().orEmpty()
    return when {
        "kde" in desktop || "plasma" in desktop -> DesignSystemId.Breeze
        "deepin" in desktop || "dde" in desktop -> DesignSystemId.Deepin
        else -> DesignSystemId.Gnome
    }
}
