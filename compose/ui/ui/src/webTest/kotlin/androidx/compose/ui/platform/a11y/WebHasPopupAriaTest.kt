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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.OnCanvasTests
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.hasPopup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.w3c.dom.HTMLElement

class WebHasPopupAriaTest : OnCanvasTests {

    @Test
    fun hasPopupTriggerExposesAriaHaspopupInDom() = runApplicationTest {
        var triggerHasPopup by mutableStateOf(true)

        createComposeWindow {
            Column {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("menuTrigger")
                        .clickable(role = Role.Button, onClick = {})
                        .semantics { hasPopup = triggerHasPopup }
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("plainButton")
                        .clickable(role = Role.Button, onClick = {})
                )
            }
        }

        awaitA11YChanges()
        val trigger = getShadowRoot().getElementById("menuTrigger") as? HTMLElement
        val plain = getShadowRoot().getElementById("plainButton") as? HTMLElement
        assertNotNull(trigger)
        assertNotNull(plain)
        assertEquals("button", trigger.getAttribute("role"))
        assertEquals("true", trigger.getAttribute("aria-haspopup"))
        assertNull(plain.getAttribute("aria-haspopup"))

        triggerHasPopup = false
        awaitA11YChanges()
        assertNull(trigger.getAttribute("aria-haspopup"))

        triggerHasPopup = true
        awaitA11YChanges()
        assertEquals("true", trigger.getAttribute("aria-haspopup"))
    }
}
