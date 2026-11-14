package org.thisisthepy.compose.window.macos

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import org.thisisthepy.compose.window.ElementRole
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A role the application declares reaches the reader. Without it, a labelled container (a
 * dialog card, a list, a tab list) reads as text, and a reader does not go inside text, so
 * the buttons in a dialog cannot be reached.
 */
class DeclaredElementRolesTest {
    private fun labelled(label: String) = SemanticsConfiguration().apply {
        this[SemanticsProperties.ContentDescription] = listOf(label)
    }

    @Test
    fun a_labelled_container_without_a_declared_role_reads_as_text() {
        assertEquals(ElementRole.TEXT, elementRoleOf(labelled("Trust this folder?"), "Trust this folder?", declared = null))
    }

    @Test
    fun a_declared_role_wins_over_what_the_window_infers() {
        val card = labelled("Trust this folder?")
        assertEquals(ElementRole.GROUP, elementRoleOf(card, "Trust this folder?", declared = ElementRole.GROUP))
        assertEquals(ElementRole.LIST, elementRoleOf(card, "Files", declared = ElementRole.LIST))
        assertEquals(ElementRole.TAB_GROUP, elementRoleOf(card, "Open files", declared = ElementRole.TAB_GROUP))
        // A container that can also be pressed is still what was declared, not a button.
        card[SemanticsProperties.ContentDescription] = listOf("Close")
        assertEquals(ElementRole.GROUP, elementRoleOf(card, "Close", declared = ElementRole.GROUP))
    }

    @Test
    fun a_role_compose_names_wins_over_a_declared_one() {
        val button = labelled("Trust").apply { this[SemanticsProperties.Role] = Role.Button }
        assertEquals(ElementRole.BUTTON, elementRoleOf(button, "Trust", declared = ElementRole.GROUP))
    }

    @Test
    fun the_hook_is_asked_and_its_answer_used() {
        val previous = DeclaredElementRoles.of
        try {
            DeclaredElementRoles.of = { config ->
                if (config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Palette")) ElementRole.GROUP else null
            }
            val palette = labelled("Palette")
            assertEquals(ElementRole.GROUP, elementRoleOf(palette, "Palette", DeclaredElementRoles.of?.invoke(palette)))
            val note = labelled("Saved")
            assertEquals(ElementRole.TEXT, elementRoleOf(note, "Saved", DeclaredElementRoles.of?.invoke(note)))
        } finally {
            DeclaredElementRoles.of = previous
        }
    }
}
