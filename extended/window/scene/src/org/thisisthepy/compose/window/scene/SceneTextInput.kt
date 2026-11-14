// Putting text into a field that an input method is composing into is not something
// Compose offers a settled way to do: the commands that do it are marked as still moving.
// They are used here and nowhere else, and pinned to Compose 1.11.1, so a version that
// changes them fails this file rather than quietly typing nothing.
@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package org.thisisthepy.compose.window.scene

import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.FinishComposingTextCommand
import androidx.compose.ui.text.input.SetComposingTextCommand
import kotlinx.coroutines.awaitCancellation
import org.thisisthepy.compose.window.CaretRect

/**
 * The session a focused field opens to be typed into.
 *
 * Held for as long as the field is focused. `startInputMethod` is suspended for the life
 * of the session and returns when it is cancelled, which is Compose's way of saying the
 * field has gone away; what is kept here is torn down at that moment so that a later
 * keystroke is not delivered into a field nobody is looking at.
 */
class SceneTextInput {

    private var session: PlatformTextInputMethodRequest? = null

    /** True where some field is waiting to be typed into. */
    val isActive: Boolean get() = session != null

    /** True while the focused field holds marked text an input method has not finished. */
    var isComposing: Boolean = false
        private set

    suspend fun run(request: PlatformTextInputMethodRequest): Nothing {
        session = request
        try {
            awaitCancellation()
        } finally {
            session = null
            isComposing = false
        }
    }

    /** Puts finished text into the focused field, the caret after it. */
    fun commit(text: String) {
        val request = session ?: return
        isComposing = false
        request.onEditCommand(listOf(CommitTextCommand(AnnotatedString(text), 1)))
    }

    /**
     * Shows text the reader is still composing. An empty string ends the composition
     * without committing anything, which is what an input method asks for when the reader
     * backs out of it.
     *
     * An empty string with nothing being composed is dropped. The command behind it replaces
     * the selection where there is no marked text, so an input method saying "done" after the
     * composition was already finished would delete whatever the reader had selected.
     */
    fun compose(text: String) {
        val request = session ?: return
        if (text.isEmpty() && !isComposing) return
        isComposing = text.isNotEmpty()
        request.onEditCommand(listOf(SetComposingTextCommand(AnnotatedString(text), 1)))
    }

    /**
     * Keeps what is being composed as finished text, where it stands, and answers whether
     * there was anything to keep.
     *
     * For a key that moves the caret while a syllable is still marked. Compose moves the
     * selection and leaves the marked range behind, so the input method's next text would
     * replace that range and pull the caret back to it: typed text landing at the old caret.
     * Finished first, the syllable stays where it was composed and the next text goes in at
     * the caret the key moved to.
     */
    fun finishComposition(): Boolean {
        val request = session ?: return false
        if (!isComposing) return false
        isComposing = false
        request.onEditCommand(listOf(FinishComposingTextCommand()))
        return true
    }

    /** What the focused field has selected (collapsed where only the caret is), or null with no field focused. */
    fun selection(): TextRange? = session?.state?.selection

    /** Where the focused field's caret is, in window pixels, or null where none is laid out. */
    fun caret(): CaretRect? {
        val rect = session?.focusedRectInRoot?.invoke() ?: return null
        return CaretRect(rect.left, rect.top, rect.right, rect.bottom)
    }
}
