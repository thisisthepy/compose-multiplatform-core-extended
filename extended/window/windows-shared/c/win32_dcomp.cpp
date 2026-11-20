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

#include <dcomp.h>
#include <stdio.h>
#include <stdlib.h>

struct dxc_dcomp {
    IDCompositionDesktopDevice *device;
    IDCompositionTarget *target;
    IDCompositionVisual2 *visual;
    // What the visual was given, kept so a commit that fails can make everything again.
    HWND window;
    IUnknown *content;
    int32_t clip_width;
    int32_t clip_height;
    int32_t buffer_width;
    int32_t buffer_height;
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

void release_tree(dxc_dcomp *comp) {
    if (comp->visual != nullptr) {
        comp->visual->Release();
        comp->visual = nullptr;
    }
    if (comp->target != nullptr) {
        comp->target->Release();
        comp->target = nullptr;
    }
}

void release_device(dxc_dcomp *comp) {
    release_tree(comp);
    if (comp->device != nullptr) {
        comp->device->Release();
        comp->device = nullptr;
    }
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

// The scale that undoes the swapchain's stretch of its source size to its buffer size, and the
// clip, which is in the visual's coordinates (before that scale): the whole buffer, which the
// scale brings to clip_width x clip_height on the window.
bool apply_clip(dxc_dcomp *comp) {
    if (comp->visual == nullptr || comp->clip_width <= 0 || comp->clip_height <= 0 ||
        comp->buffer_width <= 0 || comp->buffer_height <= 0) {
        return true;
    }
    D2D_MATRIX_3X2_F scale;
    scale._11 = static_cast<float>(comp->clip_width) / static_cast<float>(comp->buffer_width);
    scale._12 = 0.0f;
    scale._21 = 0.0f;
    scale._22 = static_cast<float>(comp->clip_height) / static_cast<float>(comp->buffer_height);
    scale._31 = 0.0f;
    scale._32 = 0.0f;
    HRESULT scaled = comp->visual->SetTransform(scale);
    D2D_RECT_F clip;
    clip.left = 0.0f;
    clip.top = 0.0f;
    clip.right = static_cast<float>(comp->buffer_width);
    clip.bottom = static_cast<float>(comp->buffer_height);
    HRESULT clipped = comp->visual->SetClip(clip);
    if (FAILED(scaled) || FAILED(clipped)) {
        fprintf(stderr, "compose-rust: dcomp: SetTransform 0x%08lx SetClip 0x%08lx for %dx%d of %dx%d\n",
                static_cast<unsigned long>(scaled), static_cast<unsigned long>(clipped), (int)comp->clip_width,
                (int)comp->clip_height, (int)comp->buffer_width, (int)comp->buffer_height);
        return false;
    }
    return true;
}

// The target, the visual, its content and its clip, on the device there is. No commit.
bool build_tree(dxc_dcomp *comp) {
    if (comp->device == nullptr || comp->window == nullptr || comp->content == nullptr) {
        return false;
    }
    // Every step's answer is said, once for each tree made (once a window, again only if a
    // failed commit makes it again), so a window that shows nothing is not a mystery.
    HRESULT target = comp->device->CreateTargetForHwnd(comp->window, TRUE, &comp->target);
    HRESULT visual = SUCCEEDED(target) ? comp->device->CreateVisual(&comp->visual) : E_ABORT;
    HRESULT content = SUCCEEDED(visual) ? comp->visual->SetContent(comp->content) : E_ABORT;
    bool clipped = SUCCEEDED(content) && apply_clip(comp);
    HRESULT root = clipped ? comp->target->SetRoot(comp->visual) : E_ABORT;
    fprintf(stderr,
            "compose-rust: dcomp: tree for window %p: CreateTargetForHwnd 0x%08lx CreateVisual 0x%08lx "
            "SetContent 0x%08lx transform and clip %s SetRoot 0x%08lx, showing %dx%d of %dx%d buffers\n",
            static_cast<void *>(comp->window), static_cast<unsigned long>(target),
            static_cast<unsigned long>(visual), static_cast<unsigned long>(content), clipped ? "ok" : "failed",
            static_cast<unsigned long>(root), (int)comp->clip_width, (int)comp->clip_height,
            (int)comp->buffer_width, (int)comp->buffer_height);
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

extern "C" int dxc_dcomp_attach(dxc_dcomp *comp, HWND window, IUnknown *swapchain) {
    if (comp == nullptr || comp->device == nullptr || window == nullptr || swapchain == nullptr) {
        return 1;
    }
    release_tree(comp);
    if (comp->content != nullptr) {
        comp->content->Release();
    }
    comp->window = window;
    comp->content = swapchain;
    swapchain->AddRef();
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

extern "C" int dxc_dcomp_set_content(dxc_dcomp *comp, IUnknown *swapchain) {
    if (comp == nullptr || swapchain == nullptr) {
        return 1;
    }
    swapchain->AddRef();
    if (comp->content != nullptr) {
        comp->content->Release();
    }
    comp->content = swapchain;
    if (comp->visual == nullptr) {
        return 1;
    }
    return SUCCEEDED(comp->visual->SetContent(swapchain)) ? 0 : 1;
}

extern "C" int dxc_dcomp_set_clip(dxc_dcomp *comp, int32_t width, int32_t height, int32_t buffer_width,
                                  int32_t buffer_height) {
    if (comp == nullptr) {
        return 1;
    }
    comp->clip_width = width;
    comp->clip_height = height;
    comp->buffer_width = buffer_width;
    comp->buffer_height = buffer_height;
    return apply_clip(comp) ? 0 : 1;
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
    // the content and the clip it had; a second failure is the caller's to report.
    release_device(comp);
    if (!make_device(comp) || !build_tree(comp) || FAILED(comp->device->Commit())) {
        fprintf(stderr, "compose-rust: dcomp: commit failed and the device could not be made again\n");
        return -1;
    }
    fprintf(stderr, "compose-rust: dcomp: commit failed, the device was made again\n");
    return 1;
}

extern "C" void dxc_dcomp_release(dxc_dcomp *comp) {
    if (comp == nullptr) {
        return;
    }
    release_device(comp);
    if (comp->content != nullptr) {
        comp->content->Release();
        comp->content = nullptr;
    }
    free(comp);
}
