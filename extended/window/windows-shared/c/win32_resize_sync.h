#ifndef DXC_WIN32_RESIZE_SYNC_H
#define DXC_WIN32_RESIZE_SYNC_H

// The window's frame waits until the application has drawn the new size.
//
// Windows 11's Settings app does this: while the reader drags an edge, the frame stays where it
// was until the app has drawn for the new size, so the reader sees no black, no gap and no
// stretched picture. An ordinary Win32 window is not waited for. This header asks Windows to
// wait for ours, with the private resize synchronization that XAML uses and
// EpicGames/raddebugger#1033 uses for a plain Win32 window:
//
//   - user32 ordinal 2615, EnableResizeLayoutSynchronization(hwnd, enable), on the top-level
//     window and on a hidden, zero-size child of it. Windows issues no token for a window
//     without such a child.
//   - user32 ordinal 2614, GetResizeDCompositionSynchronizationObject(hwnd, &handle), called in
//     WM_WINDOWPOSCHANGED. It can succeed with a null handle: not every size change has a token.
//   - the frame is shown through a Windows.UI.Composition drawing surface on a compositor made
//     by IInteropCompositorFactoryPartner::CreateInteropCompositor, not through the window's
//     DXGI swapchain: a swapchain's present does not belong to the commit that carries the token
//     (the probe's "swapchain" drags show holes and stale frames, compose-rust issue #364).
//   - IDCompositionDesktopDevicePartner6::SynchronizedCommit(token) commits the surface's new
//     picture together with the token, and user32 ordinal 2538, LayoutCompleted(hwnd), says the
//     layout is done.
//   - the frame is drawn from a posted message, after WM_WINDOWPOSCHANGED has returned.
//
// All of it is private. Nothing is linked: every ordinal is looked up with GetProcAddress, the
// interfaces are called by vtable slot, and a missing piece means the path is not taken, so the
// window carries on with the CPU resize frames it has had. It is opt in (DXC_RESIZE_SYNC=1) and
// needs Windows 11 (build 22000) unless DXC_RESIZE_SYNC=force. Interface IDs and vtable slots
// are the ones raddebugger#1033 uses, from the Windows SDK's MIDL bindings.
//
// Header only and Windows only, like the code that includes it; the includes below are the
// ones this file needs, so that it also compiles on its own.

#ifndef COBJMACROS
#define COBJMACROS
#endif
#include <windows.h>
#include <d3d11.h>
#include <roapi.h>
#include <winstring.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#pragma comment(lib, "runtimeobject.lib")

// Windows 11's first build. Below it the path is not taken unless DXC_RESIZE_SYNC=force. The
// probe's drags passed on Windows Server 2022 (build 20348) too, which is where force is for.
#define DXC_SYNC_MIN_BUILD 22000
#define DXC_WM_SYNC_RENDER (WM_APP + 3)

static const GUID dxc_sync_iid_compositor = {0xb403ca50, 0x7f8c, 0x4e83, {0x98, 0x5f, 0xcc, 0x45, 0x06, 0x00, 0x36, 0xd8}};
static const GUID dxc_sync_iid_compositor_interop = {0x25297d5c, 0x3ad4, 0x4c9c, {0xb5, 0xcf, 0xe3, 0x6a, 0x38, 0x51, 0x23, 0x30}};
static const GUID dxc_sync_iid_compositor_desktop = {0x29e691fa, 0x4567, 0x4dca, {0xb3, 0x19, 0xd0, 0xf2, 0x07, 0xeb, 0x68, 0x07}};
static const GUID dxc_sync_iid_factory_partner = {0x22118adf, 0x23f1, 0x4801, {0xbc, 0xfa, 0x66, 0xcb, 0xf4, 0x8c, 0xc5, 0x1b}};
static const GUID dxc_sync_iid_device_partner6 = {0xe01eb649, 0x787e, 0x4560, {0xb3, 0x98, 0x0d, 0xe7, 0xa2, 0x06, 0x5d, 0x8b}};
static const GUID dxc_sync_iid_visual = {0x117e202d, 0xa859, 0x4c89, {0x87, 0x3b, 0xc2, 0xaa, 0x56, 0x67, 0x88, 0xe3}};
static const GUID dxc_sync_iid_target = {0xa1bea8ba, 0xd726, 0x4663, {0x81, 0x29, 0x6b, 0x5e, 0x79, 0x27, 0xff, 0xa6}};
static const GUID dxc_sync_iid_surface = {0x1527540d, 0x42c7, 0x47a6, {0xa4, 0x08, 0x66, 0x8f, 0x79, 0xa9, 0x0d, 0xfb}};
static const GUID dxc_sync_iid_brush = {0xab0d7608, 0x30c0, 0x40e9, {0xb5, 0x68, 0xb6, 0x0a, 0x6b, 0xd1, 0xfb, 0x46}};
static const GUID dxc_sync_iid_drawing_interop = {0xfd04e6e3, 0xfe0c, 0x4c3c, {0xab, 0x19, 0xa0, 0x76, 0x01, 0xa5, 0x76, 0xee}};

// Slots 0-2 are IUnknown's, 3-5 IInspectable's on the WinRT interfaces.
#define DXC_SYNC_SLOT_FACTORY_CREATE_INTEROP_COMPOSITOR 6
#define DXC_SYNC_SLOT_PARTNER6_SYNCHRONIZED_COMMIT 69
#define DXC_SYNC_SLOT_COMPOSITOR_CREATE_SPRITE_VISUAL 22
#define DXC_SYNC_SLOT_COMPOSITOR_CREATE_SURFACE_BRUSH 24
#define DXC_SYNC_SLOT_INTEROP_CREATE_GRAPHICS_DEVICE 5
#define DXC_SYNC_SLOT_DESKTOP_CREATE_TARGET 3
#define DXC_SYNC_SLOT_GRAPHICS_DEVICE_CREATE_DRAWING_SURFACE 6
#define DXC_SYNC_SLOT_VISUAL_PUT_SIZE 36
#define DXC_SYNC_SLOT_SPRITE_PUT_BRUSH 7
#define DXC_SYNC_SLOT_TARGET_PUT_ROOT 7
#define DXC_SYNC_SLOT_DRAWING_BEGIN_DRAW 3
#define DXC_SYNC_SLOT_DRAWING_END_DRAW 4
#define DXC_SYNC_SLOT_DRAWING_RESIZE 5

typedef struct { float x, y; } dxc_sync_vec2;
typedef HRESULT(STDMETHODCALLTYPE *dxc_sync_get_fn)(void *self, void **out);
typedef HRESULT(STDMETHODCALLTYPE *dxc_sync_put_fn)(void *self, void *value);
typedef HRESULT(STDMETHODCALLTYPE *dxc_sync_call_fn)(void *self);
typedef HRESULT(STDMETHODCALLTYPE *dxc_sync_put_get_fn)(void *self, void *value, void **out);
typedef HRESULT(STDMETHODCALLTYPE *dxc_sync_interop_compositor_fn)(void *self, void *device, void *callback, const GUID *iid, void **out);
typedef HRESULT(STDMETHODCALLTYPE *dxc_sync_target_fn)(void *self, HWND hwnd, BOOL topmost, void **out);
typedef HRESULT(STDMETHODCALLTYPE *dxc_sync_drawing_surface_fn)(void *self, dxc_sync_vec2 size, int format, int alpha, void **out);
typedef HRESULT(STDMETHODCALLTYPE *dxc_sync_put_vec2_fn)(void *self, dxc_sync_vec2 value);
typedef HRESULT(STDMETHODCALLTYPE *dxc_sync_begin_draw_fn)(void *self, const RECT *update, const GUID *iid, void **out, POINT *offset);
typedef HRESULT(STDMETHODCALLTYPE *dxc_sync_resize_fn)(void *self, SIZE size);
typedef struct { DWORD size; int thread_type; int apartment_type; } dxc_sync_queue_options;
typedef HRESULT(WINAPI *dxc_sync_create_queue_fn)(dxc_sync_queue_options options, void **out);
typedef BOOL(WINAPI *dxc_sync_enable_fn)(HWND, BOOL);
typedef BOOL(WINAPI *dxc_sync_get_token_fn)(HWND, HANDLE *);
typedef BOOL(WINAPI *dxc_sync_layout_fn)(HWND);

#define DXC_SYNC_SLOT(object, slot, type) ((type)((*(void ***)(object))[slot]))

// The state of the path. dxc_sync_active is the one the window reads: composition is showing the
// window, the resize synchronization is in place, and frames go to the surface.
static int dxc_sync_active;
// A resize message has been posted and has not run yet, and the token it will commit.
static int dxc_sync_queued;
static HANDLE dxc_sync_token;
static long dxc_sync_steps;
static long dxc_sync_tokens;
static long dxc_sync_failures;

static void *dxc_sync_compositor;
static void *dxc_sync_partner;
static void *dxc_sync_graphics_device;
static void *dxc_sync_drawing;
static void *dxc_sync_visual;
static void *dxc_sync_target;
static HWND dxc_sync_child;
static int dxc_sync_width;
static int dxc_sync_height;
static dxc_sync_enable_fn dxc_sync_enable;
static dxc_sync_get_token_fn dxc_sync_get_token;
static dxc_sync_layout_fn dxc_sync_layout;

/** "1" and "force" ask for the path; "force" also skips the Windows 11 build floor. */
static int dxc_sync_requested(int *force) {
    const char *value = getenv("DXC_RESIZE_SYNC");
    *force = value != NULL && strcmp(value, "force") == 0;
    return value != NULL && (strcmp(value, "1") == 0 || *force);
}

/** The kernel's own build number (RtlGetVersion, which no compatibility shim rewrites). */
static int dxc_sync_os_build(void) {
    typedef LONG(WINAPI * rtl_get_version_fn)(OSVERSIONINFOW *);
    OSVERSIONINFOW version;
    memset(&version, 0, sizeof version);
    version.dwOSVersionInfoSize = sizeof version;
    rtl_get_version_fn get = (rtl_get_version_fn)(void *)GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "RtlGetVersion");
    if (get == NULL || get(&version) != 0) return 0;
    return (int)version.dwBuildNumber;
}

static int dxc_sync_failed(HRESULT result, const char *what) {
    if (FAILED(result)) {
        fprintf(stderr, "compose-rust: resize-sync: %s failed (0x%08lx)\n", what, (unsigned long)result);
        return 1;
    }
    return 0;
}

static void *dxc_sync_query(void *object, const GUID *iid, const char *what) {
    void *result = NULL;
    if (dxc_sync_failed(((IUnknown *)object)->lpVtbl->QueryInterface((IUnknown *)object, iid, &result), what)) return NULL;
    return result;
}

/** Commits what was drawn into the surface, with the resize's token when there is one. */
static int dxc_sync_commit(HANDLE token) {
    if (dxc_sync_partner == NULL) return 0;
    return !dxc_sync_failed(DXC_SYNC_SLOT(dxc_sync_partner, DXC_SYNC_SLOT_PARTNER6_SYNCHRONIZED_COMMIT, dxc_sync_put_fn)(dxc_sync_partner, token),
                            "SynchronizedCommit");
}

/**
 * Stops covering the window with composition, so that the swapchain shows again. The window calls
 * this when a step of the path fails after it has started; the token still queued is closed.
 */
static void dxc_sync_deactivate(const char *reason) {
    if (!dxc_sync_active) return;
    dxc_sync_active = 0;
    dxc_sync_failures++;
    if (dxc_sync_target != NULL) {
        DXC_SYNC_SLOT(dxc_sync_target, DXC_SYNC_SLOT_TARGET_PUT_ROOT, dxc_sync_put_fn)(dxc_sync_target, NULL);
        dxc_sync_commit(NULL);
    }
    if (dxc_sync_token != NULL) {
        CloseHandle(dxc_sync_token);
        dxc_sync_token = NULL;
    }
    dxc_sync_queued = 0;
    fprintf(stderr, "compose-rust: resize-mode: resize-sync off because %s\n", reason);
}

/**
 * Brings the path up for window, whose frames are drawn by d3d11 (a Direct3D 11 device). Returns 1
 * when frames now go through composition and resizes are synchronized, 0 when the window carries
 * on as it was; every refusal says why on standard error. Not taken at all unless asked for.
 */
static int dxc_sync_start(HWND window, ID3D11Device *d3d11, int width, int height) {
    int force = 0;
    if (!dxc_sync_requested(&force)) return 0;
    int build = dxc_sync_os_build();
    if (build < DXC_SYNC_MIN_BUILD && !force) {
        fprintf(stderr, "compose-rust: resize-mode: resize-sync not taken because Windows build %d is older than %d (DXC_RESIZE_SYNC=force skips this)\n",
                build, DXC_SYNC_MIN_BUILD);
        return 0;
    }
    HMODULE user32 = GetModuleHandleW(L"user32.dll");
    dxc_sync_get_token = (dxc_sync_get_token_fn)(void *)GetProcAddress(user32, MAKEINTRESOURCEA(2614));
    dxc_sync_enable = (dxc_sync_enable_fn)(void *)GetProcAddress(user32, MAKEINTRESOURCEA(2615));
    dxc_sync_layout = (dxc_sync_layout_fn)(void *)GetProcAddress(user32, MAKEINTRESOURCEA(2538));
    if (dxc_sync_get_token == NULL || dxc_sync_enable == NULL || dxc_sync_layout == NULL) {
        fprintf(stderr, "compose-rust: resize-mode: resize-sync not taken because user32 lacks ordinal %s\n",
                dxc_sync_enable == NULL ? "2615" : (dxc_sync_get_token == NULL ? "2614" : "2538"));
        return 0;
    }
    HRESULT initialized = RoInitialize(RO_INIT_SINGLETHREADED);
    // S_FALSE: already initialized on this thread, by whoever came first. Fine.
    if (FAILED(initialized) && initialized != RPC_E_CHANGED_MODE) {
        dxc_sync_failed(initialized, "RoInitialize");
        return 0;
    }
    HMODULE messaging = LoadLibraryW(L"CoreMessaging.dll");
    dxc_sync_create_queue_fn create_queue =
        messaging != NULL ? (dxc_sync_create_queue_fn)(void *)GetProcAddress(messaging, "CreateDispatcherQueueController") : NULL;
    if (create_queue == NULL) {
        fprintf(stderr, "compose-rust: resize-mode: resize-sync not taken because CreateDispatcherQueueController is missing\n");
        return 0;
    }
    dxc_sync_queue_options options = {sizeof options, 2, 2};
    void *queue = NULL;
    if (dxc_sync_failed(create_queue(options, &queue), "CreateDispatcherQueueController")) return 0;

    static const WCHAR class_name[] = L"Windows.UI.Composition.Compositor";
    HSTRING_HEADER header;
    HSTRING name = NULL;
    if (dxc_sync_failed(WindowsCreateStringReference(class_name, (UINT32)(sizeof class_name / sizeof(WCHAR) - 1), &header, &name),
                        "WindowsCreateStringReference")) return 0;
    void *factory = NULL;
    if (dxc_sync_failed(RoGetActivationFactory(name, &dxc_sync_iid_factory_partner, &factory), "the interop compositor factory") ||
        factory == NULL) return 0;
    HRESULT made = DXC_SYNC_SLOT(factory, DXC_SYNC_SLOT_FACTORY_CREATE_INTEROP_COMPOSITOR, dxc_sync_interop_compositor_fn)(
        factory, NULL, NULL, &dxc_sync_iid_compositor, &dxc_sync_compositor);
    IUnknown_Release((IUnknown *)factory);
    if (dxc_sync_failed(made, "CreateInteropCompositor")) return 0;
    dxc_sync_partner = dxc_sync_query(dxc_sync_compositor, &dxc_sync_iid_device_partner6, "IDCompositionDesktopDevicePartner6");
    if (dxc_sync_partner == NULL) return 0;

    void *interop = dxc_sync_query(dxc_sync_compositor, &dxc_sync_iid_compositor_interop, "ICompositorInterop");
    if (interop == NULL) return 0;
    made = DXC_SYNC_SLOT(interop, DXC_SYNC_SLOT_INTEROP_CREATE_GRAPHICS_DEVICE, dxc_sync_put_get_fn)(interop, d3d11, &dxc_sync_graphics_device);
    IUnknown_Release((IUnknown *)interop);
    if (dxc_sync_failed(made, "CreateGraphicsDevice on the renderer's Direct3D 11 device")) return 0;

    void *desktop = dxc_sync_query(dxc_sync_compositor, &dxc_sync_iid_compositor_desktop, "ICompositorDesktopInterop");
    if (desktop == NULL) return 0;
    void *desktop_target = NULL;
    made = DXC_SYNC_SLOT(desktop, DXC_SYNC_SLOT_DESKTOP_CREATE_TARGET, dxc_sync_target_fn)(desktop, window, TRUE, &desktop_target);
    IUnknown_Release((IUnknown *)desktop);
    if (dxc_sync_failed(made, "CreateDesktopWindowTarget")) return 0;
    dxc_sync_target = dxc_sync_query(desktop_target, &dxc_sync_iid_target, "ICompositionTarget");
    void *sprite = NULL;
    if (dxc_sync_target == NULL ||
        dxc_sync_failed(DXC_SYNC_SLOT(dxc_sync_compositor, DXC_SYNC_SLOT_COMPOSITOR_CREATE_SPRITE_VISUAL, dxc_sync_get_fn)(dxc_sync_compositor, &sprite),
                        "CreateSpriteVisual")) return 0;
    dxc_sync_visual = dxc_sync_query(sprite, &dxc_sync_iid_visual, "IVisual");
    if (dxc_sync_visual == NULL) return 0;
    dxc_sync_vec2 one = {1, 1};
    void *drawing_surface = NULL;
    if (dxc_sync_failed(DXC_SYNC_SLOT(dxc_sync_graphics_device, DXC_SYNC_SLOT_GRAPHICS_DEVICE_CREATE_DRAWING_SURFACE, dxc_sync_drawing_surface_fn)(
                            dxc_sync_graphics_device, one, 87 /* B8G8R8A8UIntNormalized */, 1 /* premultiplied */, &drawing_surface),
                        "CreateDrawingSurface")) return 0;
    void *surface = dxc_sync_query(drawing_surface, &dxc_sync_iid_surface, "ICompositionSurface");
    dxc_sync_drawing = dxc_sync_query(drawing_surface, &dxc_sync_iid_drawing_interop, "ICompositionDrawingSurfaceInterop");
    if (surface == NULL || dxc_sync_drawing == NULL) return 0;
    void *surface_brush = NULL;
    if (dxc_sync_failed(DXC_SYNC_SLOT(dxc_sync_compositor, DXC_SYNC_SLOT_COMPOSITOR_CREATE_SURFACE_BRUSH, dxc_sync_put_get_fn)(
                            dxc_sync_compositor, surface, &surface_brush), "CreateSurfaceBrush")) return 0;
    void *brush = dxc_sync_query(surface_brush, &dxc_sync_iid_brush, "ICompositionBrush");
    if (brush == NULL ||
        dxc_sync_failed(DXC_SYNC_SLOT(sprite, DXC_SYNC_SLOT_SPRITE_PUT_BRUSH, dxc_sync_put_fn)(sprite, brush), "the sprite's brush") ||
        dxc_sync_failed(DXC_SYNC_SLOT(dxc_sync_target, DXC_SYNC_SLOT_TARGET_PUT_ROOT, dxc_sync_put_fn)(dxc_sync_target, dxc_sync_visual), "the root visual"))
        return 0;
    // From here the surface covers the window, so a refusal below must uncover it again.

    // The hidden, zero-size child that makes Windows issue tokens, and the synchronization on both.
    static int class_made;
    if (!class_made) {
        WNDCLASSW child_class;
        memset(&child_class, 0, sizeof child_class);
        child_class.lpfnWndProc = DefWindowProcW;
        child_class.hInstance = GetModuleHandleW(NULL);
        child_class.lpszClassName = L"ComposeRustSyncChild";
        class_made = RegisterClassW(&child_class) != 0 || GetLastError() == ERROR_CLASS_ALREADY_EXISTS;
    }
    dxc_sync_child = CreateWindowExW(0, L"ComposeRustSyncChild", L"", WS_CHILD, 0, 0, 0, 0, window, NULL, GetModuleHandleW(NULL), NULL);
    if (dxc_sync_child == NULL) {
        fprintf(stderr, "compose-rust: resize-sync: the synchronization child could not be made (%lu)\n", (unsigned long)GetLastError());
        DXC_SYNC_SLOT(dxc_sync_target, DXC_SYNC_SLOT_TARGET_PUT_ROOT, dxc_sync_put_fn)(dxc_sync_target, NULL);
        return 0;
    }
    if (!dxc_sync_enable(window, TRUE) || !dxc_sync_enable(dxc_sync_child, TRUE)) {
        fprintf(stderr, "compose-rust: resize-sync: EnableResizeLayoutSynchronization was refused\n");
        DXC_SYNC_SLOT(dxc_sync_target, DXC_SYNC_SLOT_TARGET_PUT_ROOT, dxc_sync_put_fn)(dxc_sync_target, NULL);
        return 0;
    }
    dxc_sync_width = 0;
    dxc_sync_height = 0;
    dxc_sync_active = 1;
    fprintf(stderr, "compose-rust: resize-mode: resize-sync on (Windows build %d, composition surface, SynchronizedCommit); frames go through composition at %dx%d\n",
            build, width, height);
    return 1;
}

/**
 * The texture to draw width x height pixels into, and where in it: the surface is resized first
 * when the size changed, in the same commit as the picture drawn at that size. 1 on success.
 */
static int dxc_sync_begin_draw(int width, int height, ID3D11Texture2D **texture, POINT *offset) {
    if (!dxc_sync_active || width <= 0 || height <= 0) return 0;
    if (width != dxc_sync_width || height != dxc_sync_height) {
        SIZE size = {width, height};
        dxc_sync_vec2 visual_size = {(float)width, (float)height};
        if (dxc_sync_failed(DXC_SYNC_SLOT(dxc_sync_drawing, DXC_SYNC_SLOT_DRAWING_RESIZE, dxc_sync_resize_fn)(dxc_sync_drawing, size),
                            "resizing the drawing surface") ||
            dxc_sync_failed(DXC_SYNC_SLOT(dxc_sync_visual, DXC_SYNC_SLOT_VISUAL_PUT_SIZE, dxc_sync_put_vec2_fn)(dxc_sync_visual, visual_size),
                            "sizing the visual")) return 0;
        dxc_sync_width = width;
        dxc_sync_height = height;
    }
    return !dxc_sync_failed(DXC_SYNC_SLOT(dxc_sync_drawing, DXC_SYNC_SLOT_DRAWING_BEGIN_DRAW, dxc_sync_begin_draw_fn)(
                                dxc_sync_drawing, NULL, &IID_ID3D11Texture2D, (void **)texture, offset), "BeginDraw");
}

static int dxc_sync_end_draw(void) {
    return !dxc_sync_failed(DXC_SYNC_SLOT(dxc_sync_drawing, DXC_SYNC_SLOT_DRAWING_END_DRAW, dxc_sync_call_fn)(dxc_sync_drawing), "EndDraw");
}

/**
 * WM_WINDOWPOSCHANGED with a new size: takes the resize's token now and posts the message that
 * draws and commits it once this callback has returned. A later size replaces an unrun token.
 */
static void dxc_sync_on_size_change(HWND window) {
    HANDLE token = NULL;
    if (!dxc_sync_get_token(window, &token)) {
        if (token != NULL) CloseHandle(token);
        token = NULL;
        fprintf(stderr, "compose-rust: resize-sync: GetResizeDCompositionSynchronizationObject failed (%lu)\n", (unsigned long)GetLastError());
    }
    if (token != NULL) dxc_sync_tokens++;
    if (dxc_sync_token != NULL) CloseHandle(dxc_sync_token);
    dxc_sync_token = token;
    if (!dxc_sync_queued) {
        dxc_sync_queued = PostMessageW(window, DXC_WM_SYNC_RENDER, 0, 0) != 0;
    }
}

/** Takes the token the posted message has to commit, and clears the queue flag. */
static HANDLE dxc_sync_take_token(void) {
    HANDLE token = dxc_sync_token;
    dxc_sync_token = NULL;
    dxc_sync_queued = 0;
    return token;
}

/** After the frame for a resize was drawn: commits it with the token, and tells Windows the layout is done. */
static void dxc_sync_finish(HWND window, HANDLE token) {
    int committed = dxc_sync_commit(token);
    if (token != NULL) CloseHandle(token);
    int laid_out = dxc_sync_layout(window) ? 1 : 0;
    dxc_sync_steps++;
    if (dxc_sync_steps <= 3 || !committed || !laid_out) {
        fprintf(stderr, "compose-rust: resize-sync: step %ld commit=%d layout=%d tokens=%ld\n", dxc_sync_steps, committed, laid_out,
                dxc_sync_tokens);
    }
    if (!committed || !laid_out) dxc_sync_deactivate("a resize commit failed");
}

/** The window is going: the token still queued is closed. */
static void dxc_sync_shutdown(void) {
    if (dxc_sync_token != NULL) {
        CloseHandle(dxc_sync_token);
        dxc_sync_token = NULL;
    }
    dxc_sync_queued = 0;
    if (dxc_sync_active) {
        fprintf(stderr, "compose-rust: resize-sync: closed after %ld steps, %ld with a token\n", dxc_sync_steps, dxc_sync_tokens);
    }
    dxc_sync_active = 0;
}

#endif
