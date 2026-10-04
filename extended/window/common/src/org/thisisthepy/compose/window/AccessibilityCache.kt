package org.thisisthepy.compose.window

/** What a control is, in the small set the scene and the platform agree on. */
object ElementRole {
    const val GROUP = 0
    const val BUTTON = 1
    const val TEXT = 2
    const val FIELD = 3
    const val CHECKBOX = 4
    const val IMAGE = 5
}

/** One thing in the window, as a reader who cannot see it would meet it. */
data class AccessibleElement(
    val role: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val label: String,
)

/**
 * The platform's copy of the accessibility tree, kept in step with the scene's.
 *
 * The platform asks for the tree on its own thread at moments nobody chose, so the tree is
 * described and pushed whenever it changes and the platform answers from what it was last
 * given. [describe] reads the scene (the renderer supplies it, because it needs Compose);
 * [push] hands a changed list to the platform.
 *
 * What was last handed over is kept, so reading again costs a comparison rather than a
 * crossing: a screen that is animating is placed anew every frame and says the same thing
 * about itself throughout.
 */
class AccessibilityCache(
    private val describe: () -> List<AccessibleElement>,
    private val push: (List<AccessibleElement>) -> Unit,
) {
    private var changed = false
    private var last: List<AccessibleElement> = emptyList()

    /** The scene said its tree changed. It is read after the next frame, when it is placed. */
    fun noteChanged() {
        changed = true
    }

    /** What the platform was last given. */
    val current: List<AccessibleElement> get() = last

    /**
     * Pushes the tree if it changed. After a frame that painted, it is read again whether or
     * not anything said so: a control that moved is at a new place without having changed.
     * Returns true when the platform was given a new list.
     */
    fun pushIfChanged(afterDrawing: Boolean = false): Boolean {
        if (!changed && !afterDrawing) return false
        changed = false
        val described = describe()
        if (described == last) return false
        last = described
        push(described)
        return true
    }
}
