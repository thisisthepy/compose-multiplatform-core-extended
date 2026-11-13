@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package org.thisisthepy.compose.window.scene

import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.PlatformArchitectureComponentsOwner
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInput
import org.thisisthepy.compose.window.DoubleClick

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
 * Compose's defaults with the operating system's double-click interval in place of its own
 * 300 ms, which is shorter than any desktop's: a double click at the pace the user set their
 * system to would otherwise count as two single clicks. The shortest gap between the two
 * presses stays Compose's.
 */
internal class SceneViewConfiguration(
    override val doubleTapTimeoutMillis: Long,
) : ViewConfiguration by PlatformContext.DefaultViewConfiguration

/**
 * Escape as the back gesture, which is what closes a popup, a menu or a dialog in Compose.
 *
 * A key reaches it only after the scene left it alone, as on Compose's desktop window: a field
 * or a popup that does something with Escape keeps it.
 */
internal class SceneBackInput : NavigationEventInput() {
    /** Answers whether [event] was the back gesture, and if so goes back. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || event.key != Key.Escape) return false
        dispatchOnBackCompleted()
        return true
    }
}

/**
 * Compose's defaults for the lifecycle, the view models and the saved state, with a back
 * dispatcher of this window's own. The default one is shared by every scene in the process, so
 * Escape in one window would go back in whichever window registered last.
 */
internal class SceneArchitectureComponents(
    back: SceneBackInput,
    defaults: PlatformArchitectureComponentsOwner = PlatformContext.Empty().architectureComponentsOwner,
) : PlatformArchitectureComponentsOwner by defaults {
    override val navigationEventDispatcherOwner: NavigationEventDispatcherOwner =
        object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher().also { it.addInput(back) }
        }
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
    /** Hears every change to the scene's semantics, for the window's accessibility. */
    override val semanticsOwnerListener: PlatformContext.SemanticsOwnerListener? = null,
    /** The operating system's double-click interval, [org.thisisthepy.compose.window.WindowPlatform.doubleClickMillis]. */
    doubleClickMillis: Long = DoubleClick.DEFAULT_MILLIS,
    /** Where Escape goes when the scene did not take it. */
    back: SceneBackInput = SceneBackInput(),
) : PlatformContext {
    override val architectureComponentsOwner: PlatformArchitectureComponentsOwner = SceneArchitectureComponents(back)
    override val viewConfiguration: ViewConfiguration = SceneViewConfiguration(doubleClickMillis)
    override val windowInfo: WindowInfo = SceneWindowInfo(size)
    override val inputModeManager: InputModeManager = SceneInputModeManager()

    override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing =
        textInput.run(request)
}
