#ifndef DXC_WIN32_EDIT_MENU_H
#define DXC_WIN32_EDIT_MENU_H

// What the window's own text edit menu holds, and which keys ask for it.
//
// The menu is the system's popup, put up by the window on a right click and on the Menu key
// or Shift and F10, while the application says a text field has focus
// (dxc_native_set_edit_menu). The choice comes back as the shortcut the entry is named
// after, Control and X, C, V or A, so the field gets it the way it gets the keyboard and the
// editor stays the application's.
//
// Kept apart from the window because which entries can be chosen is a rule on a few bits,
// so a test checks it without a window, and needs no Windows header to.

#include <stddef.h>
#include <stdint.h>

// The flags dxc_native_set_edit_menu takes. Without ON the window puts up no menu, which is
// how it starts. KNOWN says the application described the field: without it Cut, Copy and
// Select All are offered as the macOS window offers them, because only the field knows what
// it has selected and a Copy with nothing selected does nothing.
#define DXC_EDIT_MENU_ON 1
#define DXC_EDIT_MENU_KNOWN 2
#define DXC_EDIT_MENU_SELECTION 4
#define DXC_EDIT_MENU_EDITABLE 8
#define DXC_EDIT_MENU_HAS_TEXT 16

// The entries, numbered as common's EditMenuId numbers them. These are the menu's command
// ids too, so a dismissed menu (TrackPopupMenuEx answers 0) is none of them.
#define DXC_EDIT_CUT 1
#define DXC_EDIT_COPY 2
#define DXC_EDIT_PASTE 3
#define DXC_EDIT_SELECT_ALL 4
#define DXC_EDIT_ENTRY_COUNT 4

struct dxc_edit_entry {
    int32_t id;
    int32_t enabled;
    // A line above it: Select All stands apart from the three that use the clipboard.
    int32_t separator_before;
};

/**
 * The entries the menu shows for [flags], in the order Windows applications put them, and
 * how many: four, or none when the menu is off. An entry that cannot run is shown greyed, as
 * Windows shows it, not left out. Paste needs text on the clipboard, which the window asks
 * the system about each time ([clipboard_has_text]).
 */
static inline int32_t dxc_edit_menu_entries(int32_t flags, int32_t clipboard_has_text,
                                            struct dxc_edit_entry out[DXC_EDIT_ENTRY_COUNT]) {
    if ((flags & DXC_EDIT_MENU_ON) == 0) return 0;
    int32_t known = (flags & DXC_EDIT_MENU_KNOWN) != 0;
    int32_t selection = (flags & DXC_EDIT_MENU_SELECTION) != 0;
    int32_t editable = (flags & DXC_EDIT_MENU_EDITABLE) != 0;
    int32_t text = (flags & DXC_EDIT_MENU_HAS_TEXT) != 0;
    out[0].id = DXC_EDIT_CUT;
    out[0].enabled = known ? (selection && editable) : 1;
    out[0].separator_before = 0;
    out[1].id = DXC_EDIT_COPY;
    out[1].enabled = known ? selection : 1;
    out[1].separator_before = 0;
    out[2].id = DXC_EDIT_PASTE;
    out[2].enabled = clipboard_has_text != 0 && (known ? editable : 1);
    out[2].separator_before = 0;
    out[3].id = DXC_EDIT_SELECT_ALL;
    out[3].enabled = known ? text : 1;
    out[3].separator_before = 1;
    return DXC_EDIT_ENTRY_COUNT;
}

/**
 * The key an entry presses with Control: its virtual-key code, which for a letter is the
 * capital letter. 0 for anything that is not an entry, a dismissed menu among them.
 */
static inline int32_t dxc_edit_menu_key(int32_t id) {
    switch (id) {
    case DXC_EDIT_CUT: return 'X';
    case DXC_EDIT_COPY: return 'C';
    case DXC_EDIT_PASTE: return 'V';
    case DXC_EDIT_SELECT_ALL: return 'A';
    default: return 0;
    }
}

// VK_APPS and VK_F10, and the bits of the window's modifier word (dxc_held_modifiers).
#define DXC_EDIT_VK_APPS 0x5D
#define DXC_EDIT_VK_F10 0x79
#define DXC_EDIT_SHIFT 1
#define DXC_EDIT_HELD (1 | 2 | 4 | 8)

/**
 * Whether a key press asks for the menu from the keyboard: the Menu key (alone, or with
 * Shift, which Windows takes as the same), or Shift and F10.
 */
static inline int32_t dxc_edit_menu_key_opens(uint32_t virtual_key, int32_t modifiers) {
    int32_t held = modifiers & DXC_EDIT_HELD;
    if (virtual_key == DXC_EDIT_VK_APPS) return held == 0 || held == DXC_EDIT_SHIFT;
    if (virtual_key == DXC_EDIT_VK_F10) return held == DXC_EDIT_SHIFT;
    return 0;
}

/**
 * The command id the system's own edit control gives the entry in its context menu, whose
 * labels are in the language of the system: WM_CUT, WM_COPY, WM_PASTE and EM_SETSEL. The
 * window reads the label from there and falls back to [dxc_edit_menu_fallback_label].
 */
static inline uint32_t dxc_edit_menu_system_command(int32_t id) {
    switch (id) {
    case DXC_EDIT_CUT: return 0x0300;
    case DXC_EDIT_COPY: return 0x0301;
    case DXC_EDIT_PASTE: return 0x0302;
    case DXC_EDIT_SELECT_ALL: return 0x00B1;
    default: return 0;
    }
}

/** The English label, with its access key, for a system that offers none. */
static inline const wchar_t *dxc_edit_menu_fallback_label(int32_t id) {
    switch (id) {
    case DXC_EDIT_CUT: return L"Cu&t";
    case DXC_EDIT_COPY: return L"&Copy";
    case DXC_EDIT_PASTE: return L"&Paste";
    case DXC_EDIT_SELECT_ALL: return L"Select &All";
    default: return L"";
    }
}

#endif
