// The DirectComposition half of the Win32 window, kept to one translation unit so the rest
// of the window never has to be C++. No exceptions, no RTTI, no STL: the calls here are COM,
// which is plain function pointers in a vtable either way, and the plain C surface in
// win32_dcomp.h is what win32_window.c calls.
//
// Built with: cl /c /O2 /std:c++17 /GR- /EHs-c- /W3 win32_dcomp.cpp

#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0A00
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif

#include "win32_dcomp.h"

#include <d3d11.h>
#include <dcomp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct dxc_dcomp {
    IDCompositionDesktopDevice *device;
    IDCompositionTarget *target;
    IDCompositionVisual2 *visual;
    // The backing (DXC_DCOMP_BACKING, on unless 0): the target's root holds a visual filled
    // with one colour, scaled far past any window, behind the frame's visual, so wherever the
    // window's rectangle reaches past the frame on screen (a grown rectangle composed before
    // the commit with its larger frame) DWM shows that colour instead of the desktop behind the
    // window. Nothing here is resized during a drag: the surface is 8x8 and the visual's scale
    // never changes. root and backing are NULL when it is off or could not be made.
    IDCompositionVisual2 *root;
    IDCompositionVisual2 *backing;
    IDCompositionSurface *backing_surface;
    // The colour it holds, BGRA as the frame's bitmap, and the colour last asked for.
    uint32_t backing_bgra;
    uint32_t backing_asked;
    IDCompositionSurfaceFactory *factory;
    // A ring of surfaces, each frame written into one the visual does not show, and the visual
    // given it in the commit that shows the frame. BeginDraw on the surface the last commit
    // shows waits until DWM has let go of it; on one it does not show, it does not.
    IDCompositionVirtualSurface *surfaces[DXC_DCOMP_MAX_SURFACES];
    int32_t room_widths[DXC_DCOMP_MAX_SURFACES];
    int32_t room_heights[DXC_DCOMP_MAX_SURFACES];
    int count;
    // The surface the last commit shows, and the one the visual has been given since (shown
    // until a commit takes it).
    int shown;
    int next;
    // What the tree was made for, kept so a commit that fails can make everything again.
    HWND window;
    IUnknown *render_device;
    int32_t width;
    int32_t height;
    // The room a surface is made with. A surface only grows: a resize of a virtual surface
    // trims or reallocates it, which a drag would pay for on every step, so a smaller frame is
    // written into its top left and the clip shows only that.
    int32_t room_width;
    int32_t room_height;
    // The size the visual is clipped to, as last given to it.
    int32_t clip_width;
    int32_t clip_height;
    // Whether the visual carries a transform that stretches the shown frame into a larger or
    // smaller view (dxc_dcomp_stretch_shown); the next frame drawn takes it off.
    bool stretched;
};

namespace {

typedef HRESULT(WINAPI *create_device2_fn)(IUnknown *, REFIID, void **);

// Looked up at run time, so an application that links this window needs no dcomp.lib and a
// machine without dcomp.dll answers "no device" instead of failing to start.
create_device2_fn create_device2() {
    static int looked_up;
    static create_device2_fn found;
    if (!looked_up) {
        looked_up = 1;
        HMODULE library = LoadLibraryW(L"dcomp.dll");
        if (library != nullptr) {
            found = reinterpret_cast<create_device2_fn>(
                reinterpret_cast<void *>(GetProcAddress(library, "DCompositionCreateDevice2")));
        }
    }
    return found;
}

template <typename T> void release(T *&object) {
    if (object != nullptr) {
        object->Release();
        object = nullptr;
    }
}

void release_tree(dxc_dcomp *comp) {
    release(comp->backing);
    release(comp->backing_surface);
    release(comp->root);
    comp->backing_bgra = 0;
    release(comp->visual);
    release(comp->target);
    for (int index = 0; index < DXC_DCOMP_MAX_SURFACES; index++) {
        release(comp->surfaces[index]);
    }
    release(comp->factory);
}

void release_device(dxc_dcomp *comp) {
    release_tree(comp);
    release(comp->device);
}

bool make_device(dxc_dcomp *comp) {
    create_device2_fn create = create_device2();
    if (create == nullptr) {
        return false;
    }
    IDCompositionDesktopDevice *device = nullptr;
    if (FAILED(create(nullptr, __uuidof(IDCompositionDesktopDevice), reinterpret_cast<void **>(&device))) ||
        device == nullptr) {
        return false;
    }
    comp->device = device;
    return true;
}

// The clip, in the visual's coordinates, which are the window's client pixels: the surface
// shows at 1:1, so nothing is scaled.
HRESULT apply_clip(dxc_dcomp *comp) {
    D2D_RECT_F clip;
    clip.left = 0.0f;
    clip.top = 0.0f;
    clip.right = static_cast<float>(comp->width);
    clip.bottom = static_cast<float>(comp->height);
    HRESULT clipped = comp->visual->SetClip(clip);
    if (SUCCEEDED(clipped)) {
        comp->clip_width = comp->width;
        comp->clip_height = comp->height;
    }
    return clipped;
}

// Fills the backing's 8x8 surface with [bgra]. Zero on success.
HRESULT fill_backing(dxc_dcomp *comp, uint32_t bgra) {
    if (comp->backing_surface == nullptr || comp->render_device == nullptr) {
        return E_POINTER;
    }
    ID3D11Device *device = nullptr;
    HRESULT found = comp->render_device->QueryInterface(__uuidof(ID3D11Device), reinterpret_cast<void **>(&device));
    if (FAILED(found) || device == nullptr) {
        return FAILED(found) ? found : E_POINTER;
    }
    ID3D11DeviceContext *context = nullptr;
    device->GetImmediateContext(&context);
    device->Release();
    if (context == nullptr) {
        return E_POINTER;
    }
    ID3D11Texture2D *texture = nullptr;
    POINT offset = {0, 0};
    HRESULT began = comp->backing_surface->BeginDraw(nullptr, __uuidof(ID3D11Texture2D),
                                                     reinterpret_cast<void **>(&texture), &offset);
    if (SUCCEEDED(began) && texture != nullptr) {
        uint32_t pixels[8 * 8];
        for (int index = 0; index < 8 * 8; index++) {
            pixels[index] = bgra | 0xFF000000u;
        }
        D3D11_BOX box = {static_cast<UINT>(offset.x), static_cast<UINT>(offset.y), 0, static_cast<UINT>(offset.x + 8),
                         static_cast<UINT>(offset.y + 8), 1};
        context->UpdateSubresource(texture, 0, &box, pixels, 8 * 4, 0);
        context->Flush();
        texture->Release();
        began = comp->backing_surface->EndDraw();
    }
    context->Release();
    if (SUCCEEDED(began)) {
        comp->backing_bgra = bgra | 0xFF000000u;
    }
    return began;
}

// The backing under the frame's visual (see dxc_dcomp::root): a root visual holding the backing,
// scaled 8x8 to 32768 px square from the client area's top left, and the frame's visual in front
// of it. On any failure the backing is left out and the frame's visual is the root, as before.
// The answers are logged once a tree.
HRESULT build_backing(dxc_dcomp *comp) {
    const char *asked = getenv("DXC_DCOMP_BACKING");
    if (asked != nullptr && strcmp(asked, "0") == 0) {
        fprintf(stderr, "compose-rust: dcomp: no backing under the frame (DXC_DCOMP_BACKING=0)\n");
        return S_FALSE;
    }
    HRESULT root = comp->device->CreateVisual(&comp->root);
    HRESULT backing = SUCCEEDED(root) ? comp->device->CreateVisual(&comp->backing) : E_ABORT;
    HRESULT surface = SUCCEEDED(backing) ? comp->factory->CreateSurface(8, 8, DXGI_FORMAT_B8G8R8A8_UNORM,
                                                                        DXGI_ALPHA_MODE_IGNORE, &comp->backing_surface)
                                         : E_ABORT;
    HRESULT filled = SUCCEEDED(surface) ? fill_backing(comp, comp->backing_asked) : E_ABORT;
    HRESULT content = SUCCEEDED(filled) ? comp->backing->SetContent(comp->backing_surface) : E_ABORT;
    D2D_MATRIX_3X2_F scale = {};
    scale._11 = 4096.0f;
    scale._22 = 4096.0f;
    HRESULT scaled = SUCCEEDED(content) ? comp->backing->SetTransform(scale) : E_ABORT;
    if (SUCCEEDED(scaled)) {
        // One colour scaled: no blending between texels or at the edge.
        comp->backing->SetBitmapInterpolationMode(DCOMPOSITION_BITMAP_INTERPOLATION_MODE_NEAREST_NEIGHBOR);
        comp->backing->SetBorderMode(DCOMPOSITION_BORDER_MODE_HARD);
    }
    HRESULT under = SUCCEEDED(scaled) ? comp->root->AddVisual(comp->backing, FALSE, nullptr) : E_ABORT;
    // In front of the backing (insertAbove TRUE, relative to the backing).
    HRESULT over = SUCCEEDED(under) ? comp->root->AddVisual(comp->visual, TRUE, comp->backing) : E_ABORT;
    fprintf(stderr,
            "compose-rust: dcomp: backing under the frame 0x%08x: CreateVisual 0x%08lx 0x%08lx CreateSurface 0x%08lx "
            "fill 0x%08lx SetContent 0x%08lx SetTransform 0x%08lx AddVisual 0x%08lx 0x%08lx\n",
            static_cast<unsigned>(comp->backing_bgra), static_cast<unsigned long>(root),
            static_cast<unsigned long>(backing), static_cast<unsigned long>(surface),
            static_cast<unsigned long>(filled), static_cast<unsigned long>(content),
            static_cast<unsigned long>(scaled), static_cast<unsigned long>(under), static_cast<unsigned long>(over));
    if (FAILED(over)) {
        if (comp->root != nullptr && SUCCEEDED(under)) comp->root->RemoveAllVisuals();
        release(comp->backing);
        release(comp->backing_surface);
        release(comp->root);
        return over;
    }
    return S_OK;
}

// The surface factory, the surface, the target, the visual, its content and its clip, on the
// device there is. No commit. Every step's answer is said, once for each tree made (once a
// window, again only if a failed commit makes it again).
bool build_tree(dxc_dcomp *comp) {
    if (comp->device == nullptr || comp->window == nullptr || comp->render_device == nullptr ||
        comp->width <= 0 || comp->height <= 0) {
        return false;
    }
    HRESULT factory = comp->device->CreateSurfaceFactory(comp->render_device, &comp->factory);
    HRESULT surface = factory;
    for (int index = 0; SUCCEEDED(surface) && index < comp->count; index++) {
        surface = comp->factory->CreateVirtualSurface(static_cast<UINT>(comp->room_width),
                                                      static_cast<UINT>(comp->room_height),
                                                      DXGI_FORMAT_B8G8R8A8_UNORM, DXGI_ALPHA_MODE_IGNORE,
                                                      &comp->surfaces[index]);
        comp->room_widths[index] = comp->room_width;
        comp->room_heights[index] = comp->room_height;
    }
    comp->shown = 0;
    comp->next = 0;
    // Below the window's child windows (topmost FALSE): the native view slots, child windows
    // of this window (win32_window.c, dxc_native_view_*), are composed above the frame. A window
    // with no child window, which is every window without a native view, composes the same
    // either way: the window has no GDI surface for the visual to be above or below.
    HRESULT target = SUCCEEDED(surface) ? comp->device->CreateTargetForHwnd(comp->window, FALSE, &comp->target)
                                        : E_ABORT;
    HRESULT visual = SUCCEEDED(target) ? comp->device->CreateVisual(&comp->visual) : E_ABORT;
    HRESULT content = SUCCEEDED(visual) ? comp->visual->SetContent(comp->surfaces[0]) : E_ABORT;
    HRESULT clipped = SUCCEEDED(content) ? apply_clip(comp) : E_ABORT;
    if (SUCCEEDED(clipped)) build_backing(comp);
    HRESULT root = SUCCEEDED(clipped) ? comp->target->SetRoot(comp->root != nullptr ? comp->root : comp->visual)
                                      : E_ABORT;
    fprintf(stderr,
            "compose-rust: dcomp: tree for window %p: CreateSurfaceFactory 0x%08lx CreateVirtualSurface 0x%08lx "
            "(%d of %dx%d) CreateTargetForHwnd 0x%08lx CreateVisual 0x%08lx SetContent 0x%08lx SetClip 0x%08lx "
            "SetRoot 0x%08lx\n",
            static_cast<void *>(comp->window), static_cast<unsigned long>(factory),
            static_cast<unsigned long>(surface), comp->count, (int)comp->room_width, (int)comp->room_height,
            static_cast<unsigned long>(target), static_cast<unsigned long>(visual),
            static_cast<unsigned long>(content), static_cast<unsigned long>(clipped),
            static_cast<unsigned long>(root));
    if (FAILED(root)) {
        release_tree(comp);
        return false;
    }
    return true;
}

}  // namespace

extern "C" dxc_dcomp *dxc_dcomp_create(void) {
    dxc_dcomp *comp = static_cast<dxc_dcomp *>(calloc(1, sizeof *comp));
    if (comp == nullptr) {
        return nullptr;
    }
    if (!make_device(comp)) {
        free(comp);
        return nullptr;
    }
    return comp;
}

extern "C" int dxc_dcomp_attach(dxc_dcomp *comp, HWND window, IUnknown *d3d11_device, int32_t width,
                                int32_t height, int surfaces) {
    if (comp == nullptr || comp->device == nullptr || window == nullptr || d3d11_device == nullptr) {
        return 1;
    }
    release_tree(comp);
    release(comp->render_device);
    comp->window = window;
    comp->render_device = d3d11_device;
    d3d11_device->AddRef();
    comp->width = width;
    comp->height = height;
    comp->room_width = width;
    comp->room_height = height;
    comp->count = surfaces < 1 ? 1 : surfaces > DXC_DCOMP_MAX_SURFACES ? DXC_DCOMP_MAX_SURFACES : surfaces;
    // Dark grey until the first frame says what colour its edge is (dxc_dcomp_set_backing).
    if (comp->backing_asked == 0) comp->backing_asked = 0xFF202020u;
    if (!build_tree(comp)) {
        return 2;
    }
    HRESULT committed = comp->device->Commit();
    fprintf(stderr, "compose-rust: dcomp: first Commit 0x%08lx\n", static_cast<unsigned long>(committed));
    if (FAILED(committed)) {
        release_tree(comp);
        return 3;
    }
    return 0;
}

extern "C" int dxc_dcomp_begin_draw(dxc_dcomp *comp, int32_t width, int32_t height, void **texture, int32_t *x,
                                    int32_t *y) {
    *texture = nullptr;
    if (comp == nullptr || comp->visual == nullptr || comp->surfaces[0] == nullptr || width <= 0 || height <= 0) {
        return 1;
    }
    // The surface after the one shown; drawn again, until a commit, if a frame is written twice.
    int index = comp->count == 1 ? comp->shown : (comp->shown + 1) % comp->count;
    IDCompositionVirtualSurface *surface = comp->surfaces[index];
    if (width > comp->room_widths[index] || height > comp->room_heights[index]) {
        int32_t room_width = width > comp->room_widths[index] ? width : comp->room_widths[index];
        int32_t room_height = height > comp->room_heights[index] ? height : comp->room_heights[index];
        HRESULT resized = surface->Resize(static_cast<UINT>(room_width), static_cast<UINT>(room_height));
        if (FAILED(resized)) {
            fprintf(stderr, "compose-rust: dcomp: Resize %dx%d failed with 0x%08lx\n", (int)room_width,
                    (int)room_height, static_cast<unsigned long>(resized));
            return 2;
        }
        comp->room_widths[index] = room_width;
        comp->room_heights[index] = room_height;
        fprintf(stderr, "compose-rust: dcomp: surface %d room -> %dx%d\n", index, (int)room_width,
                (int)room_height);
    }
    comp->width = width;
    comp->height = height;
    if (comp->clip_width != width || comp->clip_height != height) {
        HRESULT clipped = apply_clip(comp);
        if (FAILED(clipped)) {
            fprintf(stderr, "compose-rust: dcomp: SetClip %dx%d failed with 0x%08lx\n", (int)width, (int)height,
                    static_cast<unsigned long>(clipped));
        }
    }
    if (comp->stretched) {
        // The frame drawn now is the view's own size: shown at 1:1 again from its commit.
        D2D_MATRIX_3X2_F identity = {};
        identity._11 = 1.0f;
        identity._22 = 1.0f;
        HRESULT reset = comp->visual->SetTransform(identity);
        if (FAILED(reset)) {
            fprintf(stderr, "compose-rust: dcomp: SetTransform back to 1:1 failed with 0x%08lx\n",
                    static_cast<unsigned long>(reset));
        }
        comp->stretched = false;
    }
    RECT whole = {0, 0, width, height};
    POINT offset = {0, 0};
    HRESULT began = surface->BeginDraw(&whole, __uuidof(ID3D11Texture2D), texture, &offset);
    if (FAILED(began) || *texture == nullptr) {
        fprintf(stderr, "compose-rust: dcomp: BeginDraw %dx%d failed with 0x%08lx\n", (int)width, (int)height,
                static_cast<unsigned long>(began));
        *texture = nullptr;
        return 3;
    }
    comp->next = index;
    *x = static_cast<int32_t>(offset.x);
    *y = static_cast<int32_t>(offset.y);
    return 0;
}

extern "C" int dxc_dcomp_end_draw(dxc_dcomp *comp, void *texture) {
    if (texture != nullptr) {
        static_cast<IUnknown *>(texture)->Release();
    }
    if (comp == nullptr || comp->surfaces[comp->next] == nullptr) {
        return 1;
    }
    HRESULT ended = comp->surfaces[comp->next]->EndDraw();
    if (FAILED(ended)) {
        fprintf(stderr, "compose-rust: dcomp: EndDraw failed with 0x%08lx\n", static_cast<unsigned long>(ended));
        return 2;
    }
    // The visual shows this surface from the next commit, with the clip set above.
    if (comp->next != comp->shown) {
        HRESULT content = comp->visual->SetContent(comp->surfaces[comp->next]);
        if (FAILED(content)) {
            fprintf(stderr, "compose-rust: dcomp: SetContent failed with 0x%08lx\n",
                    static_cast<unsigned long>(content));
            return 3;
        }
    }
    return 0;
}

extern "C" int dxc_dcomp_stretch_shown(dxc_dcomp *comp, int32_t view_width, int32_t view_height) {
    if (comp == nullptr || comp->visual == nullptr || comp->clip_width <= 0 || comp->clip_height <= 0 ||
        view_width <= 0 || view_height <= 0) {
        return 1;
    }
    // As DSA's shown_frame_placement: the frame keeps its proportions, fits the view and sits in
    // its middle. The clip stays the frame's own, in the visual's coordinates, so it scales too.
    float scale_x = static_cast<float>(view_width) / static_cast<float>(comp->clip_width);
    float scale_y = static_cast<float>(view_height) / static_cast<float>(comp->clip_height);
    float scale = scale_x < scale_y ? scale_x : scale_y;
    D2D_MATRIX_3X2_F placed = {};
    placed._11 = scale;
    placed._22 = scale;
    placed._31 = (static_cast<float>(view_width) - scale * static_cast<float>(comp->clip_width)) / 2.0f;
    placed._32 = (static_cast<float>(view_height) - scale * static_cast<float>(comp->clip_height)) / 2.0f;
    HRESULT set = comp->visual->SetTransform(placed);
    if (FAILED(set)) {
        fprintf(stderr, "compose-rust: dcomp: SetTransform stretching %dx%d into %dx%d failed with 0x%08lx\n",
                (int)comp->clip_width, (int)comp->clip_height, (int)view_width, (int)view_height,
                static_cast<unsigned long>(set));
        return 2;
    }
    comp->visual->SetBitmapInterpolationMode(DCOMPOSITION_BITMAP_INTERPOLATION_MODE_LINEAR);
    comp->stretched = true;
    return 0;
}

extern "C" int dxc_dcomp_commit(dxc_dcomp *comp) {
    if (comp == nullptr) {
        return -1;
    }
    HRESULT committed = comp->device != nullptr && comp->visual != nullptr ? comp->device->Commit() : E_POINTER;
    if (SUCCEEDED(committed)) {
        comp->shown = comp->next;
        return 0;
    }
    fprintf(stderr, "compose-rust: dcomp: Commit failed with 0x%08lx, making the device again\n",
            static_cast<unsigned long>(committed));
    // A device can be lost (a display driver update, a session change). Made again once, with
    // the window, size and rendering device it had; a second failure is the caller's to report.
    release_device(comp);
    if (!make_device(comp) || !build_tree(comp)) {
        fprintf(stderr, "compose-rust: dcomp: the device could not be made again\n");
        return -1;
    }
    HRESULT again = comp->device->Commit();
    if (FAILED(again)) {
        fprintf(stderr, "compose-rust: dcomp: Commit on the new device failed with 0x%08lx\n",
                static_cast<unsigned long>(again));
        return -1;
    }
    fprintf(stderr, "compose-rust: dcomp: the device was made again\n");
    return 1;
}

extern "C" int dxc_dcomp_set_backing(dxc_dcomp *comp, uint32_t bgra) {
    if (comp == nullptr) {
        return 1;
    }
    comp->backing_asked = bgra | 0xFF000000u;
    if (comp->backing_surface == nullptr) {
        return 1;
    }
    if (comp->backing_bgra == comp->backing_asked) {
        return 0;
    }
    HRESULT filled = fill_backing(comp, comp->backing_asked);
    if (FAILED(filled)) {
        fprintf(stderr, "compose-rust: dcomp: filling the backing failed with 0x%08lx\n",
                static_cast<unsigned long>(filled));
        return 2;
    }
    return 0;
}

extern "C" void dxc_dcomp_wait(dxc_dcomp *comp) {
    if (comp != nullptr && comp->device != nullptr) {
        comp->device->WaitForCommitCompletion();
    }
}

extern "C" void dxc_dcomp_release(dxc_dcomp *comp) {
    if (comp == nullptr) {
        return;
    }
    release_device(comp);
    release(comp->render_device);
    free(comp);
}
