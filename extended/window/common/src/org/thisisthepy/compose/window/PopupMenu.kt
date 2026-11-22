package org.thisisthepy.compose.window

/**
 * One entry of a menu the operating system puts up (compose-rust FR-33.2): `NSMenu` on macOS,
 * a Win32 popup menu (`TrackPopupMenuEx`) on Windows. Only what every such menu can show: an
 * item, a separator, a submenu. The renderer has already normalised the list (no stray
 * separators, a disabled submenu has no children).
 */
sealed interface PopupMenuEntry {
    /**
     * An entry that can be chosen. [shortcut] is text shown at the trailing edge; the menu does
     * not register the key, which the application handles itself. [icon] is the picture the
     * renderer drew for the entry, or null.
     */
    data class Item(
        val label: String,
        val shortcut: String = "",
        val enabled: Boolean = true,
        val checked: Boolean = false,
        val icon: PopupMenuIcon? = null,
    ) : PopupMenuEntry

    /** A line between groups. */
    data object Separator : PopupMenuEntry

    /** An entry that opens a menu of [children]; a disabled one shows greyed and opens nothing. */
    data class Submenu(
        val label: String,
        val children: List<PopupMenuEntry>,
        val enabled: Boolean = true,
        val icon: PopupMenuIcon? = null,
    ) : PopupMenuEntry
}

/**
 * An entry's picture: [width] by [height] pixels of eight-bit red, green, blue and alpha,
 * premultiplied, rows unpadded, drawn at the window's scale.
 */
class PopupMenuIcon(val rgba: ByteArray, val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0 && rgba.size == width * height * 4) { "an icon is width x height x 4 bytes" }
    }
}

/**
 * Where a menu opens: the anchor's rectangle in the window's pixels from the top left of its
 * content. The menu opens below the anchor's bottom edge with its start edge on the anchor's
 * start edge (left, or right when [rightToLeft]); the anchor's width is the menu's minimum width
 * where the platform has one.
 */
data class PopupMenuAnchor(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rightToLeft: Boolean = false,
)

/**
 * The lines the C and Objective-C windows read a menu from (the GraalVM paths and the Win32
 * window), one per entry, depth first, fields separated by a tab:
 * `kind depth enabled checked icon label shortcut`. `kind` is `I` an item, `S` a separator, `M`
 * a submenu, whose children follow at depth + 1. `icon` is the entry's index in [icons], or -1.
 * Tabs and line breaks in text become spaces.
 */
class PopupMenuLines(val text: String, val icons: List<PopupMenuIcon>)

fun encodePopupMenu(entries: List<PopupMenuEntry>): PopupMenuLines {
    val lines = StringBuilder()
    val icons = ArrayList<PopupMenuIcon>()
    fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
    fun icon(picture: PopupMenuIcon?): Int = if (picture == null) -1 else icons.size.also { icons += picture }
    fun add(list: List<PopupMenuEntry>, depth: Int) {
        for (entry in list) {
            when (entry) {
                is PopupMenuEntry.Item -> lines.append(
                    "I\t$depth\t${if (entry.enabled) 1 else 0}\t${if (entry.checked) 1 else 0}\t${icon(entry.icon)}\t" +
                        "${clean(entry.label)}\t${clean(entry.shortcut)}\n",
                )
                PopupMenuEntry.Separator -> lines.append("S\t$depth\t0\t0\t-1\t\t\n")
                is PopupMenuEntry.Submenu -> {
                    lines.append("M\t$depth\t${if (entry.enabled) 1 else 0}\t0\t${icon(entry.icon)}\t${clean(entry.label)}\t\n")
                    if (entry.enabled) add(entry.children, depth + 1)
                }
            }
        }
    }
    add(entries, 0)
    return PopupMenuLines(lines.toString(), icons)
}

/**
 * The path a window answered with: indices from the top down, separated by `/` (`"2/0"`), or null
 * for an empty answer, which is a menu closed with nothing chosen.
 */
fun decodePopupMenuPath(answer: String?): List<Int>? {
    if (answer.isNullOrEmpty()) return null
    val parts = answer.split('/')
    val path = parts.map { it.toIntOrNull() ?: return null }
    return if (path.any { it < 0 }) null else path
}

/** [path] as a window answers it. */
fun encodePopupMenuPath(path: List<Int>): String = path.joinToString("/")

/**
 * One line per entry a platform menu shows, read back from that menu: indent, kind, and the
 * state a person sees. The macOS menus of both paths describe themselves this way, and the
 * parity check compares the two (FR-33.2 criterion 6).
 */
fun describePopupMenu(entries: List<PopupMenuEntry>): String {
    val out = StringBuilder()
    fun add(list: List<PopupMenuEntry>, depth: Int) {
        for (entry in list) {
            val indent = "  ".repeat(depth)
            when (entry) {
                is PopupMenuEntry.Item -> out.append(
                    "${indent}item \"${entry.label}\" shortcut=\"${entry.shortcut}\" enabled=${entry.enabled} " +
                        "checked=${entry.checked} icon=${entry.icon != null}\n",
                )
                PopupMenuEntry.Separator -> out.append("${indent}separator\n")
                is PopupMenuEntry.Submenu -> {
                    out.append("${indent}submenu \"${entry.label}\" enabled=${entry.enabled} icon=${entry.icon != null}\n")
                    if (entry.enabled) add(entry.children, depth + 1)
                }
            }
        }
    }
    add(entries, 0)
    return out.toString()
}
