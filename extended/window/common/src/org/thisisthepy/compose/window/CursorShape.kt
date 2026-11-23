package org.thisisthepy.compose.window

/**
 * The pointer's shape, by the CSS `cursor` keyword (FR-47.1 in compose-rust), numbered as on the
 * wire: [ordinal] is the `Cursor` property's value. Each has a shape on every desktop window:
 * [win32Idc] for Windows, [x11Font] for X11 and [appKit] for macOS. Where a platform has no such
 * shape it takes the closest one (Windows has no magnifier, so the zoom keywords are the
 * crosshair; grabbing is the hand). [None] hides the pointer.
 *
 * Which keyword is shown (the deepest node that is not [Auto], held during a capture) is the
 * renderer's to decide; this only says what each one looks like.
 */
enum class CursorShape(val win32Idc: Int, val x11Font: Int, val appKit: AppKitCursor) {
    Auto(Win32Idc.ARROW, X11CursorFont.LEFT_PTR, AppKitCursor.Arrow),
    Default(Win32Idc.ARROW, X11CursorFont.LEFT_PTR, AppKitCursor.Arrow),
    Pointer(Win32Idc.HAND, X11CursorFont.HAND2, AppKitCursor.PointingHand),
    Text(Win32Idc.IBEAM, X11CursorFont.XTERM, AppKitCursor.IBeam),
    VerticalText(Win32Idc.IBEAM, X11CursorFont.XTERM, AppKitCursor.IBeamVertical),
    Crosshair(Win32Idc.CROSS, X11CursorFont.CROSSHAIR, AppKitCursor.Crosshair),
    Move(Win32Idc.SIZEALL, X11CursorFont.FLEUR, AppKitCursor.OpenHand),
    Grab(Win32Idc.HAND, X11CursorFont.HAND1, AppKitCursor.OpenHand),
    Grabbing(Win32Idc.HAND, X11CursorFont.FLEUR, AppKitCursor.ClosedHand),
    NotAllowed(Win32Idc.NO, X11CursorFont.X_CURSOR, AppKitCursor.OperationNotAllowed),
    NoDrop(Win32Idc.NO, X11CursorFont.X_CURSOR, AppKitCursor.OperationNotAllowed),
    Wait(Win32Idc.WAIT, X11CursorFont.WATCH, AppKitCursor.Arrow),
    Progress(Win32Idc.APPSTARTING, X11CursorFont.WATCH, AppKitCursor.Arrow),
    Help(Win32Idc.HELP, X11CursorFont.QUESTION_ARROW, AppKitCursor.Arrow),
    ContextMenu(Win32Idc.ARROW, X11CursorFont.LEFT_PTR, AppKitCursor.ContextualMenu),
    Cell(Win32Idc.CROSS, X11CursorFont.PLUS, AppKitCursor.Crosshair),
    Copy(Win32Idc.ARROW, X11CursorFont.LEFT_PTR, AppKitCursor.DragCopy),
    Alias(Win32Idc.ARROW, X11CursorFont.LEFT_PTR, AppKitCursor.DragLink),
    AllScroll(Win32Idc.SIZEALL, X11CursorFont.FLEUR, AppKitCursor.OpenHand),
    ZoomIn(Win32Idc.CROSS, X11CursorFont.CROSSHAIR, AppKitCursor.Crosshair),
    ZoomOut(Win32Idc.CROSS, X11CursorFont.CROSSHAIR, AppKitCursor.Crosshair),
    ColResize(Win32Idc.SIZEWE, X11CursorFont.SB_H_DOUBLE_ARROW, AppKitCursor.ResizeLeftRight),
    RowResize(Win32Idc.SIZENS, X11CursorFont.SB_V_DOUBLE_ARROW, AppKitCursor.ResizeUpDown),
    EResize(Win32Idc.SIZEWE, X11CursorFont.RIGHT_SIDE, AppKitCursor.ResizeRight),
    NResize(Win32Idc.SIZENS, X11CursorFont.TOP_SIDE, AppKitCursor.ResizeUp),
    NeResize(Win32Idc.SIZENESW, X11CursorFont.TOP_RIGHT_CORNER, AppKitCursor.ResizeLeftRight),
    NwResize(Win32Idc.SIZENWSE, X11CursorFont.TOP_LEFT_CORNER, AppKitCursor.ResizeLeftRight),
    SResize(Win32Idc.SIZENS, X11CursorFont.BOTTOM_SIDE, AppKitCursor.ResizeDown),
    SeResize(Win32Idc.SIZENWSE, X11CursorFont.BOTTOM_RIGHT_CORNER, AppKitCursor.ResizeLeftRight),
    SwResize(Win32Idc.SIZENESW, X11CursorFont.BOTTOM_LEFT_CORNER, AppKitCursor.ResizeLeftRight),
    WResize(Win32Idc.SIZEWE, X11CursorFont.LEFT_SIDE, AppKitCursor.ResizeLeft),
    EwResize(Win32Idc.SIZEWE, X11CursorFont.SB_H_DOUBLE_ARROW, AppKitCursor.ResizeLeftRight),
    NsResize(Win32Idc.SIZENS, X11CursorFont.SB_V_DOUBLE_ARROW, AppKitCursor.ResizeUpDown),
    NeswResize(Win32Idc.SIZENESW, X11CursorFont.TOP_RIGHT_CORNER, AppKitCursor.ResizeLeftRight),
    NwseResize(Win32Idc.SIZENWSE, X11CursorFont.BOTTOM_RIGHT_CORNER, AppKitCursor.ResizeLeftRight),
    None(0, -1, AppKitCursor.Hidden);

    companion object {
        /** The shape the wire value [value] names, or null for a value that is none. */
        fun fromWire(value: Int): CursorShape? = entries.getOrNull(value)
    }
}

/**
 * The system cursors Windows has (`IDC_*` in winuser.h, by number, so this names no Windows
 * header). 0 is no cursor: the pointer is hidden.
 */
object Win32Idc {
    const val ARROW = 32512
    const val IBEAM = 32513
    const val WAIT = 32514
    const val CROSS = 32515
    const val SIZENWSE = 32642
    const val SIZENESW = 32643
    const val SIZEWE = 32644
    const val SIZENS = 32645
    const val SIZEALL = 32646
    const val NO = 32648
    const val HAND = 32649
    const val APPSTARTING = 32650
    const val HELP = 32651
}

/** The X cursor font's glyphs (`XC_*` in X11/cursorfont.h), by number. -1 is a hidden pointer. */
object X11CursorFont {
    const val X_CURSOR = 0
    const val BOTTOM_LEFT_CORNER = 12
    const val BOTTOM_RIGHT_CORNER = 14
    const val BOTTOM_SIDE = 16
    const val CROSSHAIR = 34
    const val FLEUR = 52
    const val HAND1 = 58
    const val HAND2 = 60
    const val LEFT_PTR = 68
    const val LEFT_SIDE = 70
    const val PLUS = 90
    const val QUESTION_ARROW = 92
    const val RIGHT_SIDE = 96
    const val SB_H_DOUBLE_ARROW = 108
    const val SB_V_DOUBLE_ARROW = 116
    const val TOP_LEFT_CORNER = 134
    const val TOP_RIGHT_CORNER = 136
    const val TOP_SIDE = 138
    const val WATCH = 150
    const val XTERM = 152
}

/**
 * AppKit's public cursors (`NSCursor`), by name: the macOS window sets the one named. AppKit has
 * no public wait, help or diagonal resize cursor before macOS 15, so those take the arrow and
 * the left-right resize. [Hidden] hides the pointer.
 */
enum class AppKitCursor {
    Arrow, PointingHand, IBeam, IBeamVertical, Crosshair, OpenHand, ClosedHand, OperationNotAllowed,
    ContextualMenu, DragCopy, DragLink, ResizeLeftRight, ResizeUpDown, ResizeLeft, ResizeRight,
    ResizeUp, ResizeDown, Hidden,
}
