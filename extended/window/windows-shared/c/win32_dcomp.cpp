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

struct dxc_dcomp {
    IDCompositionDesktopDevice *device;
    IDCompositionTarget *target;
    IDCompositionVisual2 *visual;
    IDCompositionSurfaceFactory *factory;
    IDCompositionVirtualSurface *surface;
    // What the tree was made for, kept so a commit that fails can make everything again.
    HWND window;
    IUnknown *render_device;
    int32_t width;
    int32_t height;
    // What the surface holds room for. It only grows: a resize of a virtual surface trims or
    // reallocates it, which a drag would pay for on every step, so a smaller frame is written
    // into its top left and the clip shows only that.
    int32_t room_width;
    int32_t room_height;
    // The size the visual is clipped to, as last given to it.
    int32_t clip_width;
    int32_t clip_height;
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
    release(comp->visual);
    release(comp->target);
    release(comp->surface);
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

// The surface factory, the surface, the target, the visual, its content and its clip, on the
// device there is. No commit. Every step's answer is said, once for each tree made (once a
// window, again only if a failed commit makes it again).
bool build_tree(dxc_dcomp *comp) {
    if (comp->device == nullptr || comp->window == nullptr || comp->render_device == nullptr ||
        comp->width <= 0 || comp->height <= 0) {
        return false;
    }
    HRESULT factory = comp->device->CreateSurfaceFactory(comp->render_device, &comp->factory);
    HRESULT surface = SUCCEEDED(factory)
                          ? comp->factory->CreateVirtualSurface(static_cast<UINT>(comp->room_width),
                                                                static_cast<UINT>(comp->room_height),
                                                                DXGI_FORMAT_B8G8R8A8_UNORM, DXGI_ALPHA_MODE_IGNORE,
                                                                &comp->surface)
                          : E_ABORT;
    HRESULT target = SUCCEEDED(surface) ? comp->device->CreateTargetForHwnd(comp->window, TRUE, &comp->target)
                                        : E_ABORT;
    HRESULT visual = SUCCEEDED(target) ? comp->device->CreateVisual(&comp->visual) : E_ABORT;
    HRESULT content = SUCCEEDED(visual) ? comp->visual->SetContent(comp->surface) : E_ABORT;
    HRESULT clipped = SUCCEEDED(content) ? apply_clip(comp) : E_ABORT;
    HRESULT root = SUCCEEDED(clipped) ? comp->target->SetRoot(comp->visual) : E_ABORT;
    fprintf(stderr,
            "compose-rust: dcomp: tree for window %p: CreateSurfaceFactory 0x%08lx CreateVirtualSurface 0x%08lx "
            "(%dx%d) CreateTargetForHwnd 0x%08lx CreateVisual 0x%08lx SetContent 0x%08lx SetClip 0x%08lx "
            "SetRoot 0x%08lx\n",
            static_cast<void *>(comp->window), static_cast<unsigned long>(factory),
            static_cast<unsigned long>(surface), (int)comp->room_width, (int)comp->room_height,
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
                                int32_t height) {
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
    if (comp == nullptr || comp->surface == nullptr || comp->visual == nullptr || width <= 0 || height <= 0) {
        return 1;
    }
    if (width > comp->room_width || height > comp->room_height) {
        int32_t room_width = width > comp->room_width ? width : comp->room_width;
        int32_t room_height = height > comp->room_height ? height : comp->room_height;
        HRESULT resized = comp->surface->Resize(static_cast<UINT>(room_width), static_cast<UINT>(room_height));
        if (FAILED(resized)) {
            fprintf(stderr, "compose-rust: dcomp: Resize %dx%d failed with 0x%08lx\n", (int)room_width,
                    (int)room_height, static_cast<unsigned long>(resized));
            return 2;
        }
        comp->room_width = room_width;
        comp->room_height = room_height;
        fprintf(stderr, "compose-rust: dcomp: surface room -> %dx%d\n", (int)room_width, (int)room_height);
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
    RECT whole = {0, 0, width, height};
    POINT offset = {0, 0};
    HRESULT began = comp->surface->BeginDraw(&whole, __uuidof(ID3D11Texture2D), texture, &offset);
    if (FAILED(began) || *texture == nullptr) {
        fprintf(stderr, "compose-rust: dcomp: BeginDraw %dx%d failed with 0x%08lx\n", (int)width, (int)height,
                static_cast<unsigned long>(began));
        *texture = nullptr;
        return 3;
    }
    *x = static_cast<int32_t>(offset.x);
    *y = static_cast<int32_t>(offset.y);
    return 0;
}

extern "C" int dxc_dcomp_end_draw(dxc_dcomp *comp, void *texture) {
    if (texture != nullptr) {
        static_cast<IUnknown *>(texture)->Release();
    }
    if (comp == nullptr || comp->surface == nullptr) {
        return 1;
    }
    HRESULT ended = comp->surface->EndDraw();
    if (FAILED(ended)) {
        fprintf(stderr, "compose-rust: dcomp: EndDraw failed with 0x%08lx\n", static_cast<unsigned long>(ended));
        return 2;
    }
    return 0;
}

extern "C" int dxc_dcomp_commit(dxc_dcomp *comp) {
    if (comp == nullptr) {
        return -1;
    }
    HRESULT committed = comp->device != nullptr && comp->visual != nullptr ? comp->device->Commit() : E_POINTER;
    if (SUCCEEDED(committed)) {
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
