// Puts an entry menu up as Windows' own (compose-rust FR-33.2, dxc_native_popup_menu) in a window
// of its own and saves the screen around it, for a person to look at (SPEC criterion 9: the menu
// is a Win32 popup menu). The workflow uploads the picture; nothing here judges it.
//
//   win32_popup_menu_shot.exe out\popup-menu.bmp
//
// A timer saves the screen while the menu is open and ends it with dxc_native_popup_menu_close,
// so the run never waits for a person.

#include <windows.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "win32_window.h"

static HWND window;
static const char *path;

static void save_bmp(const char *file, HDC source, int x, int y, int width, int height) {
    BITMAPINFO info;
    memset(&info, 0, sizeof info);
    info.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    info.bmiHeader.biWidth = width;
    info.bmiHeader.biHeight = -height;
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    info.bmiHeader.biCompression = BI_RGB;
    void *bits = NULL;
    HDC memory = CreateCompatibleDC(source);
    HBITMAP bitmap = CreateDIBSection(memory, &info, DIB_RGB_COLORS, &bits, NULL, 0);
    HGDIOBJ old = SelectObject(memory, bitmap);
    BitBlt(memory, 0, 0, width, height, source, x, y, SRCCOPY | CAPTUREBLT);
    FILE *out = fopen(file, "wb");
    if (out != NULL) {
        BITMAPFILEHEADER header;
        memset(&header, 0, sizeof header);
        header.bfType = 0x4D42;
        header.bfOffBits = sizeof header + sizeof(BITMAPINFOHEADER);
        header.bfSize = header.bfOffBits + (DWORD)width * height * 4;
        fwrite(&header, sizeof header, 1, out);
        fwrite(&info.bmiHeader, sizeof(BITMAPINFOHEADER), 1, out);
        fwrite(bits, (size_t)width * height * 4, 1, out);
        fclose(out);
    }
    SelectObject(memory, old);
    DeleteObject(bitmap);
    DeleteDC(memory);
}

static void CALLBACK capture(HWND unused, UINT message, UINT_PTR id, DWORD time) {
    (void)unused;
    (void)message;
    (void)time;
    KillTimer(NULL, id);
    RECT frame;
    GetWindowRect(window, &frame);
    HDC screen = GetDC(NULL);
    save_bmp(path, screen, frame.left, frame.top, 480, 360);
    ReleaseDC(NULL, screen);
    printf("saved %s\n", path);
    dxc_native_popup_menu_close();
}

int main(int argc, char **argv) {
    path = argc > 1 ? argv[1] : "popup-menu.bmp";
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    WNDCLASSW kind;
    memset(&kind, 0, sizeof kind);
    kind.lpfnWndProc = DefWindowProcW;
    kind.hInstance = GetModuleHandleW(NULL);
    kind.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
    kind.lpszClassName = L"fr33_2_popup_menu_shot";
    RegisterClassW(&kind);
    window = CreateWindowExW(0, kind.lpszClassName, L"FR-33.2 Windows path (win32_popup_menu.h)",
                             WS_OVERLAPPEDWINDOW | WS_VISIBLE, 100, 100, 480, 360, NULL, NULL, kind.hInstance, NULL);
    if (window == NULL) return 1;
    SetForegroundWindow(window);
    UpdateWindow(window);
    float scale = (float)GetDpiForWindow(window) / 96.0f;
    int px = (int)(16 * scale);
    static uint8_t icon[64 * 64 * 4];
    for (int i = 0; i < px * px; i++) {
        icon[i * 4] = 0x30;
        icon[i * 4 + 1] = 0x70;
        icon[i * 4 + 2] = 0xE0;
        icon[i * 4 + 3] = 0xFF;
    }
    int32_t sizes[] = {px, px};
    const char *lines =
        "I\t0\t1\t0\t0\tOpen...\tCtrl+O\n"
        "I\t0\t0\t0\t-1\tSave & close\tCtrl+Shift+S\n"
        "S\t0\t0\t0\t-1\t\t\n"
        "I\t0\t1\t1\t-1\tWrap lines\t\n"
        "M\t0\t1\t0\t-1\tRecent\t\n"
        "I\t1\t1\t0\t-1\ta.txt\t\n"
        "I\t1\t1\t0\t-1\tb.txt\t\n"
        "M\t0\t0\t0\t-1\tEmpty\t\n";
    SetTimer(NULL, 1, 1000, capture);
    char out[64];
    // Anchor: a 160 x 28 dip button at (20, 20) dip in the client area, in pixels.
    int32_t answered = dxc_native_popup_menu(window, lines, icon, sizes, 1, 20 * scale, 20 * scale, 160 * scale,
                                             28 * scale, 0, out, sizeof out);
    printf("answered %d\n", answered);
    DestroyWindow(window);
    return answered == 0 ? 0 : 1;
}
