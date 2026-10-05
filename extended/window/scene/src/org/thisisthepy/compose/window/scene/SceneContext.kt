@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package org.thisisthepy.compose.window.scene

import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.IntSize

/**
 * The window a scene is in, as far as Compose needs to know.
 *
 * Focus is reported as held, because a window that never says so leaves a text field
 * drawing no caret: the field is told the window it sits in is not the one being typed at.
 */
internal class SceneWindowInfo(private val size: () -> IntSize) : WindowInfo {
    override val isWindowFocused: Boolean get() = true
    override val containerSize: IntSize get() = size()
}

/** Pointer or keyboard, which decides whether focus is drawn. */
internal class SceneInputModeManager : InputModeManager {
    override val inputMode: InputMode get() = InputMode.Keyboard

    override fun requestInputMode(inputMode: InputMode): Boolean =
        inputMode == InputMode.Keyboard || inputMode == InputMode.Touch
}

/**
 * The platform, as the scene sees it, answered without a toolkit.
 *
 * A field asks to be typed into through `startInputMethod`, and until something answers
 * that, a field can be focused and clicked and stay empty however much is typed at it. Key
 * events are not how text arrives in Compose; an input session is. Everything not
 * overridden has a default that suits a window we own.
 */
internal class ScenePlatformContext(
    size: () -> IntSize,
    private val textInput: SceneTextInput,
) : PlatformContext {
    override val windowInfo: WindowInfo = SceneWindowInfo(size)
    override val inputModeManager: InputModeManager = SceneInputModeManager()

    override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing =
        textInput.run(request)
}
