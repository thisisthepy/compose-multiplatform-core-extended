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

bool apply_clip(dxc_dcomp *comp) {
    if (comp->visual == nullptr || comp->clip_width <= 0 || comp->clip_height <= 0) {
        return true;
    }
    D2D_RECT_F clip;
    clip.left = 0.0f;
    clip.top = 0.0f;
    clip.right = static_cast<float>(comp->clip_width);
    clip.bottom = static_cast<float>(comp->clip_height);
    return SUCCEEDED(comp->visual->SetClip(clip));
}

// The target, the visual, its content and its clip, on the device there is. No commit.
bool build_tree(dxc_dcomp *comp) {
    if (comp->device == nullptr || comp->window == nullptr || comp->content == nullptr) {
        return false;
    }
    if (FAILED(comp->device->CreateTargetForHwnd(comp->window, TRUE, &comp->target)) ||
        FAILED(comp->device->CreateVisual(&comp->visual)) ||
        FAILED(comp->visual->SetContent(comp->content)) ||
        !apply_clip(comp) ||
        FAILED(comp->target->SetRoot(comp->visual))) {
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
    if (FAILED(comp->device->Commit())) {
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

extern "C" int dxc_dcomp_set_clip(dxc_dcomp *comp, int32_t width, int32_t height) {
    if (comp == nullptr) {
        return 1;
    }
    comp->clip_width = width;
    comp->clip_height = height;
    return apply_clip(comp) ? 0 : 1;
}

extern "C" int dxc_dcomp_commit(dxc_dcomp *comp) {
    if (comp == nullptr) {
        return -1;
    }
    if (comp->device != nullptr && comp->visual != nullptr && SUCCEEDED(comp->device->Commit())) {
        return 0;
    }
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
