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
import androidx.compose.ui.text.input.CommitTextCommand
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

    suspend fun run(request: PlatformTextInputMethodRequest): Nothing {
        session = request
        try {
            awaitCancellation()
        } finally {
            session = null
        }
    }

    /** Puts finished text into the focused field, the caret after it. */
    fun commit(text: String) {
        val request = session ?: return
        request.onEditCommand(listOf(CommitTextCommand(AnnotatedString(text), 1)))
    }

    /**
     * Shows text the reader is still composing. An empty string ends the composition
     * without committing anything, which is what an input method asks for when the reader
     * backs out of it.
     */
    fun compose(text: String) {
        val request = session ?: return
        request.onEditCommand(listOf(SetComposingTextCommand(AnnotatedString(text), 1)))
    }

    /** Where the focused field's caret is, in window pixels, or null where none is laid out. */
    fun caret(): CaretRect? {
        val rect = session?.focusedRectInRoot?.invoke() ?: return null
        return CaretRect(rect.left, rect.top, rect.right, rect.bottom)
    }
}
