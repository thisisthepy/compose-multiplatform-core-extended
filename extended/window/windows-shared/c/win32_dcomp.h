#ifndef DXC_WIN32_DCOMP_H
#define DXC_WIN32_DCOMP_H

// A plain C ABI onto DirectComposition, for win32_window.c to call without including
// dcomp.h itself. dcomp.h declares overloaded COM methods (several SetClip and SetOffsetX,
// for instance), which is C++ only; the SDK gives it no C-mode vtable struct the way d3d12.h
// and dxgi1_4.h have one. Everything that names a DComp type lives in win32_dcomp.cpp,
// built as C++ without exceptions or RTTI, and crosses this boundary as HWND, IUnknown* or
// a plain int.
//
// One dxc_dcomp per window: the device, the target on the window and the one visual whose
// content is the window's composition swapchain.

#include <windows.h>
#include <unknwn.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct dxc_dcomp dxc_dcomp;

/**
 * Makes a composition device (DCompositionCreateDevice2, a desktop device), or answers NULL
 * where this machine has none. Called before the window exists, because the window's
 * extended style (WS_EX_NOREDIRECTIONBITMAP) depends on the answer.
 */
dxc_dcomp *dxc_dcomp_create(void);

/**
 * Puts a swapchain on a window: a target for the window, a visual, the swapchain as the
 * visual's content and the visual as the target's root, then one commit. Zero on success.
 * Non-zero leaves the device and nothing else, so the caller can release it and fall back.
 */
int dxc_dcomp_attach(dxc_dcomp *comp, HWND window, IUnknown *swapchain);

/** Replaces the visual's content. Zero on success; takes effect at the next commit. */
int dxc_dcomp_set_content(dxc_dcomp *comp, IUnknown *swapchain);

/**
 * Clips the visual to width x height pixels from its top left. Zero on success; takes effect
 * at the next commit, so a present, this clip and a commit land as one change.
 */
int dxc_dcomp_set_clip(dxc_dcomp *comp, int32_t width, int32_t height);

/**
 * Commits what is pending on the visual. Zero on success, 1 when the commit failed and the
 * device, target and visual were made again once (with the same content and clip) and that
 * commit succeeded, -1 when that failed too.
 */
int dxc_dcomp_commit(dxc_dcomp *comp);

/** Lets go of the visual, the target and the device, in that order, and of comp itself. */
void dxc_dcomp_release(dxc_dcomp *comp);

#ifdef __cplusplus
}
#endif

#endif
