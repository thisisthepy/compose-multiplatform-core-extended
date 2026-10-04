package androidx.compose.ui.awt

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.internal
import androidx.compose.ui.input.pointer.PointerEvent

/**
 * The original raw native event from AWT.
 *
 * Null if:
 * - The native event is sent by another framework (when Compose UI is embed into it)
 * - There is no native event (in tests, for example)
 * - The event is a synthetic move event sent by Compose on re-layout
 * - The event is a synthetic event sent by Compose to maintain a logically consistent stream of
 *   events. For example, if a native press event is received without a corresponding move event,
 *   Compose will synthesize a move event. That move event will have a null [awtEventOrNull].
 *
 * It is therefore recommended to always check for `null` when using this property.
 */
val PointerEvent.awtEventOrNull: java.awt.event.MouseEvent? get() {
    return nativeEvent.takeIf { it.isInstanceOfAwt("java.awt.event.MouseEvent") } as? java.awt.event.MouseEvent
}

/**
 * The original raw native event from AWT.
 *
 * Null if:
 * - The native event is sent by another framework (when Compose UI is embed into it)
 * - There is no native event (in tests, for example)
 *
 * It is therefore recommended to always check for `null` when using this property.
 */
val KeyEvent.awtEventOrNull: java.awt.event.KeyEvent? get() {
    return internal.nativeEvent.takeIf { it.isInstanceOfAwt("java.awt.event.KeyEvent") } as? java.awt.event.KeyEvent
}

// An event sent by an embedder is not an AWT event. Comparing class names up the superclass chain
// answers that without loading a java.awt class: the cast to java.awt.event.KeyEvent would load
// AWTEvent, whose initialiser loads the AWT native libraries.
private fun Any?.isInstanceOfAwt(className: String): Boolean {
    var type: Class<*>? = this?.javaClass
    while (type != null) {
        if (type.name == className) return true
        type = type.superclass
    }
    return false
}
