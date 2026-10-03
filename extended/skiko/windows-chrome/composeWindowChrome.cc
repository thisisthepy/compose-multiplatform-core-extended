#ifdef _WIN32

// What a Compose window on Windows needs from the window procedure that AWT does not give
// it: the caption band taken into the client area with the frame kept whole, the live resize
// held until a frame at the new size is on screen, the executable's own icon in place of the
// toolkit's, and the process declared aware of per-monitor scaling.
//
// Compose's side is androidx.compose.ui.window.WindowsWindowChrome, which reaches this file
// through org.jetbrains.skiko.compose.WindowsWindowChrome. The class is in skiko's package
// because a GraalVM native image links skiko's JNI methods statically by that package
// prefix, and this file is linked with them.

#define WIN32_LEAN_AND_MEAN
#include <Windows.h>
#include <windowsx.h>
#include <jni.h>
#include <stdlib.h>

#include "compose_window_chrome.h"
#include "composeWindowChromeHook.h"

// Where a window's record hangs. A property rather than a table, so finding it needs
// nothing but the window, from whichever thread presents into it.
static const wchar_t *const COMPOSE_CHROME_PROPERTY = L"ComposeWindowChrome";

struct ComposeChrome {
    WNDPROC inner;
    int takeCaption;
    int syncResize;
    int32_t captionDip;
    int32_t buttonsDip;
    // Guarded by composeLock: written by the toolkit thread and by whichever thread presents.
    struct compose_resize_sync sync;
    // Set on every present; what a waiting size message sleeps on.
    HANDLE presented;
    // The toolkit thread is inside a wait. Messages it pumps meanwhile can reach this
    // procedure again, and none of them may start a second wait or free the record.
    int waiting;
    int destroyed;
};

static INIT_ONCE composeLockOnce = INIT_ONCE_STATIC_INIT;
static CRITICAL_SECTION composeLock;

static BOOL CALLBACK composeInitLock(PINIT_ONCE, PVOID, PVOID *) {
    InitializeCriticalSection(&composeLock);
    return TRUE;
}

static void composeEnter() {
    InitOnceExecuteOnce(&composeLockOnce, composeInitLock, NULL, NULL);
    EnterCriticalSection(&composeLock);
}

static void composeLeave() {
    LeaveCriticalSection(&composeLock);
}

// GetDpiForWindow and GetSystemMetricsForDpi arrived in Windows 10 1607. Linking them would
// refuse to start on anything older, so they are looked up, and the fallback is the desktop's
// own scale, which is what an older system has anyway.
static UINT composeDpiFor(HWND window) {
    typedef UINT(WINAPI * GetDpiForWindowFn)(HWND);
    static GetDpiForWindowFn getDpi = (GetDpiForWindowFn)(void *)GetProcAddress(
        GetModuleHandleW(L"user32.dll"), "GetDpiForWindow");
    UINT dpi = getDpi != NULL ? getDpi(window) : 0;
    if (dpi == 0) {
        HDC screen = GetDC(NULL);
        if (screen != NULL) {
            dpi = (UINT)GetDeviceCaps(screen, LOGPIXELSY);
            ReleaseDC(NULL, screen);
        }
    }
    return dpi == 0 ? 96 : dpi;
}

static int32_t composeMetric(int index, UINT dpi) {
    typedef int(WINAPI * GetSystemMetricsForDpiFn)(int, UINT);
    static GetSystemMetricsForDpiFn forDpi = (GetSystemMetricsForDpiFn)(void *)GetProcAddress(
        GetModuleHandleW(L"user32.dll"), "GetSystemMetricsForDpi");
    return forDpi != NULL ? forDpi(index, dpi) : GetSystemMetrics(index);
}

/** The resize border, which is also how far a maximised window hangs past its monitor. */
static int32_t composeResizeBorder(HWND window) {
    UINT dpi = composeDpiFor(window);
    return composeMetric(SM_CYSIZEFRAME, dpi) + composeMetric(SM_CXPADDEDBORDER, dpi);
}

static ComposeChrome *composeChromeOf(HWND window) {
    return (ComposeChrome *)GetPropW(window, COMPOSE_CHROME_PROPERTY);
}

static void composeFree(ComposeChrome *chrome) {
    if (chrome->presented != NULL) {
        CloseHandle(chrome->presented);
    }
    free(chrome);
}

/**
 * Holds the size message until a frame at the expected size has been presented, or until
 * the wait runs out.
 *
 * The thread waiting is AWT's toolkit thread, and the thread that draws needs it while it
 * draws: laying out the new size moves the drawing surface, a child window this thread owns,
 * and that is done by sending it messages. So the wait pumps sent messages, and only those:
 * posted input stays queued for Windows' own loop to take when this returns.
 */
static void composeWaitForFrame(ComposeChrome *chrome, uint64_t presentsAtStart,
                                int32_t expectedWidth, int32_t expectedHeight) {
    ULONGLONG deadline = GetTickCount64() + COMPOSE_RESIZE_WAIT_MS;
    chrome->waiting = 1;
    for (;;) {
        composeEnter();
        int done = chrome->destroyed ||
            compose_resize_satisfied(&chrome->sync, presentsAtStart, expectedWidth, expectedHeight);
        composeLeave();
        if (done) {
            break;
        }
        ULONGLONG now = GetTickCount64();
        if (now >= deadline) {
            break;
        }
        DWORD woke = MsgWaitForMultipleObjectsEx(
            1, &chrome->presented, (DWORD)(deadline - now), QS_SENDMESSAGE, MWMO_INPUTAVAILABLE);
        if (woke == WAIT_OBJECT_0 + 1) {
            MSG message;
            PeekMessageW(&message, NULL, 0, 0, PM_NOREMOVE | PM_QS_SENDMESSAGE);
        } else if (woke == WAIT_FAILED) {
            break;
        }
    }
    chrome->waiting = 0;
}

static LRESULT CALLBACK composeWindowProc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    ComposeChrome *chrome = composeChromeOf(window);
    if (chrome == NULL) {
        return DefWindowProcW(window, message, wparam, lparam);
    }
    WNDPROC inner = chrome->inner;
    switch (message) {
    case WM_NCCALCSIZE: {
        // wparam FALSE asks only for a rectangle, with no frame to compute.
        if (!chrome->takeCaption || wparam != TRUE) {
            break;
        }
        // Let the frame be computed, then put the top edge back where it was asked to be:
        // the sides and the bottom stay the system's (shadow, resize border, Snap), and the
        // strip the caption occupied becomes client area.
        NCCALCSIZE_PARAMS *params = (NCCALCSIZE_PARAMS *)lparam;
        LONG requestedTop = params->rgrc[0].top;
        CallWindowProcW(inner, window, message, wparam, lparam);
        params->rgrc[0].top = requestedTop +
            compose_caption_top_inset(IsZoomed(window), composeResizeBorder(window));
        return 0;
    }
    case WM_NCHITTEST: {
        LRESULT where = CallWindowProcW(inner, window, message, wparam, lparam);
        if (!chrome->takeCaption || where != HTCLIENT) {
            return where;
        }
        RECT client;
        if (!GetClientRect(window, &client)) {
            return where;
        }
        MapWindowPoints(window, NULL, (POINT *)&client, 2);
        UINT dpi = composeDpiFor(window);
        enum compose_caption_part part = compose_caption_hit(
            GET_Y_LPARAM(lparam) - client.top,
            client.right - GET_X_LPARAM(lparam),
            IsZoomed(window),
            composeResizeBorder(window),
            compose_scale(chrome->captionDip, dpi),
            compose_scale(chrome->buttonsDip, dpi));
        switch (part) {
        case COMPOSE_CAPTION_TOP:
            return HTTOP;
        case COMPOSE_CAPTION_DRAG:
            return HTCAPTION;
        default:
            return HTCLIENT;
        }
    }
    case WM_ENTERSIZEMOVE:
        composeEnter();
        chrome->sync.dragging = 1;
        composeLeave();
        break;
    case WM_EXITSIZEMOVE:
        composeEnter();
        chrome->sync.dragging = 0;
        composeLeave();
        break;
    case WM_SIZE: {
        // AWT first: its handler is what tells Compose about the new size.
        LRESULT result = CallWindowProcW(inner, window, message, wparam, lparam);
        if (wparam == SIZE_MINIMIZED) {
            return result;
        }
        int32_t width = (int32_t)LOWORD(lparam);
        int32_t height = (int32_t)HIWORD(lparam);
        int32_t expectedWidth = 0;
        int32_t expectedHeight = 0;
        composeEnter();
        int wait = chrome->syncResize && !chrome->waiting &&
            compose_resize_expect(&chrome->sync, width, height, &expectedWidth, &expectedHeight);
        uint64_t presentsAtStart = chrome->sync.presents;
        compose_resize_note_client(&chrome->sync, width, height);
        composeLeave();
        if (wait) {
            composeWaitForFrame(chrome, presentsAtStart, expectedWidth, expectedHeight);
            if (chrome->destroyed) {
                composeFree(chrome);
            }
        }
        return result;
    }
    case WM_NCDESTROY: {
        SetWindowLongPtrW(window, GWLP_WNDPROC, (LONG_PTR)inner);
        composeEnter();
        RemovePropW(window, COMPOSE_CHROME_PROPERTY);
        composeLeave();
        // A wait further down this thread's stack still holds the record; it frees it.
        if (chrome->waiting) {
            chrome->destroyed = 1;
        } else {
            composeFree(chrome);
        }
        return CallWindowProcW(inner, window, message, wparam, lparam);
    }
    default:
        break;
    }
    return CallWindowProcW(inner, window, message, wparam, lparam);
}

static void composeFrameChanged(HWND window) {
    SetWindowPos(window, NULL, 0, 0, 0, 0,
                 SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
}

extern "C" void composeWindowChromePresented(HWND content, int32_t width, int32_t height) {
    HWND root = GetAncestor(content, GA_ROOT);
    if (root == NULL) {
        return;
    }
    composeEnter();
    ComposeChrome *chrome = composeChromeOf(root);
    if (chrome != NULL) {
        compose_resize_note_present(&chrome->sync, width, height);
        SetEvent(chrome->presented);
    }
    composeLeave();
}

/*
 * Declares the process aware of per-monitor scaling before anything makes a window.
 *
 * Windows assumes a program does not understand scaling unless it says so, and draws one
 * that does not at 96 DPI and stretches the result: a soft window among sharp ones. A JVM
 * says so in java.exe's manifest; a native image is its own executable and says nothing, and
 * AWT's own call (SetProcessDPIAware) only reaches system awareness, which is wrong on a second
 * monitor with another scale. The call has to precede every window, so it runs while the C
 * runtime initialises the executable, which is before the JVM or anything else of Java exists.
 *
 * Only when this object is linked into the executable itself. Loaded as skiko's DLL into a
 * running JVM it is too late to declare anything, and the JVM has already declared its own.
 * An executable whose manifest declares an awareness keeps it: the call fails when one is set.
 */
extern "C" IMAGE_DOS_HEADER __ImageBase;

static int composeDeclareDpiAwareness() {
    if ((HMODULE)&__ImageBase != GetModuleHandleW(NULL)) {
        return 0;
    }
    HMODULE user32 = LoadLibraryW(L"user32.dll");
    if (user32 != NULL) {
        typedef BOOL(WINAPI * SetContextFn)(HANDLE);
        SetContextFn setContext =
            (SetContextFn)(void *)GetProcAddress(user32, "SetProcessDpiAwarenessContext");
        // DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2, spelled out because the SDK defines it
        // only above a certain WINVER.
        if (setContext != NULL && setContext((HANDLE)(intptr_t)-4)) {
            return 2;
        }
    }
    // Windows 8.1 knew per-monitor scaling but not a window moving between monitors.
    HMODULE shcore = LoadLibraryW(L"shcore.dll");
    if (shcore != NULL) {
        typedef HRESULT(WINAPI * SetAwarenessFn)(int);
        SetAwarenessFn setAwareness =
            (SetAwarenessFn)(void *)GetProcAddress(shcore, "SetProcessDpiAwareness");
        if (setAwareness != NULL && setAwareness(2) == S_OK) {
            return 1;
        }
    }
    return 0;
}

// A dynamic initialiser, so the C runtime runs it before main and no compiler drops it.
static const int composeDpiDeclared = composeDeclareDpiAwareness();

// The first icon group in the executable's resources. A string name is only valid during
// the enumeration, so it is copied; a numbered one is the number itself.
struct ComposeIconName {
    LPCWSTR name;
    wchar_t text[256];
};

static BOOL CALLBACK composeFirstIcon(HMODULE, LPCWSTR, LPWSTR name, LONG_PTR found) {
    ComposeIconName *icon = (ComposeIconName *)found;
    if (IS_INTRESOURCE(name)) {
        icon->name = name;
    } else {
        lstrcpynW(icon->text, name, 256);
        icon->name = icon->text;
    }
    return FALSE;
}

extern "C" {

JNIEXPORT jboolean JNICALL Java_org_jetbrains_skiko_compose_WindowsWindowChrome_install(
    JNIEnv *env, jclass, jlong handle, jint captionDip, jint buttonsDip,
    jboolean takeCaption, jboolean syncResize) {
    HWND window = GetAncestor((HWND)(intptr_t)handle, GA_ROOT);
    if (window == NULL) {
        return JNI_FALSE;
    }
    ComposeChrome *existing = composeChromeOf(window);
    if (existing != NULL) {
        int captionChanged = existing->takeCaption != (takeCaption ? 1 : 0);
        existing->takeCaption = takeCaption ? 1 : 0;
        existing->syncResize = syncResize ? 1 : 0;
        existing->captionDip = captionDip;
        existing->buttonsDip = buttonsDip;
        if (captionChanged) {
            composeFrameChanged(window);
        }
        return JNI_TRUE;
    }
    ComposeChrome *chrome = (ComposeChrome *)calloc(1, sizeof(ComposeChrome));
    if (chrome == NULL) {
        return JNI_FALSE;
    }
    chrome->takeCaption = takeCaption ? 1 : 0;
    chrome->syncResize = syncResize ? 1 : 0;
    chrome->captionDip = captionDip;
    chrome->buttonsDip = buttonsDip;
    chrome->presented = CreateEventW(NULL, FALSE, FALSE, NULL);
    if (chrome->presented == NULL) {
        composeFree(chrome);
        return JNI_FALSE;
    }
    RECT client;
    if (GetClientRect(window, &client)) {
        compose_resize_note_client(&chrome->sync, client.right - client.left, client.bottom - client.top);
    }
    // The record is in place before the procedure that reads it.
    composeEnter();
    BOOL attached = SetPropW(window, COMPOSE_CHROME_PROPERTY, chrome);
    composeLeave();
    if (!attached) {
        composeFree(chrome);
        return JNI_FALSE;
    }
    chrome->inner = (WNDPROC)GetWindowLongPtrW(window, GWLP_WNDPROC);
    if (chrome->inner == NULL ||
        SetWindowLongPtrW(window, GWLP_WNDPROC, (LONG_PTR)composeWindowProc) == 0) {
        composeEnter();
        RemovePropW(window, COMPOSE_CHROME_PROPERTY);
        composeLeave();
        composeFree(chrome);
        return JNI_FALSE;
    }
    // Nothing recomputes the frame on its own.
    if (chrome->takeCaption) {
        composeFrameChanged(window);
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_org_jetbrains_skiko_compose_WindowsWindowChrome_refreshFrame(
    JNIEnv *env, jclass, jlong handle) {
    HWND window = GetAncestor((HWND)(intptr_t)handle, GA_ROOT);
    if (window != NULL && composeChromeOf(window) != NULL) {
        composeFrameChanged(window);
    }
}

JNIEXPORT jint JNICALL Java_org_jetbrains_skiko_compose_WindowsWindowChrome_dpiAwareness(
    JNIEnv *env, jclass) {
    return composeDpiDeclared;
}

JNIEXPORT jboolean JNICALL Java_org_jetbrains_skiko_compose_WindowsWindowChrome_useExecutableIcon(
    JNIEnv *env, jclass, jlong handle) {
    HWND window = GetAncestor((HWND)(intptr_t)handle, GA_ROOT);
    HMODULE executable = GetModuleHandleW(NULL);
    if (window == NULL || executable == NULL) {
        return JNI_FALSE;
    }
    ComposeIconName icon = {};
    EnumResourceNamesW(executable, RT_GROUP_ICON, composeFirstIcon, (LONG_PTR)&icon);
    LPCWSTR group = icon.name;
    if (group == NULL) {
        return JNI_FALSE;
    }
    HICON big = (HICON)LoadImageW(executable, group, IMAGE_ICON,
                                  GetSystemMetrics(SM_CXICON), GetSystemMetrics(SM_CYICON), LR_SHARED);
    HICON small = (HICON)LoadImageW(executable, group, IMAGE_ICON,
                                    GetSystemMetrics(SM_CXSMICON), GetSystemMetrics(SM_CYSMICON), LR_SHARED);
    if (big == NULL && small == NULL) {
        return JNI_FALSE;
    }
    if (big != NULL) {
        SendMessageW(window, WM_SETICON, ICON_BIG, (LPARAM)big);
    }
    if (small != NULL) {
        SendMessageW(window, WM_SETICON, ICON_SMALL, (LPARAM)small);
    }
    return JNI_TRUE;
}

}

#endif
