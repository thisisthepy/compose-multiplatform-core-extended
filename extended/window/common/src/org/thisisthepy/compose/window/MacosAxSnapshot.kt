package org.thisisthepy.compose.window

import kotlin.math.abs

/**
 * One accessibility element of a running macOS application, as its own process saw it.
 *
 * Both macOS windows can write down every element of the application's windows, walking them
 * through AppKit's accessibility protocol in process: the same objects AppKit's accessibility
 * server answers VoiceOver and every other client from. A client outside the process needs the
 * user to have trusted it for accessibility, which a machine nobody sits at (a CI runner) has
 * not done, so a check that has to run there asks the application itself.
 *
 * [depth] is 0 for a window. The frame is in screen points with the origin at the bottom left,
 * as AppKit reports it.
 */
data class MacosAxNode(
    val depth: Int,
    val role: String,
    val subrole: String,
    val title: String,
    val label: String,
    val value: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    val top: Double get() = y + height
}

/** A walk of an application's windows: the macOS major release and every element, in order. */
data class MacosAxSnapshot(val macosMajor: Int, val nodes: List<MacosAxNode>)

private fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

/** One element as a line of the snapshot text both windows write. */
fun formatMacosAxNode(node: MacosAxNode): String = listOf(
    node.depth.toString(), clean(node.role), clean(node.subrole), clean(node.title), clean(node.label),
    clean(node.value), node.x.toString(), node.y.toString(), node.width.toString(), node.height.toString(),
).joinToString("\t")

/** The first line of the snapshot text. */
fun formatMacosAxHeader(macosMajor: Int): String = "#macos\t$macosMajor"

/** Reads what [formatMacosAxHeader] and [formatMacosAxNode] wrote, skipping lines it cannot read. */
fun parseMacosAxSnapshot(text: String): MacosAxSnapshot {
    var major = 0
    val nodes = ArrayList<MacosAxNode>()
    for (line in text.lineSequence()) {
        if (line.isBlank()) continue
        val fields = line.split('\t')
        if (fields[0] == "#macos") {
            major = fields.getOrNull(1)?.toIntOrNull() ?: 0
            continue
        }
        if (fields.size < 10) continue
        val depth = fields[0].toIntOrNull() ?: continue
        nodes += MacosAxNode(
            depth, fields[1], fields[2], fields[3], fields[4], fields[5],
            fields[6].toDoubleOrNull() ?: 0.0, fields[7].toDoubleOrNull() ?: 0.0,
            fields[8].toDoubleOrNull() ?: 0.0, fields[9].toDoubleOrNull() ?: 0.0,
        )
    }
    return MacosAxSnapshot(major, nodes)
}

/** The elements inside the window titled [title] (the window first), or empty where there is none. */
fun MacosAxSnapshot.window(title: String): List<MacosAxNode> {
    val start = nodes.indexOfFirst { it.depth == 0 && it.role == MacosAx.WINDOW && it.title == title }
    if (start < 0) return emptyList()
    val end = (start + 1 until nodes.size).firstOrNull { nodes[it].depth == 0 } ?: nodes.size
    return nodes.subList(start, end)
}

/**
 * What is wrong with the window titled [title] for a window built as [bar] that reported
 * [caption], read from what a reader is told; empty when nothing is.
 *
 * Under a modern title bar: the close, minimise and zoom buttons are there, the content starts
 * at the window's top edge (it runs under the bar), the title is carried for the switcher but
 * no text in the bar shows it, the caption is the bar's height and the room the buttons take as
 * the buttons' own frames say, the scene draws no buttons, and the corner radius is the table's
 * for the style and release. Under the system title bar: the buttons are there and the content
 * starts below the bar.
 */
fun macosChromeFindings(
    snapshot: MacosAxSnapshot,
    title: String,
    bar: MacosTitleBar,
    caption: WindowCaption,
): List<String> {
    val found = ArrayList<String>()
    val elements = snapshot.window(title)
    val window = elements.firstOrNull() ?: return listOf("no window titled '$title' in ${snapshot.nodes.size} elements")
    fun button(vararg subroles: String) = elements.firstOrNull { it.role == MacosAx.BUTTON && it.subrole in subroles }
    val close = button(MacosAx.CLOSE_BUTTON_SUBROLE)
    val minimise = button(MacosAx.MINIMIZE_BUTTON_SUBROLE)
    val zoom = button(MacosAx.ZOOM_BUTTON_SUBROLE, MacosAx.FULL_SCREEN_BUTTON_SUBROLE)
    if (close == null) found += "no close button"
    if (minimise == null) found += "no minimise button"
    if (zoom == null) found += "no zoom button"
    // The content is the tallest group as wide as the window: the view the window holds.
    val content = elements.filter {
        it.depth >= 1 && it.role == MacosAx.GROUP && abs(it.width - window.width) <= 1.0
    }.maxByOrNull { it.height }
    if (content == null) {
        found += "no content view in the window"
    }
    if (!bar.fullSizeContentView) {
        if (content != null && content.top >= window.top - 1.0) found += "the content starts under the system's bar"
        if (caption != WindowCaption.None) found += "a window under the system's bar reported a caption: $caption"
        return found
    }
    if (content != null && abs(content.top - window.top) > 1.0) {
        found += "the content starts ${window.top - content.top} pt below the window's top, not under the bar"
    }
    if (elements.any { it.depth >= 1 && it.role == MacosAx.STATIC_TEXT && (it.value == title || it.title == title) }) {
        if (bar.titleHidden) found += "the title bar shows the title as text"
    }
    if (caption.height <= 0f) found += "the caption has no height: $caption"
    if (caption.buttonsDrawnByScene) found += "the caption says the scene draws the buttons"
    if (!caption.buttonsAtStart) found += "the caption puts the buttons at the trailing edge"
    if (close != null && zoom != null) {
        val closeMinX = close.x - window.x
        val zoomMaxX = zoom.x + zoom.width - window.x
        val expected = closeMinX + zoomMaxX
        if (abs(expected - caption.buttonsWidth) > 1.0) {
            found += "the caption's button room is ${caption.buttonsWidth} pt and the buttons take $expected pt"
        }
        // The buttons sit inside the strip the caption says the bar is.
        val buttonsFromTop = window.top - close.top
        if (buttonsFromTop < -1.0 || buttonsFromTop + close.height > caption.height + 1.0) {
            found += "the close button is outside the caption's ${caption.height} pt strip"
        }
    }
    val radius = macosCornerRadius(toolbar = bar.unifiedToolbar, macosMajor = snapshot.macosMajor)?.toFloat() ?: 0f
    if (caption.cornerRadius != radius) {
        found += "the corner radius is ${caption.cornerRadius} pt, the table says $radius pt on macOS ${snapshot.macosMajor}"
    }
    return found
}

/** The close button's distance from the window's leading edge, in points, or null. */
fun MacosAxSnapshot.closeButtonInset(title: String): Double? {
    val elements = window(title)
    val window = elements.firstOrNull() ?: return null
    val close = elements.firstOrNull { it.role == MacosAx.BUTTON && it.subrole == MacosAx.CLOSE_BUTTON_SUBROLE } ?: return null
    return close.x - window.x
}

/**
 * What a reader cannot find in the window titled [title]: each of [required], a role and a
 * label, has to be an element of the window; empty when every one is.
 */
fun macosAccessibleContentFindings(
    snapshot: MacosAxSnapshot,
    title: String,
    required: List<Pair<String, String>>,
): List<String> {
    val elements = snapshot.window(title)
    if (elements.isEmpty()) return listOf("no window titled '$title'")
    return required.mapNotNull { (role, label) ->
        val match = elements.any { it.role == role && (it.label == label || it.title == label) }
        if (match) null else "no $role labelled '$label' among ${elements.size} elements"
    }
}

/**
 * What the system's accessibility API answered when the application asked it for its own
 * windows, as an outside client would: the AXError ([error], 0 for success; -1 when the API
 * could not be reached, -2 when no answer came in time), whether the system trusts the process
 * for accessibility ([trusted]: 1, 0, or -1 for unknown) and how many windows it was told of
 * ([windows], or -1).
 */
data class MacosAxApiAnswer(val error: Int, val trusted: Int, val windows: Int) {
    /** One line for a log, naming the error where it is one a reader of the log would look up. */
    fun describe(): String {
        val name = when (error) {
            0 -> "success"
            -25211 -> "kAXErrorAPIDisabled: this process is not trusted for accessibility"
            -25204 -> "kAXErrorCannotComplete"
            -25200 -> "kAXErrorFailure"
            -1 -> "the accessibility API could not be reached"
            -2 -> "no answer in time"
            else -> "AXError $error"
        }
        return "AX API: trusted=$trusted, AXWindows error=$error ($name), windows=$windows"
    }
}
