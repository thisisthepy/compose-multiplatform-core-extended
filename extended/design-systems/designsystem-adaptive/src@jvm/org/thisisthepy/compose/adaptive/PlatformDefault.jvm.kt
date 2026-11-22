package org.thisisthepy.compose.adaptive

import org.thisisthepy.compose.designsystem.DesignSystemId

actual fun platformDefaultDesignSystemId(): DesignSystemId {
    val os = System.getProperty("os.name")?.lowercase().orEmpty()
    return when {
        "mac" in os || "darwin" in os -> DesignSystemId.Cupertino
        "win" in os -> DesignSystemId.Fluent
        "linux" in os -> linuxDesktopSystem(System.getenv("XDG_CURRENT_DESKTOP"))
        else -> DesignSystemId.Material3
    }
}

internal fun linuxDesktopSystem(xdgDesktop: String?): DesignSystemId {
    val desktop = xdgDesktop?.lowercase().orEmpty()
    return when {
        "kde" in desktop || "plasma" in desktop -> DesignSystemId.Breeze
        "deepin" in desktop || "dde" in desktop -> DesignSystemId.Deepin
        else -> DesignSystemId.Gnome
    }
}
