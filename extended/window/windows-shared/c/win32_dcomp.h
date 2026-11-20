#ifndef DXC_WIN32_DCOMP_H
#define DXC_WIN32_DCOMP_H

// A plain C ABI onto DirectComposition, for win32_window.c to call without including
// dcomp.h itself. dcomp.h declares overloaded COM methods (several SetClip and SetOffsetX,
// for instance), which is C++ only; the SDK gives it no C-mode vtable struct the way d3d12.h
// and dxgi1_4.h have one. Everything that names a DComp type lives in win32_dcomp.cpp,
// built as C++ without exceptions or RTTI, and crosses this boundary as HWND, IUnknown*,
// void* or a plain int.
//
// One dxc_dcomp per window: the device, the target on the window, one visual, and the
// visual's content, a virtual surface. A surface, not a swapchain: what is drawn into a
// surface, the surface's size and the visual's clip all reach the screen with the commit
// that follows, as one change, where a swapchain's present reaches DWM on its own and can
// land a composition before or after the commit that sizes it.

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
 * Puts a width x height surface on a window: a surface factory on d3d11_device (an
 * ID3D11Device), a virtual surface from it, a target for the window, a visual showing the
 * surface clipped to its size, then one commit. Zero on success. Non-zero leaves the device
 * and nothing else, so the caller can release it and fall back. Each step's answer is logged.
 */
int dxc_dcomp_attach(dxc_dcomp *comp, HWND window, IUnknown *d3d11_device, int32_t width, int32_t height);

/**
 * Begins drawing a frame of width x height: the surface takes that size (and the visual that
 * clip) at the next commit, and *texture is an ID3D11Texture2D to write the frame into at
 * (*x, *y). Zero on success; then dxc_dcomp_end_draw has to follow.
 */
int dxc_dcomp_begin_draw(dxc_dcomp *comp, int32_t width, int32_t height, void **texture, int32_t *x,
                         int32_t *y);

/** Ends the drawing begun above and releases its texture. Zero on success. */
int dxc_dcomp_end_draw(dxc_dcomp *comp, void *texture);

/**
 * Commits what is pending: the frame drawn, its size and its clip. Zero on success, 1 when
 * the commit failed and the device, its surface, target and visual were made again once and
 * that commit succeeded (the surface is then empty and the caller draws again), -1 when that
 * failed too. Every failure is logged with its HRESULT.
 */
int dxc_dcomp_commit(dxc_dcomp *comp);

/** Lets go of the surface, the visual, the target and the device, and of comp itself. */
void dxc_dcomp_release(dxc_dcomp *comp);

#ifdef __cplusplus
}
#endif

#endif
