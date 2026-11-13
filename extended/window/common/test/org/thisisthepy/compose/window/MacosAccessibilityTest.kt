package org.thisisthepy.compose.window

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacosAccessibilityTest {
    private fun element(
        role: Int,
        label: String = "",
        id: Int = -1,
        parentId: Int = -1,
        value: String? = null,
        states: Int = AccessibleState.ENABLED,
        actions: Int = 0,
        rangeValue: Float? = null,
    ) = AccessibleElement(
        role, 0f, 0f, 10f, 10f, label,
        id = id, parentId = parentId, value = value, states = states, actions = actions, rangeValue = rangeValue,
    )

    @Test
    fun nfr8_a_button_is_an_ax_button_that_answers_press() {
        val ax = macosAxElement(element(ElementRole.BUTTON, "OK", actions = AccessibleAction.PRESS))
        assertEquals(MacosAx.BUTTON, ax.role)
        assertNull(ax.subrole)
        assertEquals(listOf(MacosAx.PRESS), ax.actions)
    }

    @Test
    fun nfr8_a_field_is_an_ax_text_field_whose_value_is_its_text_and_settable() {
        val ax = macosAxElement(
            element(
                ElementRole.FIELD, "Name", value = "Ada",
                states = AccessibleState.ENABLED or AccessibleState.EDITABLE or AccessibleState.FOCUSABLE,
                actions = AccessibleAction.SET_VALUE or AccessibleAction.FOCUS,
            ),
        )
        assertEquals(MacosAx.TEXT_FIELD, ax.role)
        assertEquals(MacosAxValue.Text("Ada"), ax.value)
        assertTrue(ax.valueSettable)
        assertTrue(ax.focusSettable)
    }

    @Test
    fun nfr8_a_password_field_is_secure_and_its_text_is_never_read_out() {
        val ax = macosAxElement(
            element(ElementRole.FIELD, "Password", value = "hunter2", states = AccessibleState.ENABLED or AccessibleState.PASSWORD),
        )
        assertEquals(MacosAx.SECURE_TEXT_FIELD_SUBROLE, ax.subrole)
        assertEquals(MacosAxValue.Text(""), ax.value)
    }

    @Test
    fun a_multi_line_field_is_a_text_area_and_a_read_only_one_cannot_be_set() {
        val ax = macosAxElement(
            element(
                ElementRole.FIELD, states = AccessibleState.ENABLED or AccessibleState.MULTI_LINE or AccessibleState.READ_ONLY,
                actions = AccessibleAction.SET_VALUE,
            ),
        )
        assertEquals(MacosAx.TEXT_AREA, ax.role)
        assertFalse(ax.valueSettable)
    }

    @Test
    fun a_checkable_control_reports_zero_one_or_two_as_appkit_checkboxes_do() {
        val base = AccessibleState.ENABLED or AccessibleState.CHECKABLE
        assertEquals(MacosAxValue.Number(0.0), macosAxValue(element(ElementRole.CHECKBOX, states = base)))
        assertEquals(MacosAxValue.Number(1.0), macosAxValue(element(ElementRole.CHECKBOX, states = base or AccessibleState.CHECKED)))
        assertEquals(MacosAxValue.Number(2.0), macosAxValue(element(ElementRole.CHECKBOX, states = base or AccessibleState.MIXED)))
        assertNull(macosAxValue(element(ElementRole.BUTTON)))
    }

    @Test
    fun roles_with_no_role_of_their_own_take_a_subrole() {
        assertEquals(MacosAx.CHECK_BOX to MacosAx.SWITCH_SUBROLE, macosAxElement(element(ElementRole.SWITCH)).let { it.role to it.subrole })
        assertEquals(MacosAx.RADIO_BUTTON to MacosAx.TAB_BUTTON_SUBROLE, macosAxElement(element(ElementRole.TAB)).let { it.role to it.subrole })
        assertEquals(MacosAx.HEADING, macosAxElement(element(ElementRole.HEADING)).role)
        assertEquals(MacosAx.LINK, macosAxElement(element(ElementRole.LINK)).role)
        assertEquals(MacosAx.GROUP, macosAxElement(element(99)).role)
    }

    @Test
    fun a_slider_answers_increment_and_decrement_and_reports_where_it_is() {
        val ax = macosAxElement(
            element(ElementRole.SLIDER, "Volume", rangeValue = 0.5f, actions = AccessibleAction.INCREMENT or AccessibleAction.DECREMENT),
        )
        assertEquals(MacosAx.SLIDER, ax.role)
        assertEquals(MacosAxValue.Number(0.5), ax.value)
        assertEquals(listOf(MacosAx.INCREMENT, MacosAx.DECREMENT), ax.actions)
    }

    @Test
    fun elements_nest_under_the_parent_they_name_and_an_unknown_parent_puts_them_in_the_window() {
        val elements = listOf(
            element(ElementRole.GROUP, "Form", id = 1),
            element(ElementRole.FIELD, "Name", id = 2, parentId = 1),
            element(ElementRole.BUTTON, "OK", id = 3, parentId = 1),
            element(ElementRole.TEXT, "Lost", id = 4, parentId = 99),
        )
        assertContentEquals(intArrayOf(-1, 0, 0, -1), macosAxParents(elements))
    }

    @Test
    fun a_loop_of_parents_is_broken_so_no_element_is_out_of_reach() {
        val elements = listOf(
            element(ElementRole.GROUP, id = 1, parentId = 2),
            element(ElementRole.GROUP, id = 2, parentId = 1),
        )
        val parents = macosAxParents(elements)
        assertTrue(parents.any { it == -1 }, "one of them is in the window: ${parents.toList()}")
    }

    @Test
    fun an_element_without_an_id_is_keyed_by_its_place_below_every_id() {
        assertEquals(7, macosAxKey(element(ElementRole.BUTTON, id = 7), 0))
        assertEquals(-2, macosAxKey(element(ElementRole.BUTTON), 0))
        assertEquals(-5, macosAxKey(element(ElementRole.BUTTON), 3))
    }

    @Test
    fun the_focused_element_is_the_one_the_scene_marked() {
        val elements = listOf(
            element(ElementRole.BUTTON, id = 1),
            element(ElementRole.FIELD, id = 2, states = AccessibleState.ENABLED or AccessibleState.FOCUSED),
        )
        assertEquals(1, macosAxFocused(elements))
        assertEquals(-1, macosAxFocused(elements.take(1)))
    }

    @Test
    fun the_new_roles_and_states_are_added_after_the_old_ones_without_moving_them() {
        assertEquals(13, ElementRole.LINK)
        assertEquals(
            listOf(14, 15, 16, 17, 18, 19, 20, 21),
            listOf(
                ElementRole.TAB_GROUP, ElementRole.OUTLINE, ElementRole.OUTLINE_ROW, ElementRole.MENU,
                ElementRole.MENU_ITEM, ElementRole.LIST, ElementRole.LIST_ITEM, ElementRole.NAVIGATION,
            ),
        )
        assertEquals(1 shl 10, AccessibleState.MULTI_LINE)
        assertEquals(1 shl 11, AccessibleState.EXPANDABLE)
        assertEquals(1 shl 12, AccessibleState.EXPANDED)
        assertEquals(1 shl 7, AccessibleAction.SHOW_MENU)
        assertEquals(1 shl 8, AccessibleAction.EXPAND)
        assertEquals(1 shl 9, AccessibleAction.COLLAPSE)
    }

    @Test
    fun containers_and_their_items_take_appkits_roles() {
        val expected = mapOf(
            ElementRole.TAB_GROUP to (MacosAx.TAB_GROUP to null),
            ElementRole.OUTLINE to (MacosAx.OUTLINE to null),
            ElementRole.OUTLINE_ROW to (MacosAx.ROW to MacosAx.OUTLINE_ROW_SUBROLE),
            ElementRole.MENU to (MacosAx.MENU to null),
            ElementRole.MENU_ITEM to (MacosAx.MENU_ITEM to null),
            ElementRole.LIST to (MacosAx.LIST to null),
            ElementRole.LIST_ITEM to (MacosAx.GROUP to null),
            ElementRole.NAVIGATION to (MacosAx.GROUP to MacosAx.LANDMARK_NAVIGATION_SUBROLE),
        )
        for ((role, ax) in expected) {
            assertEquals(ax, macosAxElement(element(role)).let { it.role to it.subrole }, "role $role")
        }
        assertEquals("AXTabGroup", MacosAx.TAB_GROUP)
        assertEquals("AXOutlineRow", MacosAx.OUTLINE_ROW_SUBROLE)
        assertEquals("AXLandmarkNavigation", MacosAx.LANDMARK_NAVIGATION_SUBROLE)
    }

    @Test
    fun an_expandable_control_reports_ax_expanded_and_setting_it_asks_for_expand_or_collapse() {
        val closed = element(
            ElementRole.OUTLINE_ROW,
            states = AccessibleState.ENABLED or AccessibleState.EXPANDABLE,
            actions = AccessibleAction.EXPAND,
        )
        val open = closed.copy(
            states = closed.states or AccessibleState.EXPANDED,
            actions = AccessibleAction.COLLAPSE,
        )
        assertEquals(false, macosAxElement(closed).expanded)
        assertEquals(true, macosAxElement(open).expanded)
        assertNull(macosAxElement(element(ElementRole.BUTTON, states = AccessibleState.ENABLED or AccessibleState.EXPANDED)).expanded)

        assertEquals(AccessibleAction.EXPAND, macosAxExpandAction(closed, true))
        assertNull(macosAxExpandAction(closed, false), "already closed")
        assertEquals(AccessibleAction.COLLAPSE, macosAxExpandAction(open, false))
        assertNull(macosAxExpandAction(open.copy(actions = 0), false), "offers no collapse")
    }
}
