// What UI Automation is told about one element, decided without Windows.
//
// The window's UI Automation providers (win32_window.c) answer from the elements the scene
// pushed. Which patterns an element offers, how the flat list becomes a tree, what a request
// from a reader turns into and where an element is in pixels are decided here, so the rules
// compile and are checked anywhere, and the providers only call them.
//
// Include it after `struct dxc_element` is declared: the window declares the record itself and
// a test reads it from cinterop/include/win32_window.h, and both lay it out the same way.

#ifndef DXC_WIN32_UIA_H
#define DXC_WIN32_UIA_H

#include <stdint.h>

// AccessibleState, as the common module numbers its bits.
enum {
    DXC_STATE_ENABLED = 1 << 0,
    DXC_STATE_FOCUSABLE = 1 << 1,
    DXC_STATE_FOCUSED = 1 << 2,
    DXC_STATE_CHECKABLE = 1 << 3,
    DXC_STATE_CHECKED = 1 << 4,
    DXC_STATE_MIXED = 1 << 5,
    DXC_STATE_SELECTED = 1 << 6,
    DXC_STATE_EDITABLE = 1 << 7,
    DXC_STATE_READ_ONLY = 1 << 8,
    DXC_STATE_PASSWORD = 1 << 9,
    DXC_STATE_MULTI_LINE = 1 << 10,
    DXC_STATE_EXPANDABLE = 1 << 11,
    DXC_STATE_EXPANDED = 1 << 12,
};

// AccessibleAction, as the common module numbers its bits. A request names one of them.
enum {
    DXC_ACTION_PRESS = 1 << 0,
    DXC_ACTION_FOCUS = 1 << 1,
    DXC_ACTION_SET_VALUE = 1 << 2,
    DXC_ACTION_INCREMENT = 1 << 3,
    DXC_ACTION_DECREMENT = 1 << 4,
    DXC_ACTION_SCROLL_FORWARD = 1 << 5,
    DXC_ACTION_SCROLL_BACKWARD = 1 << 6,
    DXC_ACTION_SHOW_MENU = 1 << 7,
    DXC_ACTION_EXPAND = 1 << 8,
    DXC_ACTION_COLLAPSE = 1 << 9,
};

// The control patterns an element can offer, as bits of dxc_uia_patterns.
enum {
    DXC_UIA_INVOKE = 1 << 0,
    DXC_UIA_TOGGLE = 1 << 1,
    DXC_UIA_VALUE = 1 << 2,
    DXC_UIA_RANGE_VALUE = 1 << 3,
    DXC_UIA_SELECTION_ITEM = 1 << 4,
    DXC_UIA_EXPAND_COLLAPSE = 1 << 5,
};

// UIA's ToggleState and ExpandCollapseState numbers, so the rules below need no SDK header.
enum {
    DXC_UIA_TOGGLE_OFF = 0,
    DXC_UIA_TOGGLE_ON = 1,
    DXC_UIA_TOGGLE_INDETERMINATE = 2,
};
enum {
    DXC_UIA_COLLAPSED = 0,
    DXC_UIA_EXPANDED = 1,
    DXC_UIA_LEAF = 3,
};

/**
 * The patterns [el] offers.
 *
 * A control that can be checked is toggled, not invoked: a reader that finds both says
 * "invoke" for a checkbox, and Narrator reads a checkbox's state from Toggle. A ranged control
 * is a range and not a text value, and anything with a value, an editable text or a way to set
 * one is a value. Something chosen from a set (selected now, or a tab, a radio button, a list
 * item or a tree row) is a selection item, and something that opens and closes says so.
 */
static inline int32_t dxc_uia_patterns(const struct dxc_element *el) {
    int32_t patterns = 0;
    if (el->states & DXC_STATE_CHECKABLE) {
        patterns |= DXC_UIA_TOGGLE;
    } else if (el->actions & DXC_ACTION_PRESS) {
        patterns |= DXC_UIA_INVOKE;
    }
    if (el->flags & DXC_ELEMENT_HAS_RANGE) {
        patterns |= DXC_UIA_RANGE_VALUE;
    } else if ((el->flags & DXC_ELEMENT_HAS_VALUE) || (el->actions & DXC_ACTION_SET_VALUE) ||
               (el->states & (DXC_STATE_EDITABLE | DXC_STATE_READ_ONLY))) {
        patterns |= DXC_UIA_VALUE;
    }
    if ((el->states & DXC_STATE_SELECTED) || el->role == DXC_ROLE_TAB ||
        el->role == DXC_ROLE_RADIO_BUTTON || el->role == DXC_ROLE_LIST_ITEM ||
        el->role == DXC_ROLE_OUTLINE_ROW) {
        patterns |= DXC_UIA_SELECTION_ITEM;
    }
    if (el->states & DXC_STATE_EXPANDABLE) patterns |= DXC_UIA_EXPAND_COLLAPSE;
    return patterns;
}

/** Toggle's state: mixed wins over checked, and neither is off. */
static inline int32_t dxc_uia_toggle_state(int32_t states) {
    if (states & DXC_STATE_MIXED) return DXC_UIA_TOGGLE_INDETERMINATE;
    if (states & DXC_STATE_CHECKED) return DXC_UIA_TOGGLE_ON;
    return DXC_UIA_TOGGLE_OFF;
}

/** ExpandCollapse's state; an element that does not open and close is a leaf. */
static inline int32_t dxc_uia_expand_state(int32_t states) {
    if (!(states & DXC_STATE_EXPANDABLE)) return DXC_UIA_LEAF;
    return (states & DXC_STATE_EXPANDED) ? DXC_UIA_EXPANDED : DXC_UIA_COLLAPSED;
}

/**
 * The action a reader's Expand ([expand] 1) or Collapse (0) becomes, or 0 when the element
 * cannot do it. The element's own expand or collapse where it offers one; otherwise a press,
 * which is how a disclosure opens and closes, when it would change the state rather than
 * flip it back.
 */
static inline int32_t dxc_uia_expand_action(const struct dxc_element *el, int expand) {
    if (!(el->states & DXC_STATE_EXPANDABLE)) return 0;
    int32_t own = expand ? DXC_ACTION_EXPAND : DXC_ACTION_COLLAPSE;
    if (el->actions & own) return own;
    int open = (el->states & DXC_STATE_EXPANDED) != 0;
    if (open == (expand != 0)) return 0;
    return (el->actions & DXC_ACTION_PRESS) ? DXC_ACTION_PRESS : 0;
}

/** True when a reader may change the value: the element offers to set it and is enabled. */
static inline int dxc_uia_value_settable(const struct dxc_element *el) {
    return (el->actions & DXC_ACTION_SET_VALUE) && (el->states & DXC_STATE_ENABLED) &&
           !(el->states & DXC_STATE_READ_ONLY);
}

/**
 * The second half of an element's runtime id, which UIA keeps to know an element again.
 * The scene's id where it gave one, so a control is the same element across pushes and a
 * reader's cursor stays on it; otherwise the position in this push, in a range no scene id
 * reaches. Never zero, which UIA does not accept.
 */
static inline int32_t dxc_uia_runtime_key(const struct dxc_element *el, int32_t index) {
    if (el->id >= 0 && el->id < 0x3fffffff) return el->id + 1;
    return 0x40000000 + index;
}

/** Where each element is in the tree the list describes, by index; -1 for none. */
struct dxc_uia_links {
    int32_t parent;
    int32_t first_child;
    int32_t last_child;
    int32_t next;
    int32_t previous;
};

/**
 * Turns the list into a tree. The scene lists elements in the order it lays them out, a
 * parent before its children, and each names its parent's id. An element whose parent is
 * not in the list (or that names none) is directly in the window. Siblings keep the order of
 * the list, which is the reading order.
 */
static inline void dxc_uia_link(const struct dxc_element *elements, int32_t count,
                                struct dxc_uia_links *out) {
    for (int32_t i = 0; i < count; i++) {
        out[i].parent = -1;
        out[i].first_child = -1;
        out[i].last_child = -1;
        out[i].next = -1;
        out[i].previous = -1;
    }
    int32_t root_last = -1;
    for (int32_t i = 0; i < count; i++) {
        int32_t parent = -1;
        int32_t wanted = elements[i].parent_id;
        if (wanted >= 0) {
            // A parent comes before its children, usually just before, so the search walks back.
            for (int32_t j = i - 1; j >= 0; j--) {
                if (elements[j].id == wanted) {
                    parent = j;
                    break;
                }
            }
        }
        out[i].parent = parent;
        int32_t previous = parent >= 0 ? out[parent].last_child : root_last;
        out[i].previous = previous;
        if (previous >= 0) out[previous].next = i;
        if (parent >= 0) {
            if (out[parent].first_child < 0) out[parent].first_child = i;
            out[parent].last_child = i;
        } else {
            root_last = i;
        }
    }
}

/** The first element directly in the window, or -1. */
static inline int32_t dxc_uia_first_root(const struct dxc_uia_links *links, int32_t count) {
    for (int32_t i = 0; i < count; i++) {
        if (links[i].parent < 0) return i;
    }
    return -1;
}

/** The last element directly in the window, or -1. */
static inline int32_t dxc_uia_last_root(const struct dxc_uia_links *links, int32_t count) {
    for (int32_t i = count - 1; i >= 0; i--) {
        if (links[i].parent < 0) return i;
    }
    return -1;
}

/** The element that has input focus (the last one that says so), or -1. */
static inline int32_t dxc_uia_focused(const struct dxc_element *elements, int32_t count) {
    for (int32_t i = count - 1; i >= 0; i--) {
        if (elements[i].states & DXC_STATE_FOCUSED) return i;
    }
    return -1;
}

/**
 * The topmost element at a point in window points, or -1: the last in the list, because the
 * list is in drawing order and a child comes after the element it is inside.
 */
static inline int32_t dxc_uia_hit(const struct dxc_element *elements, int32_t count, float x, float y) {
    for (int32_t i = count - 1; i >= 0; i--) {
        const struct dxc_element *el = &elements[i];
        if (x >= el->x && x < el->x + el->width && y >= el->y && y < el->y + el->height) return i;
    }
    return -1;
}

#endif
