#ifndef DXC_WIN32_SCHEME_H
#define DXC_WIN32_SCHEME_H

#include <stdint.h>

// The decisions behind changing an open window's resizable and color scheme (FR-53).
//
// Kept apart from the window like win32_placement.h: they are a few bits and a choice, and a
// machine with no Windows on it can compile this header and check the answers.

/** WS_THICKFRAME and WS_MAXIMIZEBOX, the two style bits a window that cannot be resized drops. */
#define DXC_SCHEME_THICKFRAME 0x00040000u
#define DXC_SCHEME_MAXIMIZEBOX 0x00010000u

/** DWMWA_USE_IMMERSIVE_DARK_MODE, and the number it had before Windows 10 2004. */
#define DXC_SCHEME_DARK_ATTRIBUTE 20u
#define DXC_SCHEME_DARK_ATTRIBUTE_OLD 19u

/** The color schemes the application can ask for. */
#define DXC_SCHEME_SYSTEM 0
#define DXC_SCHEME_LIGHT 1
#define DXC_SCHEME_DARK 2

/** The style a window has once it is or is not resizable; the other bits are kept. */
static inline uint32_t dxc_scheme_style(uint32_t style, int32_t resizable) {
    const uint32_t bits = DXC_SCHEME_THICKFRAME | DXC_SCHEME_MAXIMIZEBOX;
    return resizable ? (style | bits) : (style & ~bits);
}

/** 1 when a scheme is one of the three the call takes. */
static inline int dxc_scheme_valid(int32_t scheme) {
    return scheme == DXC_SCHEME_SYSTEM || scheme == DXC_SCHEME_LIGHT || scheme == DXC_SCHEME_DARK;
}

/** 1 when the caption is dark: the scheme says so, or follows a system that is dark. */
static inline int32_t dxc_scheme_caption_dark(int32_t scheme, int32_t system_dark) {
    if (scheme == DXC_SCHEME_DARK) return 1;
    if (scheme == DXC_SCHEME_LIGHT) return 0;
    return system_dark ? 1 : 0;
}

/** 1 when a WM_SETTINGCHANGE with this area (narrow, may be NULL) is the apps theme changing. */
static inline int dxc_scheme_is_theme_change(const uint16_t *area) {
    static const char name[] = "ImmersiveColorSet";
    if (area == 0) return 0;
    for (int i = 0; name[i] != 0; i++) {
        if (area[i] != (uint16_t)(unsigned char)name[i]) return 0;
    }
    return area[sizeof name - 1] == 0;
}

#endif
