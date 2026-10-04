// The small window calls the shared WindowPlatform needs beyond what win32_window.c opens
// and draws: the title, the visibility, the system theme and a context menu. They take the
// window handle the open call returned, so this file holds no state of the window's own
// and needs nothing from win32_window.c but the header.

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "win32_window.h"

static wchar_t *dxc_widen_text(const char *text) {
    int units = MultiByteToWideChar(CP_UTF8, 0, text, -1, NULL, 0);
    if (units <= 0) {
        return NULL;
    }
    wchar_t *wide = (wchar_t *)calloc((size_t)units, sizeof(wchar_t));
    if (wide == NULL) {
        return NULL;
    }
    MultiByteToWideChar(CP_UTF8, 0, text, -1, wide, units);
    return wide;
}

void dxc_native_set_title(void *window_pointer, const char *title) {
    wchar_t *wide = dxc_widen_text(title);
    if (wide != NULL) {
        SetWindowTextW((HWND)window_pointer, wide);
        free(wide);
    }
}

/** True while the reader has chosen the dark theme for applications. */
int32_t dxc_native_system_dark(void) {
    DWORD value = 1;
    DWORD size = sizeof value;
    LSTATUS status = RegGetValueW(
        HKEY_CURRENT_USER,
        L"Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
        L"AppsUseLightTheme", RRF_RT_REG_DWORD, NULL, &value, &size);
    return status == ERROR_SUCCESS && value == 0;
}

static WINDOWPLACEMENT dxc_before_fullscreen = {sizeof(WINDOWPLACEMENT)};
static LONG_PTR dxc_style_before_fullscreen;
static int dxc_is_fullscreen;

static void dxc_leave_fullscreen(HWND window) {
    if (!dxc_is_fullscreen) {
        return;
    }
    dxc_is_fullscreen = 0;
    SetWindowLongPtrW(window, GWL_STYLE, dxc_style_before_fullscreen);
    SetWindowPlacement(window, &dxc_before_fullscreen);
    SetWindowPos(window, NULL, 0, 0, 0, 0,
                 SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOOWNERZORDER | SWP_FRAMECHANGED);
}

static void dxc_enter_fullscreen(HWND window) {
    if (dxc_is_fullscreen) {
        return;
    }
    MONITORINFO monitor = {sizeof(MONITORINFO)};
    if (!GetWindowPlacement(window, &dxc_before_fullscreen) ||
        !GetMonitorInfoW(MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST), &monitor)) {
        return;
    }
    dxc_style_before_fullscreen = GetWindowLongPtrW(window, GWL_STYLE);
    dxc_is_fullscreen = 1;
    SetWindowLongPtrW(window, GWL_STYLE, dxc_style_before_fullscreen & ~(LONG_PTR)WS_OVERLAPPEDWINDOW);
    SetWindowPos(window, HWND_TOP, monitor.rcMonitor.left, monitor.rcMonitor.top,
                 monitor.rcMonitor.right - monitor.rcMonitor.left,
                 monitor.rcMonitor.bottom - monitor.rcMonitor.top,
                 SWP_NOOWNERZORDER | SWP_FRAMECHANGED);
}

/** 0 hidden, 1 visible, 2 minimised, 3 full screen: the codes the Kotlin side sends. */
void dxc_native_set_visibility(void *window_pointer, int32_t code) {
    HWND window = (HWND)window_pointer;
    if (code != 3) {
        dxc_leave_fullscreen(window);
    }
    switch (code) {
    case 0:
        ShowWindow(window, SW_HIDE);
        break;
    case 1:
        ShowWindow(window, SW_RESTORE);
        ShowWindow(window, SW_SHOW);
        break;
    case 2:
        ShowWindow(window, SW_MINIMIZE);
        break;
    case 3:
        ShowWindow(window, SW_SHOW);
        dxc_enter_fullscreen(window);
        break;
    default:
        break;
    }
}

/**
 * Shows a menu at the pointer and answers with the id chosen, or -1.
 *
 * `packed` is one line per entry: id, enabled, separator after, label, separated by tabs.
 */
int32_t dxc_native_show_context_menu(void *window_pointer, const char *packed) {
    HWND window = (HWND)window_pointer;
    wchar_t *wide = dxc_widen_text(packed);
    if (wide == NULL) {
        return -1;
    }
    HMENU menu = CreatePopupMenu();
    if (menu == NULL) {
        free(wide);
        return -1;
    }
    wchar_t *cursor = wide;
    while (*cursor != L'\0') {
        wchar_t *line_end = wcschr(cursor, L'\n');
        if (line_end != NULL) {
            *line_end = L'\0';
        }
        wchar_t *fields[4] = {cursor, NULL, NULL, NULL};
        wchar_t *at = cursor;
        for (int field = 1; field < 4; field++) {
            wchar_t *tab = wcschr(at, L'\t');
            if (tab == NULL) {
                break;
            }
            *tab = L'\0';
            at = tab + 1;
            fields[field] = at;
        }
        if (fields[3] != NULL) {
            // Offset by one: the popup answers zero for "nothing chosen".
            UINT_PTR id = (UINT_PTR)(_wtoi(fields[0]) + 1);
            UINT flags = MF_STRING | (_wtoi(fields[1]) != 0 ? MF_ENABLED : MF_GRAYED);
            AppendMenuW(menu, flags, id, fields[3]);
            if (_wtoi(fields[2]) != 0) {
                AppendMenuW(menu, MF_SEPARATOR, 0, NULL);
            }
        }
        if (line_end == NULL) {
            break;
        }
        cursor = line_end + 1;
    }
    POINT where;
    GetCursorPos(&where);
    SetForegroundWindow(window);
    int chosen = (int)TrackPopupMenu(menu, TPM_RETURNCMD | TPM_NONOTIFY | TPM_RIGHTBUTTON,
                                     where.x, where.y, 0, window, NULL);
    DestroyMenu(menu);
    free(wide);
    return chosen == 0 ? -1 : chosen - 1;
}
