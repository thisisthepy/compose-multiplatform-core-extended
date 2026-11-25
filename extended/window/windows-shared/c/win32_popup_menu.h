#ifndef DXC_WIN32_POPUP_MENU_H
#define DXC_WIN32_POPUP_MENU_H

// Entry menus as Windows' own (compose-rust FR-33.2): a Win32 popup menu built from the lines
// common's encodePopupMenu writes, one per entry, depth first, fields separated by a tab:
// `kind depth enabled checked icon label shortcut`. `I` is an item, `S` a separator, `M` a
// submenu whose children follow one level deeper; `icon` indexes the pictures handed over with
// the lines, -1 for none.
//
// Kept apart from the window so the shared test can build a menu and read it back without
// opening one. The window procedure puts it up with TrackPopupMenuEx (dxc_native_popup_menu).
//
// A shortcut is text after a tab, which Windows lines up at the menu's trailing edge itself; it
// is never an accelerator, since the application handles the key. An ampersand in a label is
// doubled so it is shown rather than read as a mnemonic. Pictures are 32-bit premultiplied
// bitmaps, which menus draw with their alpha.

#include <windows.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define DXC_POPUP_MAX_ITEMS 1024
#define DXC_POPUP_MAX_DEPTH 16
#define DXC_POPUP_PATH_BYTES 64
#define DXC_POPUP_TEXT_CHARS 512

/** A built menu: its root, the path of each item by its command id (1 to count), its pictures. */
struct dxc_popup_menu {
    HMENU root;
    int32_t count;
    char paths[DXC_POPUP_MAX_ITEMS + 1][DXC_POPUP_PATH_BYTES];
    HBITMAP bitmaps[DXC_POPUP_MAX_ITEMS];
    int32_t bitmap_count;
};

/** [width] by [height] premultiplied RGBA pixels as a top-down 32-bit BGRA bitmap, or NULL. */
static inline HBITMAP dxc_popup_bitmap(const uint8_t *rgba, int32_t width, int32_t height) {
    BITMAPINFO info;
    memset(&info, 0, sizeof info);
    info.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    info.bmiHeader.biWidth = width;
    info.bmiHeader.biHeight = -height;
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    info.bmiHeader.biCompression = BI_RGB;
    void *bits = NULL;
    HBITMAP bitmap = CreateDIBSection(NULL, &info, DIB_RGB_COLORS, &bits, NULL, 0);
    if (bitmap == NULL || bits == NULL) {
        if (bitmap != NULL) DeleteObject(bitmap);
        return NULL;
    }
    uint8_t *out = (uint8_t *)bits;
    for (int64_t index = 0; index < (int64_t)width * height; index++) {
        out[index * 4 + 0] = rgba[index * 4 + 2];
        out[index * 4 + 1] = rgba[index * 4 + 1];
        out[index * 4 + 2] = rgba[index * 4 + 0];
        out[index * 4 + 3] = rgba[index * 4 + 3];
    }
    return bitmap;
}

/** [label], a tab and [shortcut] (none when empty), UTF-8 to UTF-16 with every '&' doubled. */
static inline void dxc_popup_text(const char *label, const char *shortcut, WCHAR *out, int capacity) {
    char joined[DXC_POPUP_TEXT_CHARS * 2];
    snprintf(joined, sizeof joined, shortcut[0] != '\0' ? "%s\t%s" : "%s", label, shortcut);
    WCHAR wide[DXC_POPUP_TEXT_CHARS];
    if (MultiByteToWideChar(CP_UTF8, 0, joined, -1, wide, DXC_POPUP_TEXT_CHARS) == 0) wide[0] = 0;
    int at = 0;
    for (int index = 0; wide[index] != 0 && at < capacity - 2; index++) {
        if (wide[index] == L'&') out[at++] = L'&';
        out[at++] = wide[index];
    }
    out[at] = 0;
}

/** Releases what [dxc_popup_menu_build] made: the menus and the pictures. NULL is fine. */
static inline void dxc_popup_menu_free(struct dxc_popup_menu *menu) {
    if (menu == NULL) return;
    if (menu->root != NULL) DestroyMenu(menu->root);
    for (int32_t index = 0; index < menu->bitmap_count; index++) DeleteObject(menu->bitmaps[index]);
    free(menu);
}

/**
 * The menu for [lines]. [icons] holds the pictures one after the other and [sizes] their width
 * and height in pairs. NULL where no menu could be made. Lines that are not seven fields, or that
 * skip a level, are passed over.
 */
static inline struct dxc_popup_menu *dxc_popup_menu_build(
    const char *lines, const uint8_t *icons, const int32_t *sizes, int32_t icon_count
) {
    struct dxc_popup_menu *menu = (struct dxc_popup_menu *)calloc(1, sizeof *menu);
    if (menu == NULL) return NULL;
    menu->root = CreatePopupMenu();
    if (menu->root == NULL) {
        free(menu);
        return NULL;
    }
    HMENU menus[DXC_POPUP_MAX_DEPTH];
    int32_t counts[DXC_POPUP_MAX_DEPTH];
    int32_t prefix[DXC_POPUP_MAX_DEPTH];
    int32_t levels = 1;
    menus[0] = menu->root;
    counts[0] = 0;
    const char *line = lines;
    while (line != NULL && *line != '\0') {
        const char *end = strchr(line, '\n');
        size_t length = end != NULL ? (size_t)(end - line) : strlen(line);
        char *copy = (char *)malloc(length + 1);
        if (copy == NULL) break;
        memcpy(copy, line, length);
        copy[length] = 0;
        char *fields[7];
        int count = 0;
        char *at = copy;
        fields[count++] = at;
        while (count < 7 && (at = strchr(at, '\t')) != NULL) {
            *at++ = 0;
            fields[count++] = at;
        }
        int32_t depth = count == 7 ? atoi(fields[1]) : -1;
        if (depth >= 0 && depth < levels && depth < DXC_POPUP_MAX_DEPTH) {
            levels = depth + 1;
            int32_t index = counts[depth]++;
            HMENU into = menus[depth];
            char kind = fields[0][0];
            if (kind == 'S') {
                AppendMenuW(into, MF_SEPARATOR, 0, NULL);
            } else if (kind == 'I' || kind == 'M') {
                int enabled = atoi(fields[2]) != 0;
                WCHAR text[DXC_POPUP_TEXT_CHARS];
                dxc_popup_text(fields[5], kind == 'I' ? fields[6] : "", text, DXC_POPUP_TEXT_CHARS);
                MENUITEMINFOW item;
                memset(&item, 0, sizeof item);
                item.cbSize = sizeof item;
                item.fMask = MIIM_STRING | MIIM_STATE | MIIM_FTYPE;
                item.fType = MFT_STRING;
                item.dwTypeData = text;
                item.fState = (enabled ? MFS_ENABLED : MFS_DISABLED) | (kind == 'I' && atoi(fields[3]) != 0 ? MFS_CHECKED : 0);
                int32_t picture = atoi(fields[4]);
                if (picture >= 0 && picture < icon_count && icons != NULL && sizes != NULL &&
                    menu->bitmap_count < DXC_POPUP_MAX_ITEMS) {
                    size_t offset = 0;
                    for (int32_t before = 0; before < picture; before++) {
                        offset += (size_t)sizes[before * 2] * (size_t)sizes[before * 2 + 1] * 4;
                    }
                    HBITMAP bitmap = dxc_popup_bitmap(icons + offset, sizes[picture * 2], sizes[picture * 2 + 1]);
                    if (bitmap != NULL) {
                        menu->bitmaps[menu->bitmap_count++] = bitmap;
                        item.fMask |= MIIM_BITMAP;
                        item.hbmpItem = bitmap;
                    }
                }
                if (kind == 'I' && menu->count < DXC_POPUP_MAX_ITEMS) {
                    int32_t id = ++menu->count;
                    char *path = menu->paths[id];
                    int written = 0;
                    for (int32_t level = 0; level < depth; level++) {
                        written += snprintf(path + written, DXC_POPUP_PATH_BYTES - written, "%d/", prefix[level]);
                    }
                    snprintf(path + written, DXC_POPUP_PATH_BYTES - written, "%d", index);
                    item.fMask |= MIIM_ID;
                    item.wID = (UINT)id;
                }
                if (kind == 'M' && enabled && depth + 1 < DXC_POPUP_MAX_DEPTH) {
                    HMENU children = CreatePopupMenu();
                    if (children != NULL) {
                        item.fMask |= MIIM_SUBMENU;
                        item.hSubMenu = children;
                        menus[depth + 1] = children;
                        counts[depth + 1] = 0;
                        prefix[depth] = index;
                        levels = depth + 2;
                    }
                }
                InsertMenuItemW(into, (UINT)GetMenuItemCount(into), TRUE, &item);
            }
        }
        free(copy);
        if (end == NULL) break;
        line = end + 1;
    }
    return menu;
}

/**
 * [menu] as common's describePopupMenu writes entries, read back from the Win32 menu with
 * GetMenuItemInfoW, appended to [out] (at most [capacity] bytes, always ended). For the check
 * that the menu holds the entries as the renderer sent them.
 */
static inline void dxc_popup_menu_describe(HMENU menu, int depth, char *out, size_t capacity) {
    int items = GetMenuItemCount(menu);
    for (int index = 0; index < items; index++) {
        size_t used = strlen(out);
        if (used + 1 >= capacity) return;
        char indent[2 * DXC_POPUP_MAX_DEPTH + 1];
        int spaces = depth * 2 < (int)sizeof indent - 1 ? depth * 2 : (int)sizeof indent - 1;
        memset(indent, ' ', (size_t)spaces);
        indent[spaces] = 0;
        WCHAR wide[DXC_POPUP_TEXT_CHARS];
        wide[0] = 0;
        MENUITEMINFOW item;
        memset(&item, 0, sizeof item);
        item.cbSize = sizeof item;
        item.fMask = MIIM_FTYPE | MIIM_STATE | MIIM_STRING | MIIM_SUBMENU | MIIM_BITMAP | MIIM_ID;
        item.dwTypeData = wide;
        item.cch = DXC_POPUP_TEXT_CHARS;
        if (!GetMenuItemInfoW(menu, (UINT)index, TRUE, &item)) continue;
        if ((item.fType & MFT_SEPARATOR) != 0) {
            snprintf(out + used, capacity - used, "%sseparator\n", indent);
            continue;
        }
        // Undo the doubled ampersands, then part the label from the shortcut at the tab.
        WCHAR plain[DXC_POPUP_TEXT_CHARS];
        int at = 0;
        for (int from = 0; wide[from] != 0 && at < DXC_POPUP_TEXT_CHARS - 1; from++) {
            if (wide[from] == L'&' && wide[from + 1] == L'&') from++;
            plain[at++] = wide[from];
        }
        plain[at] = 0;
        char text[DXC_POPUP_TEXT_CHARS * 3];
        if (WideCharToMultiByte(CP_UTF8, 0, plain, -1, text, sizeof text, NULL, NULL) == 0) text[0] = 0;
        char *tab = strchr(text, '\t');
        const char *shortcut = "";
        if (tab != NULL) {
            *tab = 0;
            shortcut = tab + 1;
        }
        const char *enabled = (item.fState & MFS_DISABLED) != 0 ? "false" : "true";
        const char *icon = item.hbmpItem != NULL ? "true" : "false";
        if (item.hSubMenu != NULL || item.wID == 0) {
            snprintf(out + used, capacity - used, "%ssubmenu \"%s\" enabled=%s icon=%s\n", indent, text, enabled, icon);
            if (item.hSubMenu != NULL) dxc_popup_menu_describe(item.hSubMenu, depth + 1, out, capacity);
        } else {
            snprintf(out + used, capacity - used, "%sitem \"%s\" shortcut=\"%s\" enabled=%s checked=%s icon=%s\n", indent,
                     text, shortcut, enabled, (item.fState & MFS_CHECKED) != 0 ? "true" : "false", icon);
        }
    }
}

#endif
