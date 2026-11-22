/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.platform.a11y

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.OnCanvasTests
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.w3c.dom.HTMLElement

class WebToggleAriaCheckedTest : OnCanvasTests {

    @Test
    fun triStateCheckboxUpdatesDomAriaChecked() = runApplicationTest {
        var state by mutableStateOf(ToggleableState.Off)

        createComposeWindow {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .testTag("triCheckbox")
                    .triStateToggleable(
                        state = state,
                        role = Role.Checkbox,
                        onClick = {
                            state = when (state) {
                                ToggleableState.Off -> ToggleableState.On
                                ToggleableState.On -> ToggleableState.Indeterminate
                                ToggleableState.Indeterminate -> ToggleableState.Off
                            }
                        }
                    )
            )
        }

        awaitA11YChanges()
        val node = getShadowRoot().getElementById("triCheckbox") as? HTMLElement
        assertNotNull(node)
        assertEquals("checkbox", node.getAttribute("role"))
        assertEquals("false", node.getAttribute("aria-checked"))

        // Off -> On
        node.click()
        awaitA11YChanges()
        assertEquals("true", node.getAttribute("aria-checked"))

        // On -> Indeterminate
        node.click()
        awaitA11YChanges()
        assertEquals("mixed", node.getAttribute("aria-checked"))

        // Indeterminate -> Off
        node.click()
        awaitA11YChanges()
        assertEquals("false", node.getAttribute("aria-checked"))
    }

    @Test
    fun switchAndRadioButtonUpdateDomAriaChecked() = runApplicationTest {
        var switchOn by mutableStateOf(false)
        var radioSelected by mutableStateOf(false)
        var isToggleable by mutableStateOf(true)

        createComposeWindow {
            Column {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("switchNode")
                        .then(
                            if (isToggleable) {
                                Modifier.toggleable(
                                    value = switchOn,
                                    role = Role.Switch,
                                    onValueChange = { switchOn = it }
                                )
                            } else {
                                Modifier
                            }
                        )
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("radioNode")
                        .selectable(
                            selected = radioSelected,
                            role = Role.RadioButton,
                            onClick = { radioSelected = !radioSelected }
                        )
                )
            }
        }

        awaitA11YChanges()
        val switchNode = getShadowRoot().getElementById("switchNode") as? HTMLElement
        val radioNode = getShadowRoot().getElementById("radioNode") as? HTMLElement
        assertNotNull(switchNode)
        assertNotNull(radioNode)
        assertEquals("switch", switchNode.getAttribute("role"))
        assertEquals("false", switchNode.getAttribute("aria-checked"))
        assertEquals("radio", radioNode.getAttribute("role"))
        assertEquals("false", radioNode.getAttribute("aria-checked"))

        switchOn = true
        radioSelected = true
        awaitA11YChanges()
        assertEquals("true", switchNode.getAttribute("aria-checked"))
        assertEquals("true", radioNode.getAttribute("aria-checked"))

        // Removing ToggleableState removes aria-checked from the DOM node.
        isToggleable = false
        awaitA11YChanges()
        assertNull(switchNode.getAttribute("aria-checked"))
    }
}
