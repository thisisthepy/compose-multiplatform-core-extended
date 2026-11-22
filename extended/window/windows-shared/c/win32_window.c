// A window of our own on Windows, with a Direct3D 12 swapchain in it and no toolkit
// between.
//
// The pair of `appkit_window.m`. The renderer draws with Skia into a swapchain buffer;
// everything between that buffer and the screen belongs to Win32 and DXGI, and this file
// is what talks to them: a window class, a window, an adapter, a device, a queue and a
// swapchain. What the toolkit was doing here was translating the same few things into
// Java and back, and each translation has been somewhere a frame went wrong.
//
// Nothing here draws. The pixels are Skia's, as they already were.
//
// The same native symbols the macOS file exports, because the Kotlin side reaches them by
// name and only one of the two files is ever compiled into an image. What the five
// pointers in `struct dxc_native_window` mean is this platform's business; what
// `struct dxc_event` looks like is not, and it is declared here field for field as the
// macOS file declares it so that one piece of Kotlin can read either.
//
// Two of the four walls the macOS window ran into are not here. A window may be created
// on any thread on Windows, and the thread that created it is the thread its messages are
// delivered to, so there is no main-thread hop: the renderer opens its own window on the
// thread it draws from. Nor is there a view that hands out a drawable only inside its own
// callback; a swapchain answers whenever it is asked. The other two walls stand. Skia's
// recording is not its submission, and a word value lives only in straight-line code, and
// both of those are the Kotlin side's to keep.

// Windows 10, because the per-monitor DPI calls below arrived with it and a window that
// asks the monitor how big a point is was the whole reason for asking.
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0A00
#endif
#define WIN32_LEAN_AND_MEAN
// Every call here is the wide one by name. This is for the few things that are constants
// rather than calls, `IDC_ARROW` among them, which resolve to one width or the other
// through a macro and would otherwise hand a narrow string to a wide function.
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
// The Direct3D and DXGI interfaces as C macros. Without this the headers offer only the
// C++ member functions, and there is no C++ in this project's boundary.
#define COBJMACROS

#include <windows.h>
#include <windowsx.h>
#include <imm.h>
#include <d3d12.h>
#include <dxgi1_4.h>
#include <dwmapi.h>
#include <d3d11.h>
#include <d3d11on12.h>
#include <d3d11_4.h>
#include <ole2.h>
#include <shellapi.h>
#include <winreg.h>
#include <psapi.h>
#include <uiautomation.h>
#include <uiautomationcoreapi.h>
#include <oleauto.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdlib.h>
#include <stddef.h>

#include "win32_buttons.h"
#include "win32_resize.h"
#include "win32_wheel.h"
#include "win32_keys.h"
#include "win32_ime_text.h"
#include "win32_caption.h"
#include "win32_multi.h"
#include "win32_placement.h"
#include "win32_popup_menu.h"
#include "win32_scheme.h"
#include "win32_edit_menu.h"
#include "win32_dpi.h"
#include "win32_dcomp.h"
#include "win32_native_views.h"

// What happened in the window, waiting to be read.
//
// A queue and not a call. Calling Kotlin from here would mean the shell deciding when the
// Host runs, and the Host's state belongs to the thread that draws; it is also a call
// that can arrive in the middle of a message the window is still handling, which is a
// place no renderer wants to be resumed. The window writes events down and the renderer
// empties them once a frame, which is the same shape the macOS side already has.
enum {
    DXC_EVENT_POINTER_MOVE = 1,
    DXC_EVENT_POINTER_DOWN = 2,
    DXC_EVENT_POINTER_UP = 3,
    DXC_EVENT_SCROLL = 4,
    DXC_EVENT_KEY_DOWN = 5,
    DXC_EVENT_KEY_UP = 6,
    DXC_EVENT_TEXT_COMMIT = 7,
    DXC_EVENT_TEXT_COMPOSE = 8,
    // Files over the window, let go on it, and gone from it without being let go.
    DXC_EVENT_FILES_ENTERED = 10,
    DXC_EVENT_FILES_DROPPED = 11,
    DXC_EVENT_FILES_EXITED = 12,
    DXC_EVENT_TEXT_SCALE_CHANGED = 18,
    // A native view slot took or lost keyboard focus (the slot id in key_code), and the
    // pointer went from the scene into a slot or came back (win32_native_views.h).
    DXC_EVENT_NATIVE_VIEW_FOCUS_IN = 19,
    DXC_EVENT_NATIVE_VIEW_FOCUS_OUT = 20,
    DXC_EVENT_POINTER_ENTER = 21,
    DXC_EVENT_POINTER_EXIT = 22,
    // A reader asked a control to do something; see the header.
    DXC_EVENT_ACCESSIBILITY_ACTION = 23,
};

// Room for what an input method is composing, which is a syllable or a word and never a
// document. Declared here as well as on the other desktop because one Kotlin reader reads
// both, and a test compares the two declarations for exactly that reason.
#define DXC_TEXT_BYTES 96

struct dxc_event {
    int32_t kind;
    // In pixels from the top left of the client area, which is what a scene measures in.
    float x;
    float y;
    int32_t buttons;
    int32_t modifiers;
    // The platform's own key number, and the character it would type. Which Compose key
    // that is gets decided on the other side, where the table lives.
    int32_t key_code;
    int32_t code_point;
    // UTF-8, ending at the first zero. Empty for everything that is not text.
    //
    char text[DXC_TEXT_BYTES];
    // When the system says a pointer message happened, GetMessageTime read unsigned, in
    // milliseconds, so a press read after a slow frame still counts towards a double click by
    // when it was made. Zero for everything else, which the scene times on arrival.
    int64_t time_millis;
};

// Room for a burst rather than for a session. A queue that fills is a queue nobody is
// draining, and holding a thousand stale mouse moves helps no one.
#define DXC_EVENT_CAPACITY 256

// One buffer, as Flutter's window swapchain has (ANGLE's NativeWindow11Win32 for a
// window without DirectComposition). It is a copy model swapchain: Present copies the
// buffer into the window's own redirection surface, which DWM resizes with the window,
// so the window's size and its content reach the screen together. Skia draws into a
// Direct3D 12 texture of the same size, which is copied into that buffer each frame.
#define DXC_BUFFER_COUNT 1
// The composition route's swapchain is flip model (DXGI_SWAP_EFFECT_FLIP_SEQUENTIAL), which
// a composition swapchain has to be, and a flip model swapchain has at least two buffers. It is
// never shown (the visual shows a surface), so it is made as small as a swapchain can be.
#define DXC_COMP_BUFFER_COUNT 2
#define DXC_COMP_SWAPCHAIN_SIZE 8

// The format the swapchain and Skia have to agree on. Named here as a number because the
// Kotlin side has to pass the same one to Skia and cannot see this header.
// BGRA rather than RGBA because it is the one format a swapchain can hand GDI a device
// context for (DXGI_SWAP_CHAIN_FLAG_GDI_COMPATIBLE), which the CPU resize frames need.
#define DXC_SWAPCHAIN_FORMAT DXGI_FORMAT_B8G8R8A8_UNORM

// ---------------------------------------------------------------------------
// Several windows in one process (FR-53)
// ---------------------------------------------------------------------------
//
// Everything one window keeps lives in a DxcWin, allocated when the window is made and
// found from its HWND (GWLP_USERDATA for the window's own messages, the table below for the
// handle a caller holds). The code that follows reads and writes it under the names it has
// always used: each `dxc_x` below is a macro for a field of the window the code is running
// for, which is `dxc_w`. `dxc_w` is set on entry to everything that can run for a window (its
// window procedure, its drop target, a call that names its window) and put back on the way
// out, and it is per thread, so a thread that is not the window's never sees another's.
//
// What the process keeps once is not here: the window class, the draw callback the renderer
// registered, the options the next window will open with, and the switches that turn
// diagnostics on from the environment.

#define DXC_MAX_ELEMENTS 256
#define DXC_DROPPED_BYTES (64 * 1024)

#define DXC_VALUE_BYTES 256

// One element as the scene describes it; the header says what each field is.
struct dxc_element {
    int32_t role;
    // In window points from the top left of the client area.
    float x;
    float y;
    float width;
    float height;
    char label[DXC_TEXT_BYTES];
    int32_t id;
    int32_t parent_id;
    int32_t states;
    int32_t actions;
    int32_t flags;
    float range_min;
    float range_max;
    float range_value;
    char value[DXC_VALUE_BYTES];
    char hint[DXC_TEXT_BYTES];
};

// The Kotlin writers (Win32Records.kt by cinterop's layout, Win32GraalWindow.kt by offset)
// put every field where these say.
_Static_assert(offsetof(struct dxc_element, label) == 20, "dxc_element label offset");
_Static_assert(offsetof(struct dxc_element, id) == 116, "dxc_element id offset");
_Static_assert(offsetof(struct dxc_element, flags) == 132, "dxc_element flags offset");
_Static_assert(offsetof(struct dxc_element, range_min) == 136, "dxc_element range offset");
_Static_assert(offsetof(struct dxc_element, value) == 148, "dxc_element value offset");
_Static_assert(offsetof(struct dxc_element, hint) == 404, "dxc_element hint offset");
_Static_assert(sizeof(struct dxc_element) == 500, "dxc_element size");

enum {
    DXC_ELEMENT_HAS_VALUE = 1 << 0,
    DXC_ELEMENT_HAS_RANGE = 1 << 1,
    DXC_ELEMENT_HAS_HINT = 1 << 2,
};

// How many SET_VALUE requests may wait for the renderer at once. A reader sets a value and
// waits for it, so a few is many; one beyond it is refused rather than losing another's text.
#define DXC_A11Y_ARGUMENTS 16

/** How a window was asked to be made. Each window keeps the copy it opened with. */
struct dxc_window_options {
    int32_t resizable;
    int32_t min_width;
    int32_t min_height;
    int32_t system_chrome;
    int32_t backdrop;
    /** The application owns the caption strip (`Chrome::FullSize`); see dxc_caption_zone_is_system_caption. */
    int32_t app_caption;
    /** The caption strip's height in dip, 0 for DXC_CAPTION_HEIGHT_DIP (FR-19.7). */
    int32_t caption_dip;
};

typedef struct DxcRootProvider DxcRootProvider;
typedef struct DxcWin DxcWin;

/** The drop target OLE is given for one window; OLE hands the first member back to its methods. */
struct dxc_drop_target {
    IDropTarget iface;
    DxcWin *win;
};

// The native view slots of one window (common's NativeViews; dxc_native_view_* below; the
// arithmetic in win32_native_views.h). A slot is the caller's window (the view) inside a
// container this window makes, a child of the top-level window above the scene. What the
// caller asks for is written down and given to the two windows with the next frame, after it
// is presented, so a view moves on the screen with the frame its place was laid out in.
#define DXC_NV_MAX_SLOTS 64

struct dxc_nv_slot {
    int used;
    int32_t id;
    HWND view;
    HWND container;
    // The view's WS_CHILD and WS_POPUP bits before it was attached, given back when it leaves.
    DWORD view_style;
    // What the caller asked for.
    struct dxc_nv_rect frame;
    struct dxc_nv_rect bounds;
    int visible;
    // The holes cut out of the container, in its own coordinates, waiting to be set; NULL
    // with region_changed set is no region. wants_region is whether one is set or waiting.
    HRGN region;
    int region_changed;
    int wants_region;
    int pending;
    // What the two windows were last given.
    int shown;
    int placed;
    struct dxc_nv_rect placed_bounds;
    struct dxc_nv_rect placed_view;
};

struct dxc_nv_slots {
    struct dxc_nv_slot slots[DXC_NV_MAX_SLOTS];
    int32_t count;
    int pending;
    struct dxc_nv_focus focus;
    // The window inside a slot that had the focus last, given it back when the top-level
    // window is activated again (Windows gives an activated window the focus itself).
    HWND focus_window;
    // The pointer is over a slot, and the scene was told it left.
    int pointer_in_view;
    // The swapchain's child window clips its siblings, so the slots stay above it.
    int styles_set;
};

struct DxcWin {
    struct dxc_event events[DXC_EVENT_CAPACITY];
    int event_head;
    int event_count;

    HWND window;
    HWND surface;
    IDXGISwapChain1 *swapchain;
    // The composition route: the window has no GDI redirection surface
    // (WS_EX_NOREDIRECTIONBITMAP) and no child window, and shows one DirectComposition visual
    // whose content is a virtual surface (win32_dcomp.h). Every frame is written into the
    // surface and committed: the frame, the surface's size and the visual's clip reach DWM
    // with that commit as one change. The swapchain made on this route is never shown; it is
    // the handle the renderer passes back with each frame. 0 on the GDI route: the redirection
    // surface and the swapchain's child window (surface above), as before the composition
    // route existed (DXC_DCOMP=0, or no device).
    int on_comp;
    dxc_dcomp *comp;
    // The size of the surface's last frame.
    int32_t comp_surface_width;
    int32_t comp_surface_height;
    // The size whose CPU frame drawn ahead is already in the surface, and whether it was also
    // committed there (in WM_NCCALCSIZE), so WM_SIZE has nothing left to send.
    int32_t comp_staged_width;
    int32_t comp_staged_height;
    int comp_staged_committed;
    // A frame has been shown, so WM_PAINT has nothing to draw: the visual keeps it.
    int comp_presented;
    int comp_commit_failed;
    int comp_first_show_logged;
    // The Direct3D 11 device the surface is written with. Native (comp_native): a device of its
    // own on the same adapter, made with D3D11CreateDevice; the GPU frame reaches it through the
    // draw texture shared from Direct3D 12 and a shared fence (comp_fence12, comp_fence11).
    // Otherwise (DXC_DCOMP_UPLOAD=on12, or where sharing could not be set up) the D3D11On12
    // device the window already has, whose every write to a surface DWM reads has to drain the
    // Direct3D 12 queue behind it first.
    int comp_native;
    int comp_surfaces;
    ID3D11Device *comp_device;
    ID3D11DeviceContext *comp_context;
    ID3D11DeviceContext4 *comp_context4;
    ID3D12Fence *comp_fence12;
    ID3D11Fence *comp_fence11;
    UINT64 comp_fence_value;
    ID3D11Texture2D *comp_shared;
    ID3D12Resource *comp_shared_from;
    ID3D11Device *d3d11;
    ID3D11DeviceContext *d3d11_context;
    ID3D11On12Device *on12;
    ID3D11Resource *wrapped;
    UINT swapchain_flags;
    HANDLE latency_wait;
    int drawing;
    ID3D12Device *device;
    ID3D12CommandQueue *queue;
    ID3D12Resource *buffers[DXC_BUFFER_COUNT];
    ID3D12CommandAllocator *allocator;
    ID3D12GraphicsCommandList *commands;
    ID3D12Fence *fence;
    HANDLE fence_signalled;
    UINT64 fence_value;
    UINT64 last_list_mark;
    UINT frame_index;
    struct dxc_resize sizing;
    int window_gone;
    int resizing;
    int32_t resize_target_width;
    int32_t resize_target_height;
    int32_t presented_width;
    int32_t presented_height;
    int32_t texture_width;
    int32_t texture_height;
    IDXGIAdapter1 *adapter;
    LUID device_luid;
    HMONITOR last_monitor;
    WPARAM last_size_kind;

    int ime_composing;
    // Set while a cancel this window asked of the input method is being answered.
    int ime_cancelling;
    WCHAR pending_high_surrogate;
    LPCWSTR cursor;
    LONG ime_spot_x;
    LONG ime_spot_y;
    // What dxc_native_set_edit_menu last said (DXC_EDIT_MENU_*): 0 while no text field has
    // focus, and then a right click puts up no menu.
    int32_t edit_menu;
    // The text size percent this window last reported (read when it opened), plus one.
    // Per window, because the settings broadcast reaches every window and each reports it.
    int32_t text_scale_seen;

    struct dxc_element a11y_elements[DXC_MAX_ELEMENTS];
    int32_t a11y_count;
    int a11y_dirty;
    int a11y_update_posted;
    DxcRootProvider *root_provider;
    // The new values readers set, oldest first, one per SET_VALUE event not yet read.
    char *a11y_arguments[DXC_A11Y_ARGUMENTS];
    int32_t a11y_argument_head;
    int32_t a11y_argument_count;

    int32_t presents_in_change;
    int last_blit;
    int last_validated;
    LARGE_INTEGER blit_time;
    int cpu_mode;
    HDC dib_dc;
    HBITMAP dib;
    HGDIOBJ dib_previous;
    void *dib_bits;
    int32_t dib_width;
    int32_t dib_height;
    int32_t raster_width;
    int32_t raster_height;
    int gpu_return_posted;
    int32_t mode_switches;
    // A CPU frame drawn in WM_NCCALCSIZE for the size the window is about to be given,
    // waiting in the bitmap for WM_SIZE to copy it; and set while that frame is drawn.
    struct dxc_predrawn predrawn;
    int predrawing;
    // The size the hidden child was given and presented at for the frame drawn ahead.
    int32_t surface_ready_width;
    int32_t surface_ready_height;

    char dropped_paths[DXC_DROPPED_BYTES];
    int32_t dropped_length;
    int drag_has_files;
    struct dxc_drop_target drop;

    struct dxc_window_options options;

    // Several windows. A close the system asked for waits for the application when
    // close_deferred is set; active is what WM_ACTIVATE last said; the three pending flags are
    // what the application has not been told yet and read once a turn.
    int close_deferred;
    int close_requested;
    int active;
    int activation_pending;
    int moved_pending;
    int destroyed;
    int counted;          // holds a place in the process-wide state until it is destroyed
    int has_position;     // the application named where the window opens
    int32_t position_x;   // screen points
    int32_t position_y;

    // Following the pointer: the point of the content, in points, that stays under it.
    int following;
    float follow_anchor_x;
    float follow_anchor_y;
    int follow_ended;      // 0 running or idle, 1 released, 2 cancelled; read once
    int follow_end_pending;

    // The color scheme the application asked of this window's caption (FR-53): 0 follows the
    // system, 1 light, 2 dark. scheme_applied is set once DWM took one; caption_dark is what
    // it was told last.
    int32_t color_scheme;
    int32_t caption_dark;
    int scheme_applied;

    // Where the pointer is between the maximise button and the client area, for a window
    // that draws its own caption (win32_caption.h).
    struct dxc_caption_pointer caption_pointer;

    // The size the window keeps across a change of DPI (win32_dpi.h), and the DPI its size
    // is laid out at: the window's own DPI, taken from WM_DPICHANGED as it arrives. 0 until
    // the window has been given its first size. in_dpi_change is set while WM_DPICHANGED
    // applies the size, whose WM_SIZE is not a size to remember; dpi_restore_pending is set
    // when the DPI changed while the window was maximised, minimised or snapped, so it gets
    // its size back when it is restored.
    struct dxc_dpi_size dpi_size;
    uint32_t layout_dpi;
    int in_dpi_change;
    int dpi_restore_pending;

    // The platform views placed in this window (dxc_native_view_*).
    struct dxc_nv_slots native_views;
};

#define dxc_events (dxc_w->events)
#define dxc_event_head (dxc_w->event_head)
#define dxc_event_count (dxc_w->event_count)
#define dxc_window (dxc_w->window)
#define dxc_surface (dxc_w->surface)
#define dxc_swapchain (dxc_w->swapchain)
#define dxc_on_comp (dxc_w->on_comp)
#define dxc_comp (dxc_w->comp)
#define dxc_comp_surface_width (dxc_w->comp_surface_width)
#define dxc_comp_surface_height (dxc_w->comp_surface_height)
#define dxc_comp_staged_width (dxc_w->comp_staged_width)
#define dxc_comp_staged_height (dxc_w->comp_staged_height)
#define dxc_comp_staged_committed (dxc_w->comp_staged_committed)
#define dxc_comp_presented (dxc_w->comp_presented)
#define dxc_comp_commit_failed (dxc_w->comp_commit_failed)
#define dxc_comp_first_show_logged (dxc_w->comp_first_show_logged)
#define dxc_comp_native (dxc_w->comp_native)
#define dxc_comp_surfaces (dxc_w->comp_surfaces)
#define dxc_comp_device (dxc_w->comp_device)
#define dxc_comp_context (dxc_w->comp_context)
#define dxc_comp_context4 (dxc_w->comp_context4)
#define dxc_comp_fence12 (dxc_w->comp_fence12)
#define dxc_comp_fence11 (dxc_w->comp_fence11)
#define dxc_comp_fence_value (dxc_w->comp_fence_value)
#define dxc_comp_shared (dxc_w->comp_shared)
#define dxc_comp_shared_from (dxc_w->comp_shared_from)
#define dxc_d3d11 (dxc_w->d3d11)
#define dxc_d3d11_context (dxc_w->d3d11_context)
#define dxc_on12 (dxc_w->on12)
#define dxc_wrapped (dxc_w->wrapped)
#define dxc_swapchain_flags (dxc_w->swapchain_flags)
#define dxc_latency_wait (dxc_w->latency_wait)
#define dxc_drawing (dxc_w->drawing)
#define dxc_device (dxc_w->device)
#define dxc_queue (dxc_w->queue)
#define dxc_buffers (dxc_w->buffers)
#define dxc_allocator (dxc_w->allocator)
#define dxc_commands (dxc_w->commands)
#define dxc_fence (dxc_w->fence)
#define dxc_fence_signalled (dxc_w->fence_signalled)
#define dxc_fence_value (dxc_w->fence_value)
#define dxc_last_list_mark (dxc_w->last_list_mark)
#define dxc_frame_index (dxc_w->frame_index)
#define dxc_sizing (dxc_w->sizing)
#define dxc_window_gone (dxc_w->window_gone)
#define dxc_resizing (dxc_w->resizing)
#define dxc_resize_target_width (dxc_w->resize_target_width)
#define dxc_resize_target_height (dxc_w->resize_target_height)
#define dxc_presented_width (dxc_w->presented_width)
#define dxc_presented_height (dxc_w->presented_height)
#define dxc_texture_width (dxc_w->texture_width)
#define dxc_texture_height (dxc_w->texture_height)
#define dxc_adapter (dxc_w->adapter)
#define dxc_device_luid (dxc_w->device_luid)
#define dxc_last_monitor (dxc_w->last_monitor)
#define dxc_last_size_kind (dxc_w->last_size_kind)
#define dxc_ime_composing (dxc_w->ime_composing)
#define dxc_ime_cancelling (dxc_w->ime_cancelling)
#define dxc_pending_high_surrogate (dxc_w->pending_high_surrogate)
#define dxc_cursor (dxc_w->cursor)
#define dxc_ime_spot_x (dxc_w->ime_spot_x)
#define dxc_ime_spot_y (dxc_w->ime_spot_y)
#define dxc_edit_menu (dxc_w->edit_menu)
#define dxc_a11y_elements (dxc_w->a11y_elements)
#define dxc_a11y_count (dxc_w->a11y_count)
#define dxc_a11y_dirty (dxc_w->a11y_dirty)
#define dxc_a11y_update_posted (dxc_w->a11y_update_posted)
#define dxc_root_provider (dxc_w->root_provider)
#define dxc_presents_in_change (dxc_w->presents_in_change)
#define dxc_last_blit (dxc_w->last_blit)
#define dxc_last_validated (dxc_w->last_validated)
#define dxc_blit_time (dxc_w->blit_time)
#define dxc_cpu_mode (dxc_w->cpu_mode)
#define dxc_dib_dc (dxc_w->dib_dc)
#define dxc_dib (dxc_w->dib)
#define dxc_dib_previous (dxc_w->dib_previous)
#define dxc_dib_bits (dxc_w->dib_bits)
#define dxc_dib_width (dxc_w->dib_width)
#define dxc_dib_height (dxc_w->dib_height)
#define dxc_raster_width (dxc_w->raster_width)
#define dxc_raster_height (dxc_w->raster_height)
#define dxc_gpu_return_posted (dxc_w->gpu_return_posted)
#define dxc_mode_switches (dxc_w->mode_switches)
#define dxc_predrawn (dxc_w->predrawn)
#define dxc_predrawing (dxc_w->predrawing)
#define dxc_surface_ready_width (dxc_w->surface_ready_width)
#define dxc_surface_ready_height (dxc_w->surface_ready_height)
#define dxc_dropped_paths (dxc_w->dropped_paths)
#define dxc_dropped_length (dxc_w->dropped_length)
#define dxc_drag_has_files (dxc_w->drag_has_files)
#define dxc_options (dxc_w->options)

// What a call that names no window runs for: the window the last select chose, or the one
// being made, or the one whose message is being handled. Before any window exists it is a
// record that says the window is gone, so a call made too early answers like one made too late.
static DxcWin dxc_no_window = {.window_gone = 1, .destroyed = 1};
static __declspec(thread) DxcWin *dxc_w = &dxc_no_window;

// Process-wide, shared by every window and initialised once, counted by the windows that are
// open: the window table (handle to state), the count that keeps the window class registered,
// the window being made (its first messages arrive before the handle is known), where the next
// window opens, whether a quit was asked for, and when the last pump ended.
static struct dxc_window_table dxc_table;
static struct dxc_shared dxc_shared_state;
static DxcWin *dxc_creating;
static struct {
    int32_t has;
    int32_t x;
    int32_t y;
} dxc_next_position;
static int dxc_quit_seen;
static int32_t dxc_quit_code;
static int64_t dxc_last_pump_end_micros;
static int dxc_class_registered;
/** A second pump this close behind the last is the same turn of the caller's loop. */
#define DXC_PUMP_QUIET_MICROS 2000
static void dxc_uncount_window(DxcWin *win);
static void dxc_follow_turn(void);
static int64_t dxc_micros_now(void);
static void dxc_select_handle(void *handle);
static void dxc_select_swapchain(void *swapchain);
static void dxc_select_queue(void *queue);
// The native view slots, at the end of this file.
static void dxc_nv_apply(DxcWin *win);
static void dxc_nv_exclude(DxcWin *win, HDC dc);
static void dxc_nv_turn(void);
static void dxc_nv_detach_all(DxcWin *win);
static void dxc_nv_activated(DxcWin *win, HWND window, int active);
static void dxc_nv_scene_pressed(DxcWin *win, HWND window);
static int dxc_nv_set_cursor(DxcWin *win, HWND window, HWND over);
static void dxc_nv_pointer_back(DxcWin *win, LPARAM where);
static void dxc_nv_mouse_left(DxcWin *win, HWND window);

// No lock. On macOS the events arrive on AppKit's thread and are read on the renderer's,
// so that queue is guarded; here the window belongs to the thread that draws and the
// message pump runs inside the read below, so one thread writes and the same one reads.
// A modal loop (the user dragging the window's edge) dispatches on that thread too.

// The Direct3D 11 device the swapchain belongs to, made on the renderer's Direct3D 12
// device and queue through D3D11On12, and the texture Skia draws into, wrapped for it.
// ID3D11On12Device, named here because not every SDK's dxguid.lib carries it.
static const IID dxc_iid_on12_device =
    {0x85611e73, 0x70a9, 0x490e, {0x96, 0x14, 0xa9, 0xe3, 0x02, 0x77, 0x79, 0x04}};
// What the swapchain was made with, and what every later refit has to say again: a refit
// that names other flags is refused.
// Signalled when the swapchain will take another frame without queueing it. NULL where
// the swapchain was made without the latency flag.
// A frame is being drawn on this call stack. A resize message that arrives while it is
// draws nothing: the frame in flight already reads the size that message wrote down.
// The fence value signalled after the barrier list last executed.
// The size the window has been given and the size it is drawn at, which are the same
// except while a resize is being taken. A swapchain cannot be refitted while the buffer
// being refitted is the one being drawn into, so the size is written down here and acted
// on where a frame begins.
// Set when the window has gone, so the frame loop stops rather than drawing into nothing.
// A resize is being drawn: WM_SIZE has recorded a size and is drawing it before it
// returns. The frame presents only if it was drawn at exactly that size, and only once.
// The size last presented, so one size is never presented twice by a resize.
// DXC_REPORT_LATENCY: each resize step prints how long the refit, the draw, the present
// and the DwmFlush took together.
static int dxc_report_latency;
// DXC_RESIZE_PREDRAW=0: a size given outside a drag is drawn in WM_SIZE, after the window
// has taken it, as before the frame was drawn ahead in WM_NCCALCSIZE. For comparing the two.
static int dxc_predraw_off;
// DXC_RESIZE_SWAPCHAIN=0: a frame drawn ahead of a size given outside a drag is copied into
// the window's GDI surface with the swapchain's child hidden, as before it was presented
// through the swapchain. For comparing the two.
static int dxc_raster_present_off;
// DXC_PREDRAW_FLUSH=1: the wait for one composition after the frame drawn ahead, also when it
// is presented through the swapchain. For comparing the two.
static int dxc_predraw_flush;
// DXC_RESIZE_HIDE_EARLY=0: the swapchain's child stays visible until WM_SIZE, as before it
// was hidden in WM_NCCALCSIZE, ahead of the size change. For comparing the two.
static int dxc_hide_early_off;
// DXC_DRAG_PREDRAW=0: a step of a drag is drawn in WM_SIZE, after the window has taken its
// size, as before it was drawn ahead in WM_NCCALCSIZE (issue 245). For comparing the two.
static int dxc_drag_predraw_off;
// DXC_DRAG_FLUSH: 0 never waits for a composition before a step of a drag takes its size;
// unset or anything else, every step waits, on every build (dxc_resize_drag_flush_wanted).
static int dxc_drag_flush_asked = -1;
static int dxc_drag_flush;
// Steps of the current drag drawn ahead, and how many of them waited for a composition.
static int dxc_drag_predrawn_steps;
static int dxc_drag_flushed_steps;
// When the current drag began (WM_ENTERSIZEMOVE), on the performance counter in ms.
static double dxc_drag_started_ms;
// DXC_SURFACE_RETURN: how the child hidden ahead of the size change comes back with the frame
// drawn ahead. staged (the default): WM_SIZE fits the child, shows it in place and presents;
// parked (opt-in until a run shows the present taken by frame statistics, compose-rust#926):
// the swapchain is refitted and the frame copied into its buffer in WM_NCCALCSIZE, and WM_SIZE
// shows the child outside the window's client area, presents, waits until DWM took the present
// and only then moves the child into place (dxc_show_parked_surface);
// wmsize: WM_SIZE fits it, presents the frame and shows it; nccalc: fitted and presented in
// WM_NCCALCSIZE, shown in WM_SIZE; showfirst: WM_SIZE fits and shows it, then refits and
// presents; posted: the GPU return after WM_SIZE draws a new frame first.
enum {
    DXC_SURFACE_RETURN_PARKED, DXC_SURFACE_RETURN_STAGED, DXC_SURFACE_RETURN_WM_SIZE, DXC_SURFACE_RETURN_NCCALC,
    DXC_SURFACE_RETURN_SHOW_FIRST, DXC_SURFACE_RETURN_POSTED, DXC_SURFACE_RETURN_COUNT
};
static const char *const dxc_surface_return_names[] = {"parked", "staged", "wmsize", "nccalc", "showfirst", "posted"};
static int dxc_surface_return = DXC_SURFACE_RETURN_STAGED;
// When each step of a size change outside a drag ran, on the performance counter in ms: the
// frame drawn ahead was ready (WM_NCCALCSIZE about to return), WM_WINDOWPOSCHANGED and WM_SIZE
// arrived, the frame was copied into the window, the child was fitted, and it was shown. A
// composed frame with the new size and the old picture falls between the window taking its
// rectangle and the present landing (about 1.4 ms, most of it before WM_WINDOWPOSCHANGED
// arrives), and the present then waits for that composition: the log line shows it.
enum {
    DXC_STATE_READY, DXC_STATE_CHANGED, DXC_STATE_SIZE, DXC_STATE_COPIED, DXC_STATE_FITTED, DXC_STATE_SHOWN,
    DXC_STATE_MARK_COUNT
};
static double dxc_state_marks[DXC_STATE_MARK_COUNT];
/**
 * One line on stderr for every change of a state that decides how resize frames are
 * produced. On by default: these happen a handful of times a session, and they are what
 * tells a capture that went wrong apart from one that did not.
 */
static void dxc_mode(const char *what, const char *from, const char *to, const char *reason) {
    fprintf(stderr, "compose-rust: resize-mode: %s %s -> %s because %s\n", what, from, to, reason);
}

// Load resilience, compared with DXC_RESIZE_PRIORITY: unset, the documented-safe ones are
// on (UI thread above normal during a drag, a high priority command queue); "0" turns
// everything off; "1" also raises the GPU thread priority of the Direct3D 11 device,
// which the docs warn can slow rendering if misused, so it is never on by default.
static int dxc_priority_level = -1;

static int dxc_priority(void) {
    if (dxc_priority_level < 0) {
        const char *asked = getenv("DXC_RESIZE_PRIORITY");
        dxc_priority_level = asked == NULL ? 1 : (asked[0] == '0' ? 0 : (asked[0] == '1' ? 2 : 1));
    }
    return dxc_priority_level;
}

// The UI thread's priority before a drag raised it.
static int dxc_thread_priority_before = THREAD_PRIORITY_ERROR_RETURN;

// The draw texture's size, defined with the texture further down.

// The adapter the device was made on, kept for asking it how much video memory is in use.

/** Megabytes of video memory this process uses in a segment group, or -1 where it cannot say. */
static double dxc_video_mb(DXGI_MEMORY_SEGMENT_GROUP group) {
    IDXGIAdapter3 *adapter3 = NULL;
    double answer = -1.0;
    if (dxc_adapter != NULL &&
        SUCCEEDED(IDXGIAdapter1_QueryInterface(dxc_adapter, &IID_IDXGIAdapter3, (void **)&adapter3))) {
        DXGI_QUERY_VIDEO_MEMORY_INFO info;
        if (SUCCEEDED(IDXGIAdapter3_QueryVideoMemoryInfo(adapter3, 0, group, &info))) {
            answer = (double)info.CurrentUsage / (1024.0 * 1024.0);
        }
        IDXGIAdapter3_Release(adapter3);
    }
    return answer;
}

/**
 * One line of what this process holds, for measuring: working set and private bytes, and
 * the video memory it uses on and off the adapter.
 */
static void dxc_memory_point(const char *where, IDXGIAdapter1 *adapter);

void dxc_native_report_metrics(const char *label) {
    PROCESS_MEMORY_COUNTERS_EX counters;
    memset(&counters, 0, sizeof counters);
    counters.cb = sizeof counters;
    K32GetProcessMemoryInfo(GetCurrentProcess(), (PROCESS_MEMORY_COUNTERS *)&counters, sizeof counters);
    fprintf(stderr,
            "compose-rust: metrics memory %s working_set_mb=%.1f private_mb=%.1f "
            "video_local_mb=%.1f video_nonlocal_mb=%.1f draw_texture=%dx%d route=%s\n",
            label == NULL ? "" : label,
            (double)counters.WorkingSetSize / (1024.0 * 1024.0),
            (double)counters.PrivateUsage / (1024.0 * 1024.0),
            dxc_video_mb(DXGI_MEMORY_SEGMENT_GROUP_LOCAL),
            dxc_video_mb(DXGI_MEMORY_SEGMENT_GROUP_NON_LOCAL),
            (int)dxc_texture_width, (int)dxc_texture_height, dxc_on_comp || dxc_comp != NULL ? "dcomp" : "gdi");
    dxc_memory_point(label == NULL ? "" : label, dxc_adapter);
}

// The adapter the Direct3D 12 device was made on, and the monitor the window was last on.

/**
 * Logs the window's monitor, its DPI, the adapter driving that monitor and the adapter
 * the device draws with. When the two adapters differ, every present crosses adapters
 * before DWM can compose it.
 */
static void dxc_log_monitor(HWND window, const char *reason) {
    HMONITOR monitor = MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST);
    dxc_last_monitor = monitor;
    LUID output_luid = {0, 0};
    int found = 0;
    IDXGIFactory1 *factory = NULL;
    if (SUCCEEDED(CreateDXGIFactory1(&IID_IDXGIFactory1, (void **)&factory))) {
        IDXGIAdapter1 *adapter = NULL;
        for (UINT a = 0; !found && IDXGIFactory1_EnumAdapters1(factory, a, &adapter) != DXGI_ERROR_NOT_FOUND; a++) {
            IDXGIOutput *output = NULL;
            for (UINT o = 0; !found && IDXGIAdapter1_EnumOutputs(adapter, o, &output) != DXGI_ERROR_NOT_FOUND; o++) {
                DXGI_OUTPUT_DESC described;
                if (SUCCEEDED(IDXGIOutput_GetDesc(output, &described)) && described.Monitor == monitor) {
                    DXGI_ADAPTER_DESC1 adapter_described;
                    if (SUCCEEDED(IDXGIAdapter1_GetDesc1(adapter, &adapter_described))) {
                        output_luid = adapter_described.AdapterLuid;
                        found = 1;
                    }
                }
                IDXGIOutput_Release(output);
            }
            IDXGIAdapter1_Release(adapter);
        }
        IDXGIFactory1_Release(factory);
    }
    int same = found && output_luid.LowPart == dxc_device_luid.LowPart &&
               output_luid.HighPart == dxc_device_luid.HighPart;
    fprintf(stderr,
            "compose-rust: resize-mode: monitor %p dpi %u, monitor adapter %08lx:%08lx, "
            "device adapter %08lx:%08lx (%s) because %s\n",
            (void *)monitor, (unsigned)GetDpiForWindow(window),
            (unsigned long)output_luid.HighPart, (unsigned long)output_luid.LowPart,
            (unsigned long)dxc_device_luid.HighPart, (unsigned long)dxc_device_luid.LowPart,
            !found ? "monitor adapter not found" : (same ? "same adapter" : "DIFFERENT adapter"),
            reason);
}

// The last WM_SIZE kind, for logging maximise, minimise and restore.

static const char *dxc_size_kind_name(WPARAM kind) {
    switch (kind) {
    case SIZE_MAXIMIZED: return "maximised";
    case SIZE_MINIMIZED: return "minimised";
    case SIZE_RESTORED: return "restored";
    default: return "other";
    }
}

/** Logs a failed DXGI call, with the device removed reason when that is what it was. */
static void dxc_log_dxgi_failure(const char *call, HRESULT result) {
    fprintf(stderr, "compose-rust: resize-mode: %s failed with 0x%08lx\n", call, (unsigned long)result);
    if ((result == DXGI_ERROR_DEVICE_REMOVED || result == DXGI_ERROR_DEVICE_RESET) &&
        dxc_device != NULL) {
        fprintf(stderr, "compose-rust: resize-mode: device ok -> removed because 0x%08lx\n",
                (unsigned long)ID3D12Device_GetDeviceRemovedReason(dxc_device));
    }
}

// A resize step that takes longer than this is logged. It is not cut short: returning
// before the frame is presented and flushed lets DWM show the resized redirection surface,
// which is black, so a slow step is slow rather than wrong.
#define DXC_RESIZE_STEP_CAP_MS 100.0
// Where the time of the step in flight went, in ms, for DXC_REPORT_LATENCY.
static double dxc_step_gpu_idle_ms;
static double dxc_step_refit_ms;
static double dxc_step_draw_ms;
static double dxc_step_present_ms;
static double dxc_step_copy_ms;
static LARGE_INTEGER dxc_step_mark;

// A frame, asked for by the window rather than by the loop that usually draws them.
//
// Registered by the renderer, which is the only thing that can draw: the pixels are
// Skia's and this file has never had any. It is needed because Windows runs a loop of its
// own inside `DefWindowProc` while the reader drags the window's edge, and for the whole
// of that drag the renderer's frame loop is stopped inside the message that began it. A
// size written down there is a size nothing draws until the drag ends.
//
// This is the renderer's platform code calling the renderer, on the one thread both live
// on. Nothing of the Host's crosses here and no new boundary entry point is involved: the
// window asks its own renderer to draw, which is what the frame loop would have done had
// it been given a turn.
typedef void (*dxc_draw_frame_fn)(void *isolate_thread);
static dxc_draw_frame_fn dxc_draw_frame;
// The renderer's thread, as the renderer named it when it registered. Handed back with
// every call because the other side is a Java runtime and cannot be entered without it.
static void *dxc_draw_thread;

/**
 * Lets the renderer be asked for a frame from inside a message.
 *
 * A null callback is how it is taken away again, which the renderer does before it closes
 * the scene: a message arriving after that would be a frame drawn into a scene that has
 * gone.
 */
void dxc_native_set_draw_callback(dxc_draw_frame_fn callback, void *isolate_thread) {
    dxc_draw_frame = callback;
    dxc_draw_thread = isolate_thread;
}

/**
 * Asks for a frame now, where a frame can be drawn at all.
 *
 * Nothing happens before the renderer has registered, which covers the first few messages
 * a window receives while it is still being built.
 */
static void dxc_draw_one_frame(void) {
    if (dxc_draw_frame != NULL && !dxc_drawing) {
        dxc_drawing = 1;
        dxc_draw_frame(dxc_draw_thread);
        dxc_drawing = 0;
    }
}


void dxc_native_set_cursor(int32_t shape) {
    switch (shape) {
    case 1: dxc_cursor = IDC_HAND; break;
    case 2: dxc_cursor = IDC_IBEAM; break;
    case 3: dxc_cursor = IDC_CROSS; break;
    case 4: dxc_cursor = IDC_SIZEWE; break;
    case 5: dxc_cursor = IDC_SIZENS; break;
    default: dxc_cursor = IDC_ARROW; break;
    }
    SetCursor(LoadCursorW(NULL, dxc_cursor));
}

/**
 * Sets the pointer's shape over the window to the system cursor [idc] (an `IDC_*` number, the
 * table in common's CursorShape: FR-47.1's 36 keywords), or hides it for 0. Kept until the next
 * call; WM_SETCURSOR puts it back whenever the pointer is over the client area.
 */
void dxc_native_set_cursor_id(int32_t idc) {
    dxc_cursor = idc > 0 ? MAKEINTRESOURCEW(idc) : NULL;
    SetCursor(dxc_cursor != NULL ? LoadCursorW(NULL, dxc_cursor) : NULL);
}

static void dxc_push_event(struct dxc_event event) {
    if (dxc_event_count < DXC_EVENT_CAPACITY) {
        int slot = (dxc_event_head + dxc_event_count) % DXC_EVENT_CAPACITY;
        dxc_events[slot] = event;
        dxc_event_count++;
    } else {
        // Full: the oldest goes. A dropped move from a while ago is a position that has
        // already been overtaken, and dropping the newest would leave the pointer
        // somewhere it no longer is.
        dxc_events[dxc_event_head] = event;
        dxc_event_head = (dxc_event_head + 1) % DXC_EVENT_CAPACITY;
    }
}

// ---------------------------------------------------------------------------
// Accessibility: UI Automation
// ---------------------------------------------------------------------------
//
// What the window tells a reader who cannot see it.
//
// A snapshot rather than a question. UI Automation asks for elements on the
// thread the window was made on, at moments nobody chose, and the tree it
// describes lives where the scene does. The scene pushes what it has whenever
// it changes, and the window procedure answers from that.

// The roles a scene can describe, as numbers, because a name would be a string
// crossing for every element on every push. Which UIA control type each one is
// is decided here, in one place.
enum {
    DXC_ROLE_GROUP = 0,
    DXC_ROLE_BUTTON = 1,
    DXC_ROLE_TEXT = 2,
    DXC_ROLE_FIELD = 3,
    DXC_ROLE_CHECKBOX = 4,
    DXC_ROLE_IMAGE = 5,
    DXC_ROLE_SWITCH = 6,
    DXC_ROLE_RADIO_BUTTON = 7,
    DXC_ROLE_TAB = 8,
    DXC_ROLE_SLIDER = 9,
    DXC_ROLE_PROGRESS = 10,
    DXC_ROLE_HEADING = 11,
    DXC_ROLE_COMBO_BOX = 12,
    DXC_ROLE_LINK = 13,
    DXC_ROLE_TAB_GROUP = 14,
    DXC_ROLE_OUTLINE = 15,
    DXC_ROLE_OUTLINE_ROW = 16,
    DXC_ROLE_MENU = 17,
    DXC_ROLE_MENU_ITEM = 18,
    DXC_ROLE_LIST = 19,
    DXC_ROLE_LIST_ITEM = 20,
    DXC_ROLE_NAVIGATION = 21,
};

#include "win32_uia.h"

static int dxc_uia_control_type(int32_t role) {
    switch (role) {
        case DXC_ROLE_BUTTON:   return UIA_ButtonControlTypeId;
        case DXC_ROLE_TEXT:     return UIA_TextControlTypeId;
        case DXC_ROLE_FIELD:    return UIA_EditControlTypeId;
        case DXC_ROLE_CHECKBOX: return UIA_CheckBoxControlTypeId;
        case DXC_ROLE_IMAGE:    return UIA_ImageControlTypeId;
        // A switch is a button that toggles, which is what Windows' own toggle switch reports.
        case DXC_ROLE_SWITCH:       return UIA_ButtonControlTypeId;
        case DXC_ROLE_RADIO_BUTTON: return UIA_RadioButtonControlTypeId;
        case DXC_ROLE_TAB:          return UIA_TabItemControlTypeId;
        case DXC_ROLE_SLIDER:       return UIA_SliderControlTypeId;
        case DXC_ROLE_PROGRESS:     return UIA_ProgressBarControlTypeId;
        // A heading is text; the heading level property says it is a heading.
        case DXC_ROLE_HEADING:      return UIA_TextControlTypeId;
        case DXC_ROLE_COMBO_BOX:    return UIA_ComboBoxControlTypeId;
        case DXC_ROLE_LINK:         return UIA_HyperlinkControlTypeId;
        case DXC_ROLE_TAB_GROUP:   return UIA_TabControlTypeId;
        case DXC_ROLE_OUTLINE:     return UIA_TreeControlTypeId;
        case DXC_ROLE_OUTLINE_ROW: return UIA_TreeItemControlTypeId;
        case DXC_ROLE_MENU:        return UIA_MenuControlTypeId;
        case DXC_ROLE_MENU_ITEM:   return UIA_MenuItemControlTypeId;
        case DXC_ROLE_LIST:        return UIA_ListControlTypeId;
        case DXC_ROLE_LIST_ITEM:   return UIA_ListItemControlTypeId;
        // A navigation landmark is a group, with the landmark type property saying which.
        default:                return UIA_GroupControlTypeId;
    }
}

// How many things a screen may say it has. Enough for a screen and not for a
// document, because a list of ten thousand rows is windowed before it reaches
// the scene.

// The current accessibility snapshot. Written by the push and read by UIA
// callbacks. Both happen on the same thread (the one that owns the window and
// pumps its messages), so no lock is needed.
#define DXC_WM_ACCESSIBILITY_UPDATE (WM_APP + 1)

// ---------------------------------------------------------------------------
// COM plumbing
// ---------------------------------------------------------------------------
//
// UI Automation talks to this window through COM interfaces. Each element the
// scene describes becomes an object that answers for itself, and the window
// itself is the root of the tree. COM in C means vtables built by hand: a
// struct of function pointers, pointed to by the object, with `this` as the
// first argument to every one of them.
//
// The root implements:
//   IRawElementProviderSimple       -- identity and properties
//   IRawElementProviderFragment     -- navigation (children, bounds)
//   IRawElementProviderFragmentRoot -- finding elements at a point or by focus
// Each element implements the first two, and the control patterns that fit it
// (win32_uia.h decides which):
//   IInvokeProvider, IToggleProvider, IValueProvider, IRangeValueProvider,
//   ISelectionItemProvider, IExpandCollapseProvider
//
// A reader's request (invoke, toggle, set a value, take focus) is not done
// here. It is written to the window's event queue as DXC_EVENT_ACCESSIBILITY_ACTION
// and done by the scene when the renderer next drains the queue, on the same
// thread, the way a click is.

typedef struct DxcProvider DxcProvider;

static float dxc_scale_of(HWND window);

// ---------------------------------------------------------------------------
// DxcProvider: one element
// ---------------------------------------------------------------------------

struct DxcProvider {
    IRawElementProviderSimpleVtbl *simple_vtbl;
    IRawElementProviderFragmentVtbl *fragment_vtbl;
    IInvokeProviderVtbl *invoke_vtbl;
    IToggleProviderVtbl *toggle_vtbl;
    IValueProviderVtbl *value_vtbl;
    IRangeValueProviderVtbl *range_vtbl;
    ISelectionItemProviderVtbl *selection_vtbl;
    IExpandCollapseProviderVtbl *expand_vtbl;
    LONG ref_count;
    // Its position in the root's children. An element the scene keeps (the same id) keeps
    // its object across pushes and is given its new position and description.
    int32_t index;
    // Cleared once a push leaves it out. A reader may still hold it; it then answers that
    // it is gone.
    int alive;
    struct dxc_element snapshot;
    DxcRootProvider *root;
};

// ---------------------------------------------------------------------------
// DxcRootProvider: the window itself as the root of the UIA tree
// ---------------------------------------------------------------------------

struct DxcRootProvider {
    IRawElementProviderSimpleVtbl *simple_vtbl;
    IRawElementProviderFragmentVtbl *fragment_vtbl;
    IRawElementProviderFragmentRootVtbl *fragment_root_vtbl;
    LONG ref_count;
    HWND window;
    // The elements, in the scene's order, rebuilt every time the scene pushes, and where
    // each is in the tree.
    DxcProvider **children;
    struct dxc_uia_links *links;
    int32_t child_count;
    // The scene id of the element that had focus at the last push, or -1.
    int32_t focused_id;
};

// Forward declarations of vtable functions.
static HRESULT STDMETHODCALLTYPE dxc_provider_qi(IRawElementProviderSimple *self, REFIID riid, void **out);
static ULONG STDMETHODCALLTYPE dxc_provider_addref(IRawElementProviderSimple *self);
static ULONG STDMETHODCALLTYPE dxc_provider_release(IRawElementProviderSimple *self);
static HRESULT STDMETHODCALLTYPE dxc_provider_get_provider_options(IRawElementProviderSimple *self, enum ProviderOptions *out);
static HRESULT STDMETHODCALLTYPE dxc_provider_get_pattern_provider(IRawElementProviderSimple *self, PATTERNID id, IUnknown **out);
static HRESULT STDMETHODCALLTYPE dxc_provider_get_property_value(IRawElementProviderSimple *self, PROPERTYID id, VARIANT *out);
static HRESULT STDMETHODCALLTYPE dxc_provider_get_host_raw_element_provider(IRawElementProviderSimple *self, IRawElementProviderSimple **out);

static HRESULT STDMETHODCALLTYPE dxc_frag_qi(IRawElementProviderFragment *self, REFIID riid, void **out);
static ULONG STDMETHODCALLTYPE dxc_frag_addref(IRawElementProviderFragment *self);
static ULONG STDMETHODCALLTYPE dxc_frag_release(IRawElementProviderFragment *self);
static HRESULT STDMETHODCALLTYPE dxc_frag_navigate(IRawElementProviderFragment *self, enum NavigateDirection dir, IRawElementProviderFragment **out);
static HRESULT STDMETHODCALLTYPE dxc_frag_get_runtime_id(IRawElementProviderFragment *self, SAFEARRAY **out);
static HRESULT STDMETHODCALLTYPE dxc_frag_get_bounding_rect(IRawElementProviderFragment *self, struct UiaRect *out);
static HRESULT STDMETHODCALLTYPE dxc_frag_get_embedded_fragment_roots(IRawElementProviderFragment *self, SAFEARRAY **out);
static HRESULT STDMETHODCALLTYPE dxc_frag_set_focus(IRawElementProviderFragment *self);
static HRESULT STDMETHODCALLTYPE dxc_frag_get_fragment_root(IRawElementProviderFragment *self, IRawElementProviderFragmentRoot **out);

static HRESULT STDMETHODCALLTYPE dxc_root_qi(IRawElementProviderSimple *self, REFIID riid, void **out);
static ULONG STDMETHODCALLTYPE dxc_root_addref(IRawElementProviderSimple *self);
static ULONG STDMETHODCALLTYPE dxc_root_release(IRawElementProviderSimple *self);
static HRESULT STDMETHODCALLTYPE dxc_root_get_provider_options(IRawElementProviderSimple *self, enum ProviderOptions *out);
static HRESULT STDMETHODCALLTYPE dxc_root_get_pattern_provider(IRawElementProviderSimple *self, PATTERNID id, IUnknown **out);
static HRESULT STDMETHODCALLTYPE dxc_root_get_property_value(IRawElementProviderSimple *self, PROPERTYID id, VARIANT *out);
static HRESULT STDMETHODCALLTYPE dxc_root_get_host_raw_element_provider(IRawElementProviderSimple *self, IRawElementProviderSimple **out);

static HRESULT STDMETHODCALLTYPE dxc_root_frag_qi(IRawElementProviderFragment *self, REFIID riid, void **out);
static ULONG STDMETHODCALLTYPE dxc_root_frag_addref(IRawElementProviderFragment *self);
static ULONG STDMETHODCALLTYPE dxc_root_frag_release(IRawElementProviderFragment *self);
static HRESULT STDMETHODCALLTYPE dxc_root_frag_navigate(IRawElementProviderFragment *self, enum NavigateDirection dir, IRawElementProviderFragment **out);
static HRESULT STDMETHODCALLTYPE dxc_root_frag_get_runtime_id(IRawElementProviderFragment *self, SAFEARRAY **out);
static HRESULT STDMETHODCALLTYPE dxc_root_frag_get_bounding_rect(IRawElementProviderFragment *self, struct UiaRect *out);
static HRESULT STDMETHODCALLTYPE dxc_root_frag_get_embedded_fragment_roots(IRawElementProviderFragment *self, SAFEARRAY **out);
static HRESULT STDMETHODCALLTYPE dxc_root_frag_set_focus(IRawElementProviderFragment *self);
static HRESULT STDMETHODCALLTYPE dxc_root_frag_get_fragment_root(IRawElementProviderFragment *self, IRawElementProviderFragmentRoot **out);

static HRESULT STDMETHODCALLTYPE dxc_root_fr_qi(IRawElementProviderFragmentRoot *self, REFIID riid, void **out);
static ULONG STDMETHODCALLTYPE dxc_root_fr_addref(IRawElementProviderFragmentRoot *self);
static ULONG STDMETHODCALLTYPE dxc_root_fr_release(IRawElementProviderFragmentRoot *self);
static HRESULT STDMETHODCALLTYPE dxc_root_fr_element_from_point(IRawElementProviderFragmentRoot *self, double x, double y, IRawElementProviderFragment **out);
static HRESULT STDMETHODCALLTYPE dxc_root_fr_get_focus(IRawElementProviderFragmentRoot *self, IRawElementProviderFragment **out);

static HRESULT STDMETHODCALLTYPE dxc_invoke_qi(IInvokeProvider *self, REFIID riid, void **out);
static ULONG STDMETHODCALLTYPE dxc_invoke_addref(IInvokeProvider *self);
static ULONG STDMETHODCALLTYPE dxc_invoke_release(IInvokeProvider *self);
static HRESULT STDMETHODCALLTYPE dxc_invoke_invoke(IInvokeProvider *self);

static HRESULT STDMETHODCALLTYPE dxc_toggle_qi(IToggleProvider *self, REFIID riid, void **out);
static ULONG STDMETHODCALLTYPE dxc_toggle_addref(IToggleProvider *self);
static ULONG STDMETHODCALLTYPE dxc_toggle_release(IToggleProvider *self);
static HRESULT STDMETHODCALLTYPE dxc_toggle_toggle(IToggleProvider *self);
static HRESULT STDMETHODCALLTYPE dxc_toggle_get_state(IToggleProvider *self, enum ToggleState *out);

static HRESULT STDMETHODCALLTYPE dxc_value_qi(IValueProvider *self, REFIID riid, void **out);
static ULONG STDMETHODCALLTYPE dxc_value_addref(IValueProvider *self);
static ULONG STDMETHODCALLTYPE dxc_value_release(IValueProvider *self);
static HRESULT STDMETHODCALLTYPE dxc_value_set(IValueProvider *self, LPCWSTR value);
static HRESULT STDMETHODCALLTYPE dxc_value_get(IValueProvider *self, BSTR *out);
static HRESULT STDMETHODCALLTYPE dxc_value_get_read_only(IValueProvider *self, BOOL *out);

static HRESULT STDMETHODCALLTYPE dxc_range_qi(IRangeValueProvider *self, REFIID riid, void **out);
static ULONG STDMETHODCALLTYPE dxc_range_addref(IRangeValueProvider *self);
static ULONG STDMETHODCALLTYPE dxc_range_release(IRangeValueProvider *self);
static HRESULT STDMETHODCALLTYPE dxc_range_set(IRangeValueProvider *self, double value);
static HRESULT STDMETHODCALLTYPE dxc_range_get(IRangeValueProvider *self, double *out);
static HRESULT STDMETHODCALLTYPE dxc_range_get_read_only(IRangeValueProvider *self, BOOL *out);
static HRESULT STDMETHODCALLTYPE dxc_range_get_maximum(IRangeValueProvider *self, double *out);
static HRESULT STDMETHODCALLTYPE dxc_range_get_minimum(IRangeValueProvider *self, double *out);
static HRESULT STDMETHODCALLTYPE dxc_range_get_large_change(IRangeValueProvider *self, double *out);
static HRESULT STDMETHODCALLTYPE dxc_range_get_small_change(IRangeValueProvider *self, double *out);

static HRESULT STDMETHODCALLTYPE dxc_selection_qi(ISelectionItemProvider *self, REFIID riid, void **out);
static ULONG STDMETHODCALLTYPE dxc_selection_addref(ISelectionItemProvider *self);
static ULONG STDMETHODCALLTYPE dxc_selection_release(ISelectionItemProvider *self);
static HRESULT STDMETHODCALLTYPE dxc_selection_select(ISelectionItemProvider *self);
static HRESULT STDMETHODCALLTYPE dxc_selection_add(ISelectionItemProvider *self);
static HRESULT STDMETHODCALLTYPE dxc_selection_remove(ISelectionItemProvider *self);
static HRESULT STDMETHODCALLTYPE dxc_selection_get_selected(ISelectionItemProvider *self, BOOL *out);
static HRESULT STDMETHODCALLTYPE dxc_selection_get_container(ISelectionItemProvider *self, IRawElementProviderSimple **out);

static HRESULT STDMETHODCALLTYPE dxc_expand_qi(IExpandCollapseProvider *self, REFIID riid, void **out);
static ULONG STDMETHODCALLTYPE dxc_expand_addref(IExpandCollapseProvider *self);
static ULONG STDMETHODCALLTYPE dxc_expand_release(IExpandCollapseProvider *self);
static HRESULT STDMETHODCALLTYPE dxc_expand_expand(IExpandCollapseProvider *self);
static HRESULT STDMETHODCALLTYPE dxc_expand_collapse(IExpandCollapseProvider *self);
static HRESULT STDMETHODCALLTYPE dxc_expand_get_state(IExpandCollapseProvider *self, enum ExpandCollapseState *out);

// ---------------------------------------------------------------------------
// Vtables
// ---------------------------------------------------------------------------

static IRawElementProviderSimpleVtbl dxc_provider_simple_vtbl = {
    dxc_provider_qi,
    dxc_provider_addref,
    dxc_provider_release,
    dxc_provider_get_provider_options,
    dxc_provider_get_pattern_provider,
    dxc_provider_get_property_value,
    dxc_provider_get_host_raw_element_provider,
};

static IRawElementProviderFragmentVtbl dxc_provider_fragment_vtbl = {
    dxc_frag_qi,
    dxc_frag_addref,
    dxc_frag_release,
    dxc_frag_navigate,
    dxc_frag_get_runtime_id,
    dxc_frag_get_bounding_rect,
    dxc_frag_get_embedded_fragment_roots,
    dxc_frag_set_focus,
    dxc_frag_get_fragment_root,
};

static IRawElementProviderSimpleVtbl dxc_root_simple_vtbl = {
    dxc_root_qi,
    dxc_root_addref,
    dxc_root_release,
    dxc_root_get_provider_options,
    dxc_root_get_pattern_provider,
    dxc_root_get_property_value,
    dxc_root_get_host_raw_element_provider,
};

static IRawElementProviderFragmentVtbl dxc_root_fragment_vtbl = {
    dxc_root_frag_qi,
    dxc_root_frag_addref,
    dxc_root_frag_release,
    dxc_root_frag_navigate,
    dxc_root_frag_get_runtime_id,
    dxc_root_frag_get_bounding_rect,
    dxc_root_frag_get_embedded_fragment_roots,
    dxc_root_frag_set_focus,
    dxc_root_frag_get_fragment_root,
};

static IRawElementProviderFragmentRootVtbl dxc_root_fragment_root_vtbl = {
    dxc_root_fr_qi,
    dxc_root_fr_addref,
    dxc_root_fr_release,
    dxc_root_fr_element_from_point,
    dxc_root_fr_get_focus,
};

static IInvokeProviderVtbl dxc_invoke_vtbl = {
    dxc_invoke_qi,
    dxc_invoke_addref,
    dxc_invoke_release,
    dxc_invoke_invoke,
};

static IToggleProviderVtbl dxc_toggle_vtbl = {
    dxc_toggle_qi,
    dxc_toggle_addref,
    dxc_toggle_release,
    dxc_toggle_toggle,
    dxc_toggle_get_state,
};

static IValueProviderVtbl dxc_value_vtbl = {
    dxc_value_qi,
    dxc_value_addref,
    dxc_value_release,
    dxc_value_set,
    dxc_value_get,
    dxc_value_get_read_only,
};

static IRangeValueProviderVtbl dxc_range_vtbl = {
    dxc_range_qi,
    dxc_range_addref,
    dxc_range_release,
    dxc_range_set,
    dxc_range_get,
    dxc_range_get_read_only,
    dxc_range_get_maximum,
    dxc_range_get_minimum,
    dxc_range_get_large_change,
    dxc_range_get_small_change,
};

static ISelectionItemProviderVtbl dxc_selection_vtbl = {
    dxc_selection_qi,
    dxc_selection_addref,
    dxc_selection_release,
    dxc_selection_select,
    dxc_selection_add,
    dxc_selection_remove,
    dxc_selection_get_selected,
    dxc_selection_get_container,
};

static IExpandCollapseProviderVtbl dxc_expand_vtbl = {
    dxc_expand_qi,
    dxc_expand_addref,
    dxc_expand_release,
    dxc_expand_expand,
    dxc_expand_collapse,
    dxc_expand_get_state,
};

// ---------------------------------------------------------------------------
// From an interface pointer back to its object
// ---------------------------------------------------------------------------

#define DXC_PROVIDER_FROM(iface, field) \
    ((DxcProvider *)((char *)(iface) - offsetof(DxcProvider, field)))

static DxcProvider *dxc_provider_from_simple(IRawElementProviderSimple *iface) {
    return (DxcProvider *)iface;
}

static DxcProvider *dxc_provider_from_fragment(IRawElementProviderFragment *iface) {
    return DXC_PROVIDER_FROM(iface, fragment_vtbl);
}

static DxcRootProvider *dxc_root_from_simple(IRawElementProviderSimple *iface) {
    return (DxcRootProvider *)iface;
}

static DxcRootProvider *dxc_root_from_fragment(IRawElementProviderFragment *iface) {
    return (DxcRootProvider *)((char *)iface - offsetof(DxcRootProvider, fragment_vtbl));
}

static DxcRootProvider *dxc_root_from_fragment_root(IRawElementProviderFragmentRoot *iface) {
    return (DxcRootProvider *)((char *)iface - offsetof(DxcRootProvider, fragment_root_vtbl));
}

// Converts UTF-8 to a BSTR for UIA property answers. The caller owns the result and must
// free it with SysFreeString.
static BSTR dxc_bstr_from_utf8(const char *utf8) {
    if (utf8 == NULL || utf8[0] == '\0') {
        return SysAllocString(L"");
    }
    int wide_len = MultiByteToWideChar(CP_UTF8, 0, utf8, -1, NULL, 0);
    if (wide_len <= 0) {
        return SysAllocString(L"");
    }
    BSTR result = SysAllocStringLen(NULL, (UINT)(wide_len - 1));
    if (result != NULL) {
        MultiByteToWideChar(CP_UTF8, 0, utf8, -1, result, wide_len);
    }
    return result;
}

// An element's rectangle on the screen. The scene describes it in window points from the
// top left of the client area, and UIA wants screen pixels.
static void dxc_element_rect_to_screen(HWND window, const struct dxc_element *el,
                                       struct UiaRect *out) {
    float scale = dxc_scale_of(window);
    POINT top_left;
    top_left.x = (LONG)(el->x * scale + 0.5f);
    top_left.y = (LONG)(el->y * scale + 0.5f);
    ClientToScreen(window, &top_left);
    out->left = (double)top_left.x;
    out->top = (double)top_left.y;
    out->width = (double)(el->width * scale);
    out->height = (double)(el->height * scale);
}

/** True while [provider] is part of the tree its root shows. */
static int dxc_provider_live(const DxcProvider *provider) {
    const DxcRootProvider *root = provider->root;
    return provider->alive && root != NULL && root->window != NULL && provider->index >= 0 &&
           provider->index < root->child_count && root->children[provider->index] == provider;
}

/** The element at [index] as the fragment interface UIA navigates by, with a reference for the caller. */
static IRawElementProviderFragment *dxc_child_fragment(DxcRootProvider *root, int32_t index) {
    if (index < 0 || index >= root->child_count) return NULL;
    DxcProvider *child = root->children[index];
    InterlockedIncrement(&child->ref_count);
    return (IRawElementProviderFragment *)&child->fragment_vtbl;
}

/**
 * Writes a reader's request down for the scene: DXC_EVENT_ACCESSIBILITY_ACTION on the queue of
 * the window the element is in, and for SET_VALUE the new value beside it. Answers what UIA
 * expects of a pattern method: the element gone, disabled, or nameless (an element the scene
 * gave no id cannot be asked to do anything), or done.
 */
static HRESULT dxc_provider_request(DxcProvider *provider, int32_t action, const char *argument) {
    if (!dxc_provider_live(provider)) return UIA_E_ELEMENTNOTAVAILABLE;
    if (provider->snapshot.id < 0) return UIA_E_INVALIDOPERATION;
    if (!(provider->snapshot.states & DXC_STATE_ENABLED)) return UIA_E_ELEMENTNOTENABLED;
    DxcWin *win = (DxcWin *)GetWindowLongPtrW(provider->root->window, GWLP_USERDATA);
    if (win == NULL) return UIA_E_ELEMENTNOTAVAILABLE;
    if (action == DXC_ACTION_SET_VALUE) {
        if (win->a11y_argument_count >= DXC_A11Y_ARGUMENTS) return E_FAIL;
        char *copy = _strdup(argument != NULL ? argument : "");
        if (copy == NULL) return E_OUTOFMEMORY;
        int32_t slot = (win->a11y_argument_head + win->a11y_argument_count) % DXC_A11Y_ARGUMENTS;
        win->a11y_arguments[slot] = copy;
        win->a11y_argument_count++;
    }
    struct dxc_event record;
    memset(&record, 0, sizeof record);
    record.kind = DXC_EVENT_ACCESSIBILITY_ACTION;
    record.key_code = provider->snapshot.id;
    record.code_point = action;
    DxcWin *outer = dxc_w;
    dxc_w = win;
    dxc_push_event(record);
    dxc_w = outer;
    return S_OK;
}

/** Drops every new value still waiting for the renderer, for a window that is going away. */
static void dxc_drop_accessibility_arguments(DxcWin *win) {
    while (win->a11y_argument_count > 0) {
        free(win->a11y_arguments[win->a11y_argument_head]);
        win->a11y_arguments[win->a11y_argument_head] = NULL;
        win->a11y_argument_head = (win->a11y_argument_head + 1) % DXC_A11Y_ARGUMENTS;
        win->a11y_argument_count--;
    }
    win->a11y_argument_head = 0;
}

// ---------------------------------------------------------------------------
// DxcProvider: IRawElementProviderSimple
// ---------------------------------------------------------------------------

static HRESULT STDMETHODCALLTYPE dxc_provider_qi(
    IRawElementProviderSimple *self, REFIID riid, void **out
) {
    DxcProvider *provider = dxc_provider_from_simple(self);
    int32_t patterns = dxc_uia_patterns(&provider->snapshot);
    void *found = NULL;
    if (IsEqualIID(riid, &IID_IUnknown) || IsEqualIID(riid, &IID_IRawElementProviderSimple)) {
        found = &provider->simple_vtbl;
    } else if (IsEqualIID(riid, &IID_IRawElementProviderFragment)) {
        found = &provider->fragment_vtbl;
    } else if (IsEqualIID(riid, &IID_IInvokeProvider) && (patterns & DXC_UIA_INVOKE)) {
        found = &provider->invoke_vtbl;
    } else if (IsEqualIID(riid, &IID_IToggleProvider) && (patterns & DXC_UIA_TOGGLE)) {
        found = &provider->toggle_vtbl;
    } else if (IsEqualIID(riid, &IID_IValueProvider) && (patterns & DXC_UIA_VALUE)) {
        found = &provider->value_vtbl;
    } else if (IsEqualIID(riid, &IID_IRangeValueProvider) && (patterns & DXC_UIA_RANGE_VALUE)) {
        found = &provider->range_vtbl;
    } else if (IsEqualIID(riid, &IID_ISelectionItemProvider) && (patterns & DXC_UIA_SELECTION_ITEM)) {
        found = &provider->selection_vtbl;
    } else if (IsEqualIID(riid, &IID_IExpandCollapseProvider) && (patterns & DXC_UIA_EXPAND_COLLAPSE)) {
        found = &provider->expand_vtbl;
    }
    *out = found;
    if (found == NULL) return E_NOINTERFACE;
    InterlockedIncrement(&provider->ref_count);
    return S_OK;
}

static ULONG STDMETHODCALLTYPE dxc_provider_addref(IRawElementProviderSimple *self) {
    DxcProvider *provider = dxc_provider_from_simple(self);
    return (ULONG)InterlockedIncrement(&provider->ref_count);
}

static ULONG STDMETHODCALLTYPE dxc_provider_release(IRawElementProviderSimple *self) {
    DxcProvider *provider = dxc_provider_from_simple(self);
    LONG count = InterlockedDecrement(&provider->ref_count);
    if (count <= 0) {
        free(provider);
    }
    return (ULONG)count;
}

static HRESULT STDMETHODCALLTYPE dxc_provider_get_provider_options(
    IRawElementProviderSimple *self, enum ProviderOptions *out
) {
    (void)self;
    *out = ProviderOptions_ServerSideProvider;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_provider_get_pattern_provider(
    IRawElementProviderSimple *self, PATTERNID id, IUnknown **out
) {
    DxcProvider *provider = dxc_provider_from_simple(self);
    int32_t patterns = dxc_uia_patterns(&provider->snapshot);
    void *found = NULL;
    // An if chain rather than a switch: the SDK declares the ids as const variables in C,
    // which a case label cannot name.
    if (id == UIA_InvokePatternId && (patterns & DXC_UIA_INVOKE)) {
        found = &provider->invoke_vtbl;
    } else if (id == UIA_TogglePatternId && (patterns & DXC_UIA_TOGGLE)) {
        found = &provider->toggle_vtbl;
    } else if (id == UIA_ValuePatternId && (patterns & DXC_UIA_VALUE)) {
        found = &provider->value_vtbl;
    } else if (id == UIA_RangeValuePatternId && (patterns & DXC_UIA_RANGE_VALUE)) {
        found = &provider->range_vtbl;
    } else if (id == UIA_SelectionItemPatternId && (patterns & DXC_UIA_SELECTION_ITEM)) {
        found = &provider->selection_vtbl;
    } else if (id == UIA_ExpandCollapsePatternId && (patterns & DXC_UIA_EXPAND_COLLAPSE)) {
        found = &provider->expand_vtbl;
    }
    if (found != NULL) InterlockedIncrement(&provider->ref_count);
    *out = (IUnknown *)found;
    return S_OK;
}

static void dxc_variant_bool(VARIANT *out, int value) {
    out->vt = VT_BOOL;
    out->boolVal = value ? VARIANT_TRUE : VARIANT_FALSE;
}

static HRESULT STDMETHODCALLTYPE dxc_provider_get_property_value(
    IRawElementProviderSimple *self, PROPERTYID id, VARIANT *out
) {
    DxcProvider *provider = dxc_provider_from_simple(self);
    const struct dxc_element *el = &provider->snapshot;
    VariantInit(out);
    if (id == UIA_ControlTypePropertyId) {
        out->vt = VT_I4;
        out->lVal = dxc_uia_control_type(el->role);
    } else if (id == UIA_NamePropertyId) {
        out->vt = VT_BSTR;
        out->bstrVal = dxc_bstr_from_utf8(el->label);
    } else if (id == UIA_AutomationIdPropertyId) {
        // The scene's id, so a test or a tool can find the same control again.
        if (el->id >= 0) {
            wchar_t text[16];
            swprintf(text, sizeof text / sizeof *text, L"%d", el->id);
            out->vt = VT_BSTR;
            out->bstrVal = SysAllocString(text);
        }
    } else if (id == UIA_IsControlElementPropertyId || id == UIA_IsContentElementPropertyId) {
        dxc_variant_bool(out, 1);
    } else if (id == UIA_IsEnabledPropertyId) {
        dxc_variant_bool(out, (el->states & DXC_STATE_ENABLED) != 0);
    } else if (id == UIA_IsKeyboardFocusablePropertyId) {
        dxc_variant_bool(out, (el->states & DXC_STATE_FOCUSABLE) != 0);
    } else if (id == UIA_HasKeyboardFocusPropertyId) {
        dxc_variant_bool(out, (el->states & DXC_STATE_FOCUSED) != 0);
    } else if (id == UIA_IsPasswordPropertyId) {
        dxc_variant_bool(out, (el->states & DXC_STATE_PASSWORD) != 0);
    } else if (id == UIA_IsOffscreenPropertyId) {
        dxc_variant_bool(out, el->width <= 0.0f || el->height <= 0.0f);
    } else if (id == UIA_HelpTextPropertyId) {
        if (el->flags & DXC_ELEMENT_HAS_HINT) {
            out->vt = VT_BSTR;
            out->bstrVal = dxc_bstr_from_utf8(el->hint);
        }
    } else if (id == UIA_LandmarkTypePropertyId) {
        if (el->role == DXC_ROLE_NAVIGATION) {
            out->vt = VT_I4;
            out->lVal = UIA_NavigationLandmarkTypeId;
        }
    } else if (id == UIA_HeadingLevelPropertyId) {
        // The scene does not say which level a heading is, and a heading without a level is
        // not one Narrator moves between, so every heading is the first level.
        out->vt = VT_I4;
        out->lVal = el->role == DXC_ROLE_HEADING ? HeadingLevel1 : HeadingLevel_None;
    } else if (id == UIA_ProviderDescriptionPropertyId) {
        out->vt = VT_BSTR;
        out->bstrVal = SysAllocString(L"compose-rust element");
    }
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_provider_get_host_raw_element_provider(
    IRawElementProviderSimple *self, IRawElementProviderSimple **out
) {
    (void)self;
    *out = NULL;
    return S_OK;
}

// ---------------------------------------------------------------------------
// DxcProvider: IRawElementProviderFragment
// ---------------------------------------------------------------------------

static HRESULT STDMETHODCALLTYPE dxc_frag_qi(
    IRawElementProviderFragment *self, REFIID riid, void **out
) {
    DxcProvider *provider = dxc_provider_from_fragment(self);
    return dxc_provider_qi((IRawElementProviderSimple *)&provider->simple_vtbl, riid, out);
}

static ULONG STDMETHODCALLTYPE dxc_frag_addref(IRawElementProviderFragment *self) {
    DxcProvider *provider = dxc_provider_from_fragment(self);
    return (ULONG)InterlockedIncrement(&provider->ref_count);
}

static ULONG STDMETHODCALLTYPE dxc_frag_release(IRawElementProviderFragment *self) {
    DxcProvider *provider = dxc_provider_from_fragment(self);
    return dxc_provider_release((IRawElementProviderSimple *)&provider->simple_vtbl);
}

// The tree the scene described: each element's parent is the element whose id it named, or
// the window, and its siblings are the elements with the same parent, in the scene's order.
static HRESULT STDMETHODCALLTYPE dxc_frag_navigate(
    IRawElementProviderFragment *self, enum NavigateDirection dir,
    IRawElementProviderFragment **out
) {
    DxcProvider *provider = dxc_provider_from_fragment(self);
    *out = NULL;
    if (!dxc_provider_live(provider)) return UIA_E_ELEMENTNOTAVAILABLE;
    DxcRootProvider *root = provider->root;
    const struct dxc_uia_links *links = &root->links[provider->index];
    switch (dir) {
    case NavigateDirection_Parent:
        if (links->parent >= 0) {
            *out = dxc_child_fragment(root, links->parent);
        } else {
            *out = (IRawElementProviderFragment *)&root->fragment_vtbl;
            InterlockedIncrement(&root->ref_count);
        }
        return S_OK;
    case NavigateDirection_NextSibling:
        *out = dxc_child_fragment(root, links->next);
        return S_OK;
    case NavigateDirection_PreviousSibling:
        *out = dxc_child_fragment(root, links->previous);
        return S_OK;
    case NavigateDirection_FirstChild:
        *out = dxc_child_fragment(root, links->first_child);
        return S_OK;
    case NavigateDirection_LastChild:
        *out = dxc_child_fragment(root, links->last_child);
        return S_OK;
    default:
        return S_OK;
    }
}

static HRESULT STDMETHODCALLTYPE dxc_frag_get_runtime_id(
    IRawElementProviderFragment *self, SAFEARRAY **out
) {
    DxcProvider *provider = dxc_provider_from_fragment(self);
    // UiaAppendRuntimeId, then a number unique among this window's elements.
    int ids[2];
    ids[0] = UiaAppendRuntimeId;
    ids[1] = dxc_uia_runtime_key(&provider->snapshot, provider->index);
    SAFEARRAYBOUND bound;
    bound.lLbound = 0;
    bound.cElements = 2;
    SAFEARRAY *array = SafeArrayCreate(VT_I4, 1, &bound);
    if (array == NULL) {
        *out = NULL;
        return E_OUTOFMEMORY;
    }
    for (LONG i = 0; i < 2; i++) {
        SafeArrayPutElement(array, &i, &ids[i]);
    }
    *out = array;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_frag_get_bounding_rect(
    IRawElementProviderFragment *self, struct UiaRect *out
) {
    DxcProvider *provider = dxc_provider_from_fragment(self);
    if (provider->root != NULL && provider->root->window != NULL) {
        dxc_element_rect_to_screen(provider->root->window, &provider->snapshot, out);
    } else {
        memset(out, 0, sizeof *out);
    }
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_frag_get_embedded_fragment_roots(
    IRawElementProviderFragment *self, SAFEARRAY **out
) {
    (void)self;
    *out = NULL;
    return S_OK;
}

// A reader moving focus to an element asks the scene to focus it. An element that cannot
// take focus is left alone, which is what UIA asks of one.
static HRESULT STDMETHODCALLTYPE dxc_frag_set_focus(IRawElementProviderFragment *self) {
    DxcProvider *provider = dxc_provider_from_fragment(self);
    if (!dxc_provider_live(provider)) return UIA_E_ELEMENTNOTAVAILABLE;
    if (!(provider->snapshot.actions & DXC_ACTION_FOCUS)) return S_OK;
    return dxc_provider_request(provider, DXC_ACTION_FOCUS, NULL);
}

static HRESULT STDMETHODCALLTYPE dxc_frag_get_fragment_root(
    IRawElementProviderFragment *self, IRawElementProviderFragmentRoot **out
) {
    DxcProvider *provider = dxc_provider_from_fragment(self);
    if (provider->root != NULL) {
        *out = (IRawElementProviderFragmentRoot *)&provider->root->fragment_root_vtbl;
        InterlockedIncrement(&provider->root->ref_count);
    } else {
        *out = NULL;
    }
    return S_OK;
}

// ---------------------------------------------------------------------------
// The control patterns
// ---------------------------------------------------------------------------
//
// Each pattern interface is one more vtable pointer in the element's object, so its
// IUnknown methods find the object and answer as the element does.

#define DXC_PATTERN_IUNKNOWN(prefix, Iface, field)                                              \
    static HRESULT STDMETHODCALLTYPE prefix##_qi(Iface *self, REFIID riid, void **out) {        \
        DxcProvider *provider = DXC_PROVIDER_FROM(self, field);                                \
        return dxc_provider_qi((IRawElementProviderSimple *)&provider->simple_vtbl, riid, out); \
    }                                                                                           \
    static ULONG STDMETHODCALLTYPE prefix##_addref(Iface *self) {                               \
        return (ULONG)InterlockedIncrement(&DXC_PROVIDER_FROM(self, field)->ref_count);        \
    }                                                                                           \
    static ULONG STDMETHODCALLTYPE prefix##_release(Iface *self) {                              \
        DxcProvider *provider = DXC_PROVIDER_FROM(self, field);                                \
        return dxc_provider_release((IRawElementProviderSimple *)&provider->simple_vtbl);       \
    }

DXC_PATTERN_IUNKNOWN(dxc_invoke, IInvokeProvider, invoke_vtbl)
DXC_PATTERN_IUNKNOWN(dxc_toggle, IToggleProvider, toggle_vtbl)
DXC_PATTERN_IUNKNOWN(dxc_value, IValueProvider, value_vtbl)
DXC_PATTERN_IUNKNOWN(dxc_range, IRangeValueProvider, range_vtbl)
DXC_PATTERN_IUNKNOWN(dxc_selection, ISelectionItemProvider, selection_vtbl)
DXC_PATTERN_IUNKNOWN(dxc_expand, IExpandCollapseProvider, expand_vtbl)

// Invoke: what a click does.
static HRESULT STDMETHODCALLTYPE dxc_invoke_invoke(IInvokeProvider *self) {
    return dxc_provider_request(DXC_PROVIDER_FROM(self, invoke_vtbl), DXC_ACTION_PRESS, NULL);
}

// Toggle: a checkbox or switch is pressed to change its state, as a click would.
static HRESULT STDMETHODCALLTYPE dxc_toggle_toggle(IToggleProvider *self) {
    DxcProvider *provider = DXC_PROVIDER_FROM(self, toggle_vtbl);
    if (!(provider->snapshot.actions & DXC_ACTION_PRESS)) return UIA_E_INVALIDOPERATION;
    return dxc_provider_request(provider, DXC_ACTION_PRESS, NULL);
}

static HRESULT STDMETHODCALLTYPE dxc_toggle_get_state(IToggleProvider *self, enum ToggleState *out) {
    DxcProvider *provider = DXC_PROVIDER_FROM(self, toggle_vtbl);
    *out = (enum ToggleState)dxc_uia_toggle_state(provider->snapshot.states);
    return S_OK;
}

// Value: a field's text, which a reader may replace when the field takes it.
static HRESULT STDMETHODCALLTYPE dxc_value_set(IValueProvider *self, LPCWSTR value) {
    DxcProvider *provider = DXC_PROVIDER_FROM(self, value_vtbl);
    if (!dxc_provider_live(provider)) return UIA_E_ELEMENTNOTAVAILABLE;
    if (!(provider->snapshot.states & DXC_STATE_ENABLED)) return UIA_E_ELEMENTNOTENABLED;
    if (!dxc_uia_value_settable(&provider->snapshot)) return UIA_E_INVALIDOPERATION;
    if (value == NULL) value = L"";
    int bytes = WideCharToMultiByte(CP_UTF8, 0, value, -1, NULL, 0, NULL, NULL);
    if (bytes <= 0) return E_INVALIDARG;
    char *utf8 = (char *)malloc((size_t)bytes);
    if (utf8 == NULL) return E_OUTOFMEMORY;
    WideCharToMultiByte(CP_UTF8, 0, value, -1, utf8, bytes, NULL, NULL);
    HRESULT result = dxc_provider_request(provider, DXC_ACTION_SET_VALUE, utf8);
    free(utf8);
    return result;
}

static HRESULT STDMETHODCALLTYPE dxc_value_get(IValueProvider *self, BSTR *out) {
    DxcProvider *provider = DXC_PROVIDER_FROM(self, value_vtbl);
    const struct dxc_element *el = &provider->snapshot;
    *out = dxc_bstr_from_utf8((el->flags & DXC_ELEMENT_HAS_VALUE) ? el->value : "");
    return *out != NULL ? S_OK : E_OUTOFMEMORY;
}

static HRESULT STDMETHODCALLTYPE dxc_value_get_read_only(IValueProvider *self, BOOL *out) {
    *out = !dxc_uia_value_settable(&DXC_PROVIDER_FROM(self, value_vtbl)->snapshot);
    return S_OK;
}

// RangeValue: a slider or a progress indicator. A new value reaches the scene as text, the
// way a reader's text reaches a field, and the scene reads the number from it.
static HRESULT STDMETHODCALLTYPE dxc_range_set(IRangeValueProvider *self, double value) {
    DxcProvider *provider = DXC_PROVIDER_FROM(self, range_vtbl);
    const struct dxc_element *el = &provider->snapshot;
    if (!dxc_provider_live(provider)) return UIA_E_ELEMENTNOTAVAILABLE;
    if (!(el->states & DXC_STATE_ENABLED)) return UIA_E_ELEMENTNOTENABLED;
    if (!dxc_uia_value_settable(el)) return UIA_E_INVALIDOPERATION;
    if (value < (double)el->range_min || value > (double)el->range_max) return E_INVALIDARG;
    char text[48];
    _snprintf_s(text, sizeof text, _TRUNCATE, "%.9g", value);
    return dxc_provider_request(provider, DXC_ACTION_SET_VALUE, text);
}

static HRESULT STDMETHODCALLTYPE dxc_range_get(IRangeValueProvider *self, double *out) {
    *out = (double)DXC_PROVIDER_FROM(self, range_vtbl)->snapshot.range_value;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_range_get_read_only(IRangeValueProvider *self, BOOL *out) {
    *out = !dxc_uia_value_settable(&DXC_PROVIDER_FROM(self, range_vtbl)->snapshot);
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_range_get_maximum(IRangeValueProvider *self, double *out) {
    *out = (double)DXC_PROVIDER_FROM(self, range_vtbl)->snapshot.range_max;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_range_get_minimum(IRangeValueProvider *self, double *out) {
    *out = (double)DXC_PROVIDER_FROM(self, range_vtbl)->snapshot.range_min;
    return S_OK;
}

// The scene moves a slider a twentieth of its range for one step when it does not say how
// many steps it has, so that is the small change; a large one is two of them.
static HRESULT STDMETHODCALLTYPE dxc_range_get_small_change(IRangeValueProvider *self, double *out) {
    const struct dxc_element *el = &DXC_PROVIDER_FROM(self, range_vtbl)->snapshot;
    *out = ((double)el->range_max - (double)el->range_min) / 20.0;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_range_get_large_change(IRangeValueProvider *self, double *out) {
    const struct dxc_element *el = &DXC_PROVIDER_FROM(self, range_vtbl)->snapshot;
    *out = ((double)el->range_max - (double)el->range_min) / 10.0;
    return S_OK;
}

// SelectionItem: a tab, a radio button, a list item or a row is chosen by pressing it. The
// scene chooses one at a time, so adding to the choice or taking one out of it is not a
// thing it does, except where it is already so.
static HRESULT STDMETHODCALLTYPE dxc_selection_select(ISelectionItemProvider *self) {
    DxcProvider *provider = DXC_PROVIDER_FROM(self, selection_vtbl);
    if (!(provider->snapshot.actions & DXC_ACTION_PRESS)) return UIA_E_INVALIDOPERATION;
    return dxc_provider_request(provider, DXC_ACTION_PRESS, NULL);
}

static HRESULT STDMETHODCALLTYPE dxc_selection_add(ISelectionItemProvider *self) {
    DxcProvider *provider = DXC_PROVIDER_FROM(self, selection_vtbl);
    if (provider->snapshot.states & DXC_STATE_SELECTED) return S_OK;
    return UIA_E_INVALIDOPERATION;
}

static HRESULT STDMETHODCALLTYPE dxc_selection_remove(ISelectionItemProvider *self) {
    DxcProvider *provider = DXC_PROVIDER_FROM(self, selection_vtbl);
    if (!(provider->snapshot.states & DXC_STATE_SELECTED)) return S_OK;
    return UIA_E_INVALIDOPERATION;
}

static HRESULT STDMETHODCALLTYPE dxc_selection_get_selected(ISelectionItemProvider *self, BOOL *out) {
    *out = (DXC_PROVIDER_FROM(self, selection_vtbl)->snapshot.states & DXC_STATE_SELECTED) != 0;
    return S_OK;
}

// No element here offers the Selection pattern a container would, so there is none to name.
static HRESULT STDMETHODCALLTYPE dxc_selection_get_container(
    ISelectionItemProvider *self, IRawElementProviderSimple **out
) {
    (void)self;
    *out = NULL;
    return S_OK;
}

// ExpandCollapse: a tree row, a disclosure, a menu button.
static HRESULT dxc_expand_to(DxcProvider *provider, int expand) {
    int32_t action = dxc_uia_expand_action(&provider->snapshot, expand);
    if (action != 0) return dxc_provider_request(provider, action, NULL);
    int open = (provider->snapshot.states & DXC_STATE_EXPANDED) != 0;
    return open == (expand != 0) ? S_OK : UIA_E_INVALIDOPERATION;
}

static HRESULT STDMETHODCALLTYPE dxc_expand_expand(IExpandCollapseProvider *self) {
    return dxc_expand_to(DXC_PROVIDER_FROM(self, expand_vtbl), 1);
}

static HRESULT STDMETHODCALLTYPE dxc_expand_collapse(IExpandCollapseProvider *self) {
    return dxc_expand_to(DXC_PROVIDER_FROM(self, expand_vtbl), 0);
}

static HRESULT STDMETHODCALLTYPE dxc_expand_get_state(IExpandCollapseProvider *self, enum ExpandCollapseState *out) {
    *out = (enum ExpandCollapseState)dxc_uia_expand_state(DXC_PROVIDER_FROM(self, expand_vtbl)->snapshot.states);
    return S_OK;
}

// ---------------------------------------------------------------------------
// DxcRootProvider: IRawElementProviderSimple
// ---------------------------------------------------------------------------

static HRESULT STDMETHODCALLTYPE dxc_root_qi(
    IRawElementProviderSimple *self, REFIID riid, void **out
) {
    DxcRootProvider *root = dxc_root_from_simple(self);
    if (IsEqualIID(riid, &IID_IUnknown) ||
        IsEqualIID(riid, &IID_IRawElementProviderSimple)) {
        *out = &root->simple_vtbl;
        InterlockedIncrement(&root->ref_count);
        return S_OK;
    }
    if (IsEqualIID(riid, &IID_IRawElementProviderFragment)) {
        *out = &root->fragment_vtbl;
        InterlockedIncrement(&root->ref_count);
        return S_OK;
    }
    if (IsEqualIID(riid, &IID_IRawElementProviderFragmentRoot)) {
        *out = &root->fragment_root_vtbl;
        InterlockedIncrement(&root->ref_count);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static ULONG STDMETHODCALLTYPE dxc_root_addref(IRawElementProviderSimple *self) {
    DxcRootProvider *root = dxc_root_from_simple(self);
    return (ULONG)InterlockedIncrement(&root->ref_count);
}

static ULONG STDMETHODCALLTYPE dxc_root_release(IRawElementProviderSimple *self) {
    DxcRootProvider *root = dxc_root_from_simple(self);
    LONG count = InterlockedDecrement(&root->ref_count);
    // The root is not freed here: it lives as long as the window does, and the
    // ref count going to zero just means UIA has let go of the reference it was
    // holding. It will come back.
    return (ULONG)count;
}

static HRESULT STDMETHODCALLTYPE dxc_root_get_provider_options(
    IRawElementProviderSimple *self, enum ProviderOptions *out
) {
    (void)self;
    *out = ProviderOptions_ServerSideProvider;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_root_get_pattern_provider(
    IRawElementProviderSimple *self, PATTERNID id, IUnknown **out
) {
    (void)self;
    (void)id;
    *out = NULL;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_root_get_property_value(
    IRawElementProviderSimple *self, PROPERTYID id, VARIANT *out
) {
    DxcRootProvider *root = dxc_root_from_simple(self);
    VariantInit(out);
    // An if chain for the same reason as the element provider's.
    if (id == UIA_ControlTypePropertyId) {
        out->vt = VT_I4;
        out->lVal = UIA_WindowControlTypeId;
    } else if (id == UIA_NamePropertyId) {
        wchar_t title[256] = L"";
        if (root->window != NULL) {
            GetWindowTextW(root->window, title, (int)(sizeof title / sizeof *title));
        }
        out->vt = VT_BSTR;
        out->bstrVal = SysAllocString(title);
    } else if (id == UIA_IsControlElementPropertyId || id == UIA_IsContentElementPropertyId) {
        out->vt = VT_BOOL;
        out->boolVal = VARIANT_TRUE;
    } else if (id == UIA_IsKeyboardFocusablePropertyId) {
        out->vt = VT_BOOL;
        out->boolVal = VARIANT_TRUE;
    } else if (id == UIA_ProviderDescriptionPropertyId) {
        out->vt = VT_BSTR;
        out->bstrVal = SysAllocString(L"compose-rust root");
    }
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_root_get_host_raw_element_provider(
    IRawElementProviderSimple *self, IRawElementProviderSimple **out
) {
    DxcRootProvider *root = dxc_root_from_simple(self);
    *out = NULL;
    if (root->window == NULL) return UIA_E_ELEMENTNOTAVAILABLE;
    return UiaHostProviderFromHwnd(root->window, out);
}

// ---------------------------------------------------------------------------
// DxcRootProvider: IRawElementProviderFragment
// ---------------------------------------------------------------------------

static HRESULT STDMETHODCALLTYPE dxc_root_frag_qi(
    IRawElementProviderFragment *self, REFIID riid, void **out
) {
    DxcRootProvider *root = dxc_root_from_fragment(self);
    return dxc_root_qi((IRawElementProviderSimple *)&root->simple_vtbl, riid, out);
}

static ULONG STDMETHODCALLTYPE dxc_root_frag_addref(IRawElementProviderFragment *self) {
    DxcRootProvider *root = dxc_root_from_fragment(self);
    return (ULONG)InterlockedIncrement(&root->ref_count);
}

static ULONG STDMETHODCALLTYPE dxc_root_frag_release(IRawElementProviderFragment *self) {
    DxcRootProvider *root = dxc_root_from_fragment(self);
    return dxc_root_release((IRawElementProviderSimple *)&root->simple_vtbl);
}

// The root's children are the elements directly in the window: those that name no parent,
// or a parent the scene left out.
static HRESULT STDMETHODCALLTYPE dxc_root_frag_navigate(
    IRawElementProviderFragment *self, enum NavigateDirection dir,
    IRawElementProviderFragment **out
) {
    DxcRootProvider *root = dxc_root_from_fragment(self);
    *out = NULL;
    switch (dir) {
    case NavigateDirection_FirstChild:
        *out = dxc_child_fragment(root, dxc_uia_first_root(root->links, root->child_count));
        return S_OK;
    case NavigateDirection_LastChild:
        *out = dxc_child_fragment(root, dxc_uia_last_root(root->links, root->child_count));
        return S_OK;
    default:
        // The root has no parent and no siblings in our tree.
        return S_OK;
    }
}

static HRESULT STDMETHODCALLTYPE dxc_root_frag_get_runtime_id(
    IRawElementProviderFragment *self, SAFEARRAY **out
) {
    (void)self;
    // The root is hosted by the HWND, so its runtime ID is NULL: the system
    // uses the HWND's own identity.
    *out = NULL;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_root_frag_get_bounding_rect(
    IRawElementProviderFragment *self, struct UiaRect *out
) {
    DxcRootProvider *root = dxc_root_from_fragment(self);
    if (root->window != NULL) {
        RECT rc;
        GetClientRect(root->window, &rc);
        POINT pt = {0, 0};
        ClientToScreen(root->window, &pt);
        out->left = (double)pt.x;
        out->top = (double)pt.y;
        out->width = (double)(rc.right - rc.left);
        out->height = (double)(rc.bottom - rc.top);
    } else {
        memset(out, 0, sizeof *out);
    }
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_root_frag_get_embedded_fragment_roots(
    IRawElementProviderFragment *self, SAFEARRAY **out
) {
    (void)self;
    *out = NULL;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_root_frag_set_focus(IRawElementProviderFragment *self) {
    (void)self;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_root_frag_get_fragment_root(
    IRawElementProviderFragment *self, IRawElementProviderFragmentRoot **out
) {
    DxcRootProvider *root = dxc_root_from_fragment(self);
    *out = (IRawElementProviderFragmentRoot *)&root->fragment_root_vtbl;
    InterlockedIncrement(&root->ref_count);
    return S_OK;
}

// ---------------------------------------------------------------------------
// DxcRootProvider: IRawElementProviderFragmentRoot
// ---------------------------------------------------------------------------

static HRESULT STDMETHODCALLTYPE dxc_root_fr_qi(
    IRawElementProviderFragmentRoot *self, REFIID riid, void **out
) {
    DxcRootProvider *root = dxc_root_from_fragment_root(self);
    return dxc_root_qi((IRawElementProviderSimple *)&root->simple_vtbl, riid, out);
}

static ULONG STDMETHODCALLTYPE dxc_root_fr_addref(IRawElementProviderFragmentRoot *self) {
    DxcRootProvider *root = dxc_root_from_fragment_root(self);
    return (ULONG)InterlockedIncrement(&root->ref_count);
}

static ULONG STDMETHODCALLTYPE dxc_root_fr_release(IRawElementProviderFragmentRoot *self) {
    DxcRootProvider *root = dxc_root_from_fragment_root(self);
    return dxc_root_release((IRawElementProviderSimple *)&root->simple_vtbl);
}

static HRESULT STDMETHODCALLTYPE dxc_root_fr_element_from_point(
    IRawElementProviderFragmentRoot *self, double x, double y,
    IRawElementProviderFragment **out
) {
    DxcRootProvider *root = dxc_root_from_fragment_root(self);
    *out = NULL;
    if (root->window == NULL) return S_OK;
    // Screen pixels to window points, which is what the elements are described in.
    POINT screen_pt;
    screen_pt.x = (LONG)x;
    screen_pt.y = (LONG)y;
    ScreenToClient(root->window, &screen_pt);
    float scale = dxc_scale_of(root->window);
    float px = (float)screen_pt.x / scale;
    float py = (float)screen_pt.y / scale;
    for (int32_t i = root->child_count - 1; i >= 0; i--) {
        const struct dxc_element *el = &root->children[i]->snapshot;
        if (px >= el->x && px < el->x + el->width && py >= el->y && py < el->y + el->height) {
            *out = dxc_child_fragment(root, i);
            return S_OK;
        }
    }
    return S_OK;
}

// The element the scene said has focus. Nothing, when focus is on no element the scene
// described, which UIA reads as the window itself.
static HRESULT STDMETHODCALLTYPE dxc_root_fr_get_focus(
    IRawElementProviderFragmentRoot *self, IRawElementProviderFragment **out
) {
    DxcRootProvider *root = dxc_root_from_fragment_root(self);
    *out = NULL;
    for (int32_t i = root->child_count - 1; i >= 0; i--) {
        if (root->children[i]->snapshot.states & DXC_STATE_FOCUSED) {
            *out = dxc_child_fragment(root, i);
            break;
        }
    }
    return S_OK;
}

// ---------------------------------------------------------------------------
// Building and replacing the elements
// ---------------------------------------------------------------------------

static DxcProvider *dxc_create_provider(
    const struct dxc_element *element, int32_t index, DxcRootProvider *root
) {
    DxcProvider *provider = (DxcProvider *)calloc(1, sizeof(DxcProvider));
    if (provider == NULL) return NULL;
    provider->simple_vtbl = &dxc_provider_simple_vtbl;
    provider->fragment_vtbl = &dxc_provider_fragment_vtbl;
    provider->invoke_vtbl = &dxc_invoke_vtbl;
    provider->toggle_vtbl = &dxc_toggle_vtbl;
    provider->value_vtbl = &dxc_value_vtbl;
    provider->range_vtbl = &dxc_range_vtbl;
    provider->selection_vtbl = &dxc_selection_vtbl;
    provider->expand_vtbl = &dxc_expand_vtbl;
    provider->ref_count = 1;
    provider->index = index;
    provider->alive = 1;
    provider->snapshot = *element;
    provider->root = root;
    return provider;
}

// Lets go of an element the tree no longer has. A reader still holding it is told it is gone.
static void dxc_retire_provider(DxcProvider *provider) {
    provider->alive = 0;
    dxc_provider_release((IRawElementProviderSimple *)&provider->simple_vtbl);
}

static void dxc_release_children(DxcRootProvider *root) {
    if (root->children != NULL) {
        for (int32_t i = 0; i < root->child_count; i++) {
            if (root->children[i] != NULL) dxc_retire_provider(root->children[i]);
        }
        free(root->children);
        root->children = NULL;
    }
    free(root->links);
    root->links = NULL;
    root->child_count = 0;
}

static void dxc_raise_int_change(DxcProvider *provider, PROPERTYID id, int32_t before, int32_t after) {
    if (before == after) return;
    VARIANT old_value;
    VARIANT new_value;
    VariantInit(&old_value);
    VariantInit(&new_value);
    old_value.vt = VT_I4;
    old_value.lVal = before;
    new_value.vt = VT_I4;
    new_value.lVal = after;
    UiaRaiseAutomationPropertyChangedEvent(
        (IRawElementProviderSimple *)&provider->simple_vtbl, id, old_value, new_value);
}

static void dxc_raise_bool_change(DxcProvider *provider, PROPERTYID id, int before, int after) {
    if ((before != 0) == (after != 0)) return;
    VARIANT old_value;
    VARIANT new_value;
    VariantInit(&old_value);
    VariantInit(&new_value);
    dxc_variant_bool(&old_value, before);
    dxc_variant_bool(&new_value, after);
    UiaRaiseAutomationPropertyChangedEvent(
        (IRawElementProviderSimple *)&provider->simple_vtbl, id, old_value, new_value);
}

static void dxc_raise_text_change(DxcProvider *provider, PROPERTYID id, const char *before, const char *after) {
    if (strcmp(before, after) == 0) return;
    VARIANT old_value;
    VARIANT new_value;
    VariantInit(&old_value);
    VariantInit(&new_value);
    old_value.vt = VT_BSTR;
    old_value.bstrVal = dxc_bstr_from_utf8(before);
    new_value.vt = VT_BSTR;
    new_value.bstrVal = dxc_bstr_from_utf8(after);
    UiaRaiseAutomationPropertyChangedEvent(
        (IRawElementProviderSimple *)&provider->simple_vtbl, id, old_value, new_value);
    VariantClear(&old_value);
    VariantClear(&new_value);
}

/**
 * Tells a reader what changed about an element the scene kept: its name, whether it is
 * enabled, checked, chosen or open, and its value. A reader announces these as they happen
 * rather than when it next asks.
 */
static void dxc_raise_changes(DxcProvider *provider, const struct dxc_element *before) {
    const struct dxc_element *after = &provider->snapshot;
    int32_t patterns = dxc_uia_patterns(after);
    dxc_raise_text_change(provider, UIA_NamePropertyId, before->label, after->label);
    dxc_raise_bool_change(provider, UIA_IsEnabledPropertyId,
                          before->states & DXC_STATE_ENABLED, after->states & DXC_STATE_ENABLED);
    if (patterns & DXC_UIA_TOGGLE) {
        dxc_raise_int_change(provider, UIA_ToggleToggleStatePropertyId,
                             dxc_uia_toggle_state(before->states), dxc_uia_toggle_state(after->states));
    }
    if (patterns & DXC_UIA_SELECTION_ITEM) {
        dxc_raise_bool_change(provider, UIA_SelectionItemIsSelectedPropertyId,
                              before->states & DXC_STATE_SELECTED, after->states & DXC_STATE_SELECTED);
    }
    if (patterns & DXC_UIA_EXPAND_COLLAPSE) {
        dxc_raise_int_change(provider, UIA_ExpandCollapseExpandCollapseStatePropertyId,
                             dxc_uia_expand_state(before->states), dxc_uia_expand_state(after->states));
    }
    if (patterns & DXC_UIA_VALUE) {
        dxc_raise_text_change(provider, UIA_ValueValuePropertyId,
                              (before->flags & DXC_ELEMENT_HAS_VALUE) ? before->value : "",
                              (after->flags & DXC_ELEMENT_HAS_VALUE) ? after->value : "");
    }
    if ((patterns & DXC_UIA_RANGE_VALUE) && before->range_value != after->range_value) {
        VARIANT old_value;
        VARIANT new_value;
        VariantInit(&old_value);
        VariantInit(&new_value);
        old_value.vt = VT_R8;
        old_value.dblVal = (double)before->range_value;
        new_value.vt = VT_R8;
        new_value.dblVal = (double)after->range_value;
        UiaRaiseAutomationPropertyChangedEvent(
            (IRawElementProviderSimple *)&provider->simple_vtbl, UIA_RangeValueValuePropertyId,
            old_value, new_value);
    }
}

/**
 * Replaces the elements with [count] new ones. An element whose id was there before keeps its
 * object, so a reader holding it, or standing on it, still has it; when [raise] is set, what
 * changed about it is announced, and so is focus moving to another element.
 */
static void dxc_rebuild_children(DxcRootProvider *root,
                                 const struct dxc_element *elements,
                                 int32_t count, int raise) {
    if (count <= 0) {
        dxc_release_children(root);
        root->focused_id = -1;
        return;
    }
    DxcProvider **children = (DxcProvider **)calloc((size_t)count, sizeof(DxcProvider *));
    struct dxc_uia_links *links = (struct dxc_uia_links *)calloc((size_t)count, sizeof(struct dxc_uia_links));
    if (children == NULL || links == NULL) {
        free(children);
        free(links);
        return;
    }
    // Which old element each new one is, by id; -1 for a new element. Matched before anything
    // is moved, so running out of memory below leaves the old tree whole.
    int32_t *kept_from = (int32_t *)malloc((size_t)count * sizeof(int32_t));
    unsigned char *taken = (unsigned char *)calloc((size_t)root->child_count + 1, 1);
    if (kept_from == NULL || taken == NULL) {
        free(kept_from);
        free(taken);
        free(children);
        free(links);
        return;
    }
    for (int32_t i = 0; i < count; i++) {
        kept_from[i] = -1;
        if (elements[i].id < 0) continue;
        for (int32_t j = 0; j < root->child_count; j++) {
            DxcProvider *old = root->children[j];
            if (taken[j] || old == NULL || old->snapshot.id != elements[i].id) continue;
            taken[j] = 1;
            kept_from[i] = j;
            break;
        }
    }
    free(taken);
    for (int32_t i = 0; i < count; i++) {
        if (kept_from[i] >= 0) continue;
        children[i] = dxc_create_provider(&elements[i], i, root);
        if (children[i] == NULL) {
            for (int32_t j = 0; j < i; j++) {
                if (kept_from[j] < 0) dxc_retire_provider(children[j]);
            }
            free(kept_from);
            free(children);
            free(links);
            return;
        }
    }
    int listening = raise && UiaClientsAreListening();
    for (int32_t i = 0; i < count; i++) {
        if (kept_from[i] < 0) continue;
        DxcProvider *kept = root->children[kept_from[i]];
        root->children[kept_from[i]] = NULL;
        struct dxc_element before = kept->snapshot;
        kept->snapshot = elements[i];
        kept->index = i;
        children[i] = kept;
        if (listening) dxc_raise_changes(kept, &before);
    }
    free(kept_from);
    dxc_release_children(root);
    dxc_uia_link(elements, count, links);
    root->children = children;
    root->links = links;
    root->child_count = count;

    int32_t focused = dxc_uia_focused(elements, count);
    int32_t focused_id = focused >= 0 ? elements[focused].id : -1;
    if (focused_id != root->focused_id) {
        root->focused_id = focused_id;
        if (listening && focused >= 0) {
            UiaRaiseAutomationEvent((IRawElementProviderSimple *)&children[focused]->simple_vtbl,
                                    UIA_AutomationFocusChangedEventId);
        }
    }
}

static void dxc_publish_accessibility(DxcRootProvider *root, int raise) {
    if (!dxc_a11y_dirty || root == NULL) return;
    dxc_rebuild_children(root, dxc_a11y_elements, dxc_a11y_count, raise);
    dxc_a11y_dirty = 0;
}

// ---------------------------------------------------------------------------
// The root provider, created lazily on the first push or the first
// WM_GETOBJECT, whichever comes first.
// ---------------------------------------------------------------------------

static DxcRootProvider *dxc_ensure_root_provider(HWND window) {
    if (dxc_root_provider != NULL) {
        dxc_root_provider->window = window;
        return dxc_root_provider;
    }
    DxcRootProvider *root = (DxcRootProvider *)calloc(1, sizeof(DxcRootProvider));
    if (root == NULL) return NULL;
    root->simple_vtbl = &dxc_root_simple_vtbl;
    root->fragment_vtbl = &dxc_root_fragment_vtbl;
    root->fragment_root_vtbl = &dxc_root_fragment_root_vtbl;
    root->ref_count = 1;
    root->window = window;
    root->focused_id = -1;
    dxc_root_provider = root;
    return root;
}

// ---------------------------------------------------------------------------
// dxc_native_set_accessibility
// ---------------------------------------------------------------------------
//
// Replaces what the window tells a reader who cannot see it.
//
// Called from the thread the scene lives on. The elements are copied into a
// snapshot and a message is posted to the window's thread so that the tree is
// rebuilt and UIA is notified after this call returns.
//
// The same name and the same signature as the macOS version. Kotlin calls one
// or the other depending on which file was compiled into the image.

void dxc_native_set_accessibility(const struct dxc_element *elements,
                                   int32_t count,
                                   void *window_pointer) {
    HWND window = (HWND)window_pointer;
    if (window == NULL) return;
    dxc_select_handle(window_pointer);
    if (count < 0 || (count > 0 && elements == NULL)) return;
    // Cap to the maximum the static buffer can hold.
    if (count > DXC_MAX_ELEMENTS) count = DXC_MAX_ELEMENTS;
    // The scene and window share a thread. Copy the caller's stack now and
    // defer provider rebuilding and notification to the message pump.
    if (count > 0) {
        memcpy(dxc_a11y_elements, elements,
               (size_t)count * sizeof(struct dxc_element));
    }
    dxc_a11y_count = count;
    dxc_a11y_dirty = 1;
    if (!dxc_a11y_update_posted) {
        dxc_a11y_update_posted = PostMessageW(
            window, DXC_WM_ACCESSIBILITY_UPDATE, 0, 0) != 0;
    }
}

/**
 * The new value of the oldest SET_VALUE request the selected window has not handed over, as
 * UTF-8 into [out], and how many bytes it is; -1 when there is none. When it does not fit in
 * [capacity] nothing is taken and the answer is the size it needs.
 */
int32_t dxc_native_take_accessibility_argument(char *out, int32_t capacity) {
    DxcWin *win = dxc_w;
    if (win->a11y_argument_count == 0) return -1;
    char *argument = win->a11y_arguments[win->a11y_argument_head];
    size_t length = strlen(argument);
    if (capacity < 0 || length > (size_t)capacity || (length > 0 && out == NULL)) return (int32_t)length;
    if (length > 0) memcpy(out, argument, length);
    free(argument);
    win->a11y_arguments[win->a11y_argument_head] = NULL;
    win->a11y_argument_head = (win->a11y_argument_head + 1) % DXC_A11Y_ARGUMENTS;
    win->a11y_argument_count--;
    return (int32_t)length;
}

/** Which mouse buttons are down, in the bits win32_buttons.h names. */
static int32_t dxc_pressed_buttons(void) {
    int32_t buttons = 0;
    if (GetKeyState(VK_LBUTTON) < 0) buttons |= DXC_PRIMARY_HELD;
    if (GetKeyState(VK_RBUTTON) < 0) buttons |= DXC_SECONDARY_HELD;
    if (GetKeyState(VK_MBUTTON) < 0) buttons |= DXC_MIDDLE_HELD;
    if (GetKeyState(VK_XBUTTON1) < 0) buttons |= DXC_BACK_HELD;
    if (GetKeyState(VK_XBUTTON2) < 0) buttons |= DXC_FORWARD_HELD;
    return buttons;
}

/**
 * Which modifier keys are held.
 *
 * Bits of our own choosing rather than a Win32 value, because there is no Win32 value:
 * the platform answers one key at a time. Shift, control, alt, then the Windows key.
 * What they mean to Compose is decided on the other side, where the table lives.
 */
static int32_t dxc_held_modifiers(void) {
    int32_t modifiers = 0;
    if (GetKeyState(VK_SHIFT) < 0) modifiers |= 1;
    if (GetKeyState(VK_CONTROL) < 0) modifiers |= 2;
    if (GetKeyState(VK_MENU) < 0) modifiers |= 4;
    if (GetKeyState(VK_LWIN) < 0 || GetKeyState(VK_RWIN) < 0) modifiers |= 8;
    return modifiers;
}

/** How far one notch of the wheel is meant to move, as the reader set it. */
static float dxc_wheel_lines(void) {
    UINT lines = 3;
    if (!SystemParametersInfoW(SPI_GETWHEELSCROLLLINES, 0, &lines, 0)) {
        lines = 3;
    }
    // A wheel set to move a page at a time answers with a sentinel rather than a count.
    if (lines == 0 || lines == WHEEL_PAGESCROLL) {
        lines = 3;
    }
    return (float)lines;
}

/*
 * The double-click interval set in the mouse settings, in milliseconds. user32, which this
 * file links already, answers it for the whole session.
 */
int64_t dxc_native_double_click_millis(void) {
    return (int64_t)GetDoubleClickTime();
}

/*
 * The Text size slider in Settings > Accessibility, as a percent (100 to 225); 0 when the
 * user never moved it. This is the value UISettings.TextScaleFactor reports, read from the
 * registry because UISettings is a WinRT class and this file stays plain C. advapi32 is
 * loaded by name for the same reason dwmapi is: no extra import library to link.
 */
typedef LONG (WINAPI *dxc_reg_get_value_fn)(HKEY, LPCWSTR, LPCWSTR, DWORD, LPDWORD, PVOID, LPDWORD);

int32_t dxc_native_text_scale_percent(void) {
    static dxc_reg_get_value_fn reg_get_value = NULL;
    static int resolved = 0;
    if (!resolved) {
        HMODULE advapi = LoadLibraryW(L"advapi32.dll");
        if (advapi != NULL) {
            reg_get_value = (dxc_reg_get_value_fn)(void *)GetProcAddress(advapi, "RegGetValueW");
        }
        resolved = 1;
    }
    if (reg_get_value == NULL) {
        return 0;
    }
    DWORD value = 0;
    DWORD size = sizeof value;
    LONG status = reg_get_value(HKEY_CURRENT_USER, L"Software\\Microsoft\\Accessibility",
                                L"TextScaleFactor", RRF_RT_REG_DWORD, NULL, &value, &size);
    return status == ERROR_SUCCESS ? (int32_t)value : 0;
}

/* Queues a text scale event for the current window when the slider moved since its last look. */
static void dxc_check_text_scale(void) {
    int32_t percent = dxc_native_text_scale_percent();
    int32_t seen = dxc_w->text_scale_seen;
    dxc_w->text_scale_seen = percent + 1;
    if (seen == percent + 1) {
        return;
    }
    struct dxc_event record;
    memset(&record, 0, sizeof record);
    record.kind = DXC_EVENT_TEXT_SCALE_CHANGED;
    record.x = (float)percent;
    dxc_push_event(record);
}

/** The DXC_*_BUTTON flag of the button a WM_*BUTTONDOWN or WM_*BUTTONUP message is about. */
static int32_t dxc_event_button(UINT message, WPARAM wparam) {
    switch (message) {
    case WM_RBUTTONDOWN:
    case WM_RBUTTONUP:
        return DXC_SECONDARY_BUTTON;
    case WM_MBUTTONDOWN:
    case WM_MBUTTONUP:
        return DXC_MIDDLE_BUTTON;
    case WM_XBUTTONDOWN:
    case WM_XBUTTONUP:
        return GET_XBUTTON_WPARAM(wparam) == XBUTTON1 ? DXC_BACK_BUTTON : DXC_FORWARD_BUTTON;
    default:
        return 0;
    }
}

// When the message being handled was posted, as GetMessageTime gives it: milliseconds since
// the system started in a LONG that wraps after 49.7 days, read unsigned. The scene keeps only
// the difference between two of these, and starts again from its own clock when it wraps.
static int64_t dxc_message_time(void) {
    return (int64_t)(uint32_t)GetMessageTime();
}

static void dxc_push_pointer(int32_t kind, LPARAM where, int32_t event_button) {
    struct dxc_event record;
    memset(&record, 0, sizeof record);
    record.kind = kind;
    record.x = (float)GET_X_LPARAM(where);
    record.y = (float)GET_Y_LPARAM(where);
    record.buttons = dxc_pointer_buttons(dxc_pressed_buttons(), event_button);
    record.modifiers = dxc_held_modifiers();
    record.time_millis = dxc_message_time();
    dxc_push_event(record);
}

static void dxc_push_scroll(float x, float y) {
    struct dxc_event record;
    memset(&record, 0, sizeof record);
    record.kind = DXC_EVENT_SCROLL;
    // The wheel's travel rides in the same two fields the pointer uses, because a scroll
    // has no position of its own beyond where the pointer already is.
    record.x = x;
    record.y = y;
    record.buttons = dxc_pressed_buttons();
    record.modifiers = dxc_held_modifiers();
    record.time_millis = dxc_message_time();
    dxc_push_event(record);
}

static void dxc_push_key_held(int32_t kind, WPARAM key, int32_t modifiers) {
    struct dxc_event record;
    memset(&record, 0, sizeof record);
    record.kind = kind;
    record.buttons = dxc_pressed_buttons();
    record.modifiers = modifiers;
    record.key_code = (int32_t)key;
    // The character the key carries with no modifier applied, which is what the macOS
    // side puts here. Windows delivers typed text as a separate message, so it is asked
    // for rather than waited for. A dead key answers with its top bit set, and the
    // character underneath is the part worth keeping.
    UINT typed = MapVirtualKeyW((UINT)key, MAPVK_VK_TO_CHAR);
    record.code_point = (int32_t)(typed & 0x7fffffffu);
    dxc_push_event(record);
}

static void dxc_push_key(int32_t kind, WPARAM key) {
    dxc_push_key_held(kind, key, dxc_held_modifiers());
}

// The window's own text edit menu (win32_edit_menu.h).

/**
 * The label of an entry in the language of the system, copied into [out]: the one the
 * system's edit control puts in its own context menu, which is the first menu of user32. The
 * menu is not a documented resource, so an entry it does not have, or a system without it,
 * takes the English label.
 */
static void dxc_edit_menu_label(int32_t id, WCHAR *out, int capacity) {
    static HMENU system_menu;
    static int looked;
    if (!looked) {
        looked = 1;
        HMODULE user32 = GetModuleHandleW(L"user32.dll");
        HMENU loaded = user32 != NULL ? LoadMenuW(user32, MAKEINTRESOURCEW(1)) : NULL;
        system_menu = loaded != NULL ? GetSubMenu(loaded, 0) : NULL;
    }
    out[0] = L'\0';
    if (system_menu != NULL &&
        GetMenuStringW(system_menu, dxc_edit_menu_system_command(id), out, capacity, MF_BYCOMMAND) > 0) {
        // An accelerator written after a tab is not part of the name.
        WCHAR *tab = wcschr(out, L'\t');
        if (tab != NULL) *tab = L'\0';
        if (out[0] != L'\0') return;
    }
    wcsncpy(out, dxc_edit_menu_fallback_label(id), (size_t)capacity - 1);
    out[capacity - 1] = L'\0';
}

/**
 * Puts the edit menu up and hands the entry chosen to the field as its shortcut, Control and
 * the entry's letter, pressed and let go. At [where] on the screen, or at the caret the
 * input method was last told of ([at_caret]) for a menu asked for from the keyboard. Does
 * nothing while no text field has focus.
 *
 * Returns when the menu closes. TrackPopupMenuEx runs the menu's own loop meanwhile, which
 * dispatches this window's messages (paint among them) but not the application's frame loop,
 * so the window shows its last frame until the menu is gone: a caret stops blinking for that
 * long, as it does under any Windows menu.
 */
static void dxc_show_edit_menu(HWND window, int at_caret, POINT where) {
    struct dxc_edit_entry entries[DXC_EDIT_ENTRY_COUNT];
    int32_t count = dxc_edit_menu_entries(dxc_edit_menu,
                                          IsClipboardFormatAvailable(CF_UNICODETEXT) ? 1 : 0, entries);
    if (count == 0) return;
    HMENU menu = CreatePopupMenu();
    if (menu == NULL) return;
    for (int32_t index = 0; index < count; index++) {
        WCHAR label[128];
        dxc_edit_menu_label(entries[index].id, label, (int)(sizeof label / sizeof *label));
        if (entries[index].separator_before) AppendMenuW(menu, MF_SEPARATOR, 0, NULL);
        AppendMenuW(menu, MF_STRING | (entries[index].enabled ? MF_ENABLED : MF_GRAYED),
                    (UINT_PTR)entries[index].id, label);
    }
    POINT at = where;
    if (at_caret) {
        if (dxc_ime_spot_x != 0 || dxc_ime_spot_y != 0) {
            at.x = dxc_ime_spot_x;
            at.y = dxc_ime_spot_y;
            ClientToScreen(window, &at);
        } else {
            GetCursorPos(&at);
        }
    }
    // Without the window in front the menu does not close when the reader clicks elsewhere;
    // the posted message lets it finish closing (KB135788).
    SetForegroundWindow(window);
    UINT command = (UINT)TrackPopupMenuEx(
        menu, TPM_RETURNCMD | TPM_RIGHTBUTTON | TPM_NONOTIFY | TPM_LEFTALIGN | TPM_TOPALIGN,
        at.x, at.y, window, NULL);
    PostMessageW(window, WM_NULL, 0, 0);
    DestroyMenu(menu);
    int32_t key = dxc_edit_menu_key((int32_t)command);
    // The window can have gone while the menu was up.
    if (key == 0 || !IsWindow(window)) return;
    // Control alone, whatever is still held: Shift from Shift and F10 would make it another
    // shortcut.
    dxc_push_key_held(DXC_EVENT_KEY_DOWN, (WPARAM)key, 2);
    dxc_push_key_held(DXC_EVENT_KEY_UP, (WPARAM)key, 2);
}

/**
 * Turns the window's edit menu on for a focused text field, or off (0). [flags] are the
 * DXC_EDIT_MENU_* bits of win32_edit_menu.h: ON, and with KNOWN what the field allows.
 */
void dxc_native_set_edit_menu(void *window_pointer, int32_t flags) {
    if (window_pointer == NULL) return;
    dxc_select_handle(window_pointer);
    dxc_edit_menu = flags;
}

static void dxc_push_text(int32_t kind, const uint16_t *text, size_t units) {
    struct dxc_event record;
    memset(&record, 0, sizeof record);
    record.kind = kind;
    dxc_utf16_to_utf8(text, units, record.text, sizeof record.text);
    dxc_push_event(record);
}

static void dxc_read_ime_text(HIMC context, DWORD part, int32_t kind) {
    LONG bytes = ImmGetCompositionStringW(context, part, NULL, 0);
    if (bytes < 0 || bytes % sizeof(WCHAR) != 0) return;
    if (bytes == 0) {
        if (kind == DXC_EVENT_TEXT_COMPOSE) dxc_push_text(kind, NULL, 0);
        return;
    }
    WCHAR *wide = (WCHAR *)malloc((size_t)bytes);
    if (wide == NULL) return;
    LONG copied = ImmGetCompositionStringW(context, part, wide, (DWORD)bytes);
    if (copied >= 0 && copied <= bytes && copied % sizeof(WCHAR) == 0) {
        dxc_push_text(kind, (const uint16_t *)wide, (size_t)copied / sizeof(WCHAR));
    }
    free(wide);
}

// Where the caret is in the client area, in pixels, as the renderer last said.

static void dxc_position_ime(HWND window) {
    HIMC context = ImmGetContext(window);
    if (context == NULL) return;
    COMPOSITIONFORM position;
    memset(&position, 0, sizeof position);
    position.dwStyle = CFS_POINT;
    // The caret position has not crossed from Compose yet. Keep the IME window at the
    // client area's top left, as the macOS text client does for the same reason.
    position.ptCurrentPos.x = dxc_ime_spot_x;
    position.ptCurrentPos.y = dxc_ime_spot_y;
    ImmSetCompositionWindow(context, &position);
    CANDIDATEFORM candidate;
    memset(&candidate, 0, sizeof candidate);
    candidate.dwStyle = CFS_CANDIDATEPOS;
    candidate.ptCurrentPos.x = dxc_ime_spot_x;
    candidate.ptCurrentPos.y = dxc_ime_spot_y + 20;
    ImmSetCandidateWindow(context, &candidate);
    ImmReleaseContext(window, context);
}

// What the application asked of its window, held until the window is made.
static struct dxc_window_options dxc_next_options = {1, 0, 0, 1, 0};

/**
 * Says how the next window should be made. Called once, before it is opened.
 *
 * `system_chrome` keeps the system's caption. Without it the caption strip becomes part of
 * the client area and the renderer draws the title and the three buttons in it. A window
 * nobody configured keeps the system's caption: taking the strip away is only safe for a
 * caller that has said it draws the buttons, and a caller that never said so would
 * otherwise get a window with no way to close it (thisisthepy/compose-multiplatform-core-extended#171). `backdrop`
 * means nothing here: this window has no material to put behind the page.
 */
void dxc_native_window_configure(
    int32_t resizable,
    int32_t min_width,
    int32_t min_height,
    int32_t system_chrome,
    int32_t backdrop
) {
    dxc_next_options.resizable = resizable;
    dxc_next_options.min_width = min_width;
    dxc_next_options.min_height = min_height;
    dxc_next_options.system_chrome = system_chrome;
    dxc_next_options.backdrop = backdrop;
    dxc_next_options.app_caption = 0;
    dxc_next_options.caption_dip = 0;
}

// When the frame drawn now will be on the screen, in nanoseconds. The macOS window answers
// from its display link and the native image's shared Kotlin declares it, so this window
// answers too, with the performance counter now.
int64_t dxc_native_frame_time_nanos(void) {
    static LARGE_INTEGER frequency;
    LARGE_INTEGER now;
    if (frequency.QuadPart == 0) {
        QueryPerformanceFrequency(&frequency);
    }
    QueryPerformanceCounter(&now);
    return (int64_t)(now.QuadPart / frequency.QuadPart) * 1000000000LL +
           (int64_t)(now.QuadPart % frequency.QuadPart) * 1000000000LL / frequency.QuadPart;
}

/*
 * Calls of the X11 window (graalvm-linux) that the renderer's one native image also carries on
 * Windows, because its code can reach them. A native image links every C function its
 * reachable code names, and on Windows a name left undefined is a DLL that does not link. None
 * of them is used on this desktop: the Win32 window is woken by its message queue, joins no
 * AT-SPI bus, composes text through IMM32 and counts its resize in win32_resize.h. Each answers
 * that it has nothing.
 */
void dxc_native_wake(void) {}

void dxc_native_window_origin(int32_t *x, int32_t *y) {
    *x = 0;
    *y = 0;
}

int32_t dxc_native_window_focused(void) {
    return 0;
}

/*
 * Not a stub like its neighbours: ends what the input method is composing where it stands,
 * sending nothing, and copies what it was composing into out as UTF-8. Answers how many bytes
 * that is, zero where there was nothing. Asked when a key moved the caret away from a syllable
 * the field has already kept, so the input method must not go on composing it at the new
 * caret. Cancelled rather than completed: completing would send the syllable as a result, and
 * the field would hold it twice. The messages the cancel sends back find the composition
 * already closed here and push nothing.
 */
int32_t dxc_native_ime_reset(char *out, int32_t capacity) {
    if (out != NULL && capacity > 0) out[0] = 0;
    if (dxc_window == NULL || !dxc_ime_composing) return 0;
    HIMC context = ImmGetContext(dxc_window);
    if (context == NULL) return 0;
    int32_t written = 0;
    if (out != NULL && capacity > 0) {
        WCHAR wide[256];
        LONG bytes = ImmGetCompositionStringW(context, GCS_COMPSTR, wide, (DWORD)sizeof wide);
        if (bytes > 0 && bytes % sizeof(WCHAR) == 0) {
            written = (int32_t)dxc_utf16_to_utf8((const uint16_t *)wide, (size_t)bytes / sizeof(WCHAR),
                                                 out, (size_t)capacity);
        }
    }
    dxc_ime_composing = 0;
    dxc_ime_cancelling = 1;
    ImmNotifyIME(context, NI_COMPOSITIONSTR, CPS_CANCEL, 0);
    dxc_ime_cancelling = 0;
    ImmReleaseContext(dxc_window, context);
    return written;
}

int64_t dxc_native_resize_serial(void) {
    return 0;
}

void dxc_native_window_server_size(int32_t *width, int32_t *height) {
    *width = 0;
    *height = 0;
}

/** The window style the options come to. */
static DWORD dxc_window_style(void) {
    DWORD style = WS_OVERLAPPEDWINDOW;
    if (!dxc_options.resizable) {
        style &= ~(DWORD)(WS_THICKFRAME | WS_MAXIMIZEBOX);
    }
    return style;
}

int32_t dxc_native_system_dark(void);

typedef HRESULT(WINAPI *dxc_dwm_set_attribute_fn)(HWND, DWORD, LPCVOID, DWORD);

/**
 * Tells DWM whether the selected window's caption is dark, from its color scheme. Looked up
 * at run time like DwmFlush. Tries DWMWA_USE_IMMERSIVE_DARK_MODE and then the number it had
 * before Windows 10 2004. 1 when DWM took it.
 */
static int dxc_apply_color_scheme(HWND window) {
    static int looked_up;
    static dxc_dwm_set_attribute_fn set_attribute;
    if (!looked_up) {
        looked_up = 1;
        HMODULE dwm = LoadLibraryW(L"dwmapi.dll");
        if (dwm != NULL) {
            set_attribute = (dxc_dwm_set_attribute_fn)(void *)GetProcAddress(dwm, "DwmSetWindowAttribute");
        }
    }
    if (set_attribute == NULL || window == NULL) return 0;
    BOOL dark = dxc_scheme_caption_dark(dxc_w->color_scheme, dxc_native_system_dark()) ? TRUE : FALSE;
    if (FAILED(set_attribute(window, DXC_SCHEME_DARK_ATTRIBUTE, &dark, sizeof dark)) &&
        FAILED(set_attribute(window, DXC_SCHEME_DARK_ATTRIBUTE_OLD, &dark, sizeof dark))) {
        return 0;
    }
    dxc_w->caption_dark = dark ? 1 : 0;
    dxc_w->scheme_applied = 1;
    SetWindowPos(window, NULL, 0, 0, 0, 0,
                 SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
    return 1;
}

/**
 * Makes the selected window resizable or not after it opened. The style and the caption's
 * hit test both read the option, so the frame and the top resize band change together.
 * 0 when no window is selected.
 */
int32_t dxc_native_window_set_resizable(int32_t resizable) {
    if (dxc_window == NULL) return 0;
    dxc_options.resizable = resizable ? 1 : 0;
    LONG_PTR style = GetWindowLongPtrW(dxc_window, GWL_STYLE);
    style = (LONG_PTR)dxc_scheme_style((uint32_t)style, dxc_options.resizable);
    SetWindowLongPtrW(dxc_window, GWL_STYLE, style);
    SetWindowPos(dxc_window, NULL, 0, 0, 0, 0,
                 SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
    return 1;
}

/**
 * Sets the selected window's caption to follow the system (0), stay light (1) or stay
 * dark (2). A window that follows the system follows it on WM_SETTINGCHANGE. 1 when DWM
 * took it; 0 with no window, a scheme it does not know, or a DWM that refused.
 */
int32_t dxc_native_window_set_color_scheme(int32_t scheme) {
    if (dxc_window == NULL || !dxc_scheme_valid(scheme)) return 0;
    dxc_w->color_scheme = scheme;
    return dxc_apply_color_scheme(dxc_window);
}

/** What the selected window's caption was last told, for tests. 0 until a scheme was applied. */
int32_t dxc_native_window_caption_dark(int32_t *dark) {
    if (!dxc_w->scheme_applied) return 0;
    if (dark != NULL) *dark = dxc_w->caption_dark;
    return 1;
}

/*
 * Taking the caption strip into the client area while the frame stays whole.
 *
 * The obvious way to draw your own title bar is an undecorated window, and on Windows
 * that is the wrong trade. The frame is not only the bar: it is the drop shadow, the
 * resize border, Snap Layouts and the animation when the window is restored. None of
 * those can be drawn from inside the window.
 *
 * What VS Code and Windows Terminal do instead is keep every one of those and take only
 * the caption. A window reports its client area in WM_NCCALCSIZE. Letting the default
 * handler compute the frame and then putting the top edge back where it started leaves
 * the sides and the bottom as the system's while the strip the caption occupied becomes
 * ours to draw in. The styles are untouched, so the shadow, the border and Snap are
 * untouched with them.
 *
 * Two details are not optional. A maximised window is deliberately laid out larger than
 * the monitor by the border thickness, so the same edges fall off screen; restoring the
 * top edge unchanged there puts the caption off screen too, and it has to be inset. And
 * the top resize band lived in the non-client area that no longer exists, so the hit test
 * has to answer for it or the window becomes the one window on the desktop that cannot be
 * resized from the top.
 *
 * The geometry is duplicated in Kotlin (common's Win32Chrome), which the scene lays the
 * buttons out with. scripts/check-windows-caption-metrics.sh fails if the two stop agreeing.
 */

// Windows 11 caption metrics, in device independent pixels, are in win32_caption.h with
// the hit test arithmetic, so the shared test can check them without a window.

static int dxc_scaled(HWND window, int dip) {
    UINT dpi = GetDpiForWindow(window);
    if (dpi == 0) {
        dpi = USER_DEFAULT_SCREEN_DPI;
    }
    return (int)MulDiv(dip, (int)dpi, USER_DEFAULT_SCREEN_DPI);
}

/** How far a maximised window hangs off every edge of its monitor. */
static int dxc_maximised_overhang(HWND window) {
    // At the window's own DPI: GetSystemMetrics answers for the primary monitor's, which
    // is wrong on a monitor of another scale.
    UINT dpi = GetDpiForWindow(window);
    if (dpi == 0) {
        dpi = USER_DEFAULT_SCREEN_DPI;
    }
    return GetSystemMetricsForDpi(SM_CYSIZEFRAME, dpi) + GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi);
}

static LRESULT dxc_caption_hit_test(HWND window, LPARAM lparam) {
    LRESULT where = DefWindowProcW(window, WM_NCHITTEST, 0, lparam);
    // Everywhere the frame still answers for keeps its answer: the sides, the bottom and
    // all four corners are still the system's.
    if (where != HTCLIENT) {
        return where;
    }
    POINT point = {GET_X_LPARAM(lparam), GET_Y_LPARAM(lparam)};
    RECT client;
    if (!ScreenToClient(window, &point) || !GetClientRect(window, &client)) {
        return where;
    }
    // Measured in the client area: a maximised window's top edge hangs off the monitor and
    // the client area is inset from it, which is where the renderer draws the buttons.
    switch (dxc_caption_zone_at(point.x, point.y, client.right, (int32_t)GetDpiForWindow(window),
                                dxc_options.caption_dip, dxc_maximised_overhang(window), dxc_options.resizable,
                                IsZoomed(window) ? 1 : 0)) {
    case DXC_CAPTION_ZONE_TOP:
        return HTTOP;
    case DXC_CAPTION_ZONE_CAPTION:
        // Drags the window, and brings back double click to maximise and the system menu
        // on right click. Where the application owns the strip it is the application's
        // content instead, and the scene begins a move from it (dxc_native_window_begin_drag).
        return dxc_caption_zone_is_system_caption(DXC_CAPTION_ZONE_CAPTION, dxc_options.app_caption)
            ? HTCAPTION
            : HTCLIENT;
    case DXC_CAPTION_ZONE_MAXIMISE:
        // Snap Layouts appears only over a window's HTMAXBUTTON. The press and the moves
        // that land here are non-client messages, forwarded to the scene below so the
        // renderer's button still hovers, presses and toggles.
        return HTMAXBUTTON;
    default:
        // Minimise and close are drawn by the renderer and take ordinary mouse input.
        return HTCLIENT;
    }
}

/**
 * Hands a non-client mouse message over the maximise button to the scene as the client
 * pointer event it would have been, with the point moved into client coordinates.
 */
static void dxc_push_caption_pointer(HWND window, int32_t kind, LPARAM screen) {
    POINT point = {GET_X_LPARAM(screen), GET_Y_LPARAM(screen)};
    ScreenToClient(window, &point);
    dxc_push_pointer(kind, MAKELPARAM(point.x, point.y), 0);
}

/** Tells the scene what win32_caption.h decided about a move or a leave. `screen` is read only for a move. */
static void dxc_forward_caption(HWND window, enum dxc_caption_forward forward, LPARAM screen) {
    switch (forward) {
    case DXC_CAPTION_FORWARD_MOVE:
        dxc_push_caption_pointer(window, DXC_EVENT_POINTER_MOVE, screen);
        break;
    case DXC_CAPTION_FORWARD_LEAVE:
        // Over nothing the scene draws, so it drops the hover it was showing.
        dxc_push_pointer(DXC_EVENT_POINTER_MOVE, MAKELPARAM(DXC_CAPTION_OUTSIDE, DXC_CAPTION_OUTSIDE), 0);
        break;
    default:
        break;
    }
}

/** Whether a mouse button is down or this window has the mouse, so a leave is not the end of anything. */
static int32_t dxc_pointer_held(HWND window) {
    return dxc_pressed_buttons() != 0 || GetCapture() == window ? 1 : 0;
}

/** True while the window is maximised, for the renderer's maximise or restore glyph. */
int32_t dxc_native_window_is_zoomed(void) {
    return dxc_window != NULL && IsZoomed(dxc_window) ? 1 : 0;
}

// Named apart from the one in `renderer_entry.c`, which subclasses the toolkit's frame
// to reclaim its caption. That one goes looking for a window of AWT's class and will
// not find this one, so the two never meet; the names are kept distinct anyway,
// because a reader who found both would have every reason to think they were.

typedef HRESULT(WINAPI *dxc_dwm_flush_fn)(void);
typedef HRESULT(WINAPI *dxc_dwm_enabled_fn)(BOOL *);

/**
 * DwmFlush, where DWM composition is on. Looked up at run time so the build links
 * nothing new. Zero when it did not run.
 */
static int dxc_dwm_flush(void) {
    static int looked_up;
    static dxc_dwm_flush_fn flush;
    static dxc_dwm_enabled_fn enabled;
    if (!looked_up) {
        looked_up = 1;
        HMODULE dwm = LoadLibraryW(L"dwmapi.dll");
        if (dwm != NULL) {
            flush = (dxc_dwm_flush_fn)(void *)GetProcAddress(dwm, "DwmFlush");
            enabled = (dxc_dwm_enabled_fn)(void *)GetProcAddress(dwm, "DwmIsCompositionEnabled");
        }
    }
    BOOL on = FALSE;
    if (flush == NULL || enabled == NULL || FAILED(enabled(&on)) || !on) {
        return 0;
    }
    flush();
    return 1;
}

static double dxc_elapsed_ms(LARGE_INTEGER since) {
    LARGE_INTEGER now, frequency;
    QueryPerformanceCounter(&now);
    QueryPerformanceFrequency(&frequency);
    return (double)(now.QuadPart - since.QuadPart) * 1000.0 / (double)frequency.QuadPart;
}

/** The performance counter in ms, the clock DXGI's present times are on, for the log lines. */
static double dxc_now_ms(void) {
    LARGE_INTEGER now, frequency;
    QueryPerformanceCounter(&now);
    QueryPerformanceFrequency(&frequency);
    return (double)now.QuadPart * 1000.0 / (double)frequency.QuadPart;
}

/*
 * Resize trace, for DXC_RESIZE_TRACE=1: when each message of a size change arrived and when
 * each step's frame was copied into the window, against DWM's own composition clock, so a
 * capture taken by another process can be lined up with the step it shows. Written into a
 * fixed array during the drag (no I/O while it runs) and printed when the size change ends.
 * Unset, nothing here runs.
 */
enum dxc_trace_kind {
    DXC_TRACE_SWP_CALL, DXC_TRACE_SWP_RET, DXC_TRACE_NCLBUTTONDOWN, DXC_TRACE_ENTERSIZEMOVE,
    DXC_TRACE_SIZING, DXC_TRACE_WPCHANGING, DXC_TRACE_NCCALC, DXC_TRACE_WPCHANGED,
    DXC_TRACE_WM_SIZE, DXC_TRACE_BLIT, DXC_TRACE_VALID, DXC_TRACE_WM_SIZE_RET,
    DXC_TRACE_EXITSIZEMOVE, DXC_TRACE_PAINT, DXC_TRACE_SEND, DXC_TRACE_KIND_COUNT
};
static const char *const dxc_trace_names[DXC_TRACE_KIND_COUNT] = {
    "SWP_CALL", "SWP_RET", "NCLBUTTONDOWN", "ENTERSIZEMOVE", "SIZING", "WPCHANGING", "NCCALC",
    "WPCHANGED", "WM_SIZE", "BLIT", "VALID", "WM_SIZE_RET", "EXITSIZEMOVE", "PAINT", "SEND",
};
struct dxc_trace_record {
    long long qpc;
    int kind;
    int width;
    int height;
    int step;
    unsigned long long frame;
    unsigned long long compose;
    unsigned long long vblank;
    unsigned long long refresh;
    unsigned long long frame_displayed;
    unsigned long long frame_displayed_qpc;
    long long cost;
};
#define DXC_TRACE_CAPACITY 32768
typedef HRESULT(WINAPI *dxc_dwm_timing_fn)(HWND, DWM_TIMING_INFO *);
static struct dxc_trace_record *dxc_trace_records;
static volatile LONG dxc_trace_count;
static int dxc_trace_state; // 0 unread, 1 on, -1 off
static int dxc_trace_step;
static int dxc_trace_last_width;
static int dxc_trace_last_height;
static dxc_dwm_timing_fn dxc_trace_timing;

static int dxc_trace_on(void) {
    if (dxc_trace_state == 0) {
        const char *asked = getenv("DXC_RESIZE_TRACE");
        dxc_trace_state = asked != NULL && strcmp(asked, "1") == 0 ? 1 : -1;
        if (dxc_trace_state > 0) {
            dxc_trace_records = (struct dxc_trace_record *)calloc(DXC_TRACE_CAPACITY, sizeof *dxc_trace_records);
            HMODULE dwm = LoadLibraryW(L"dwmapi.dll");
            if (dwm != NULL) {
                dxc_trace_timing = (dxc_dwm_timing_fn)(void *)GetProcAddress(dwm, "DwmGetCompositionTimingInfo");
            }
            if (dxc_trace_records == NULL) dxc_trace_state = -1;
            LARGE_INTEGER frequency;
            QueryPerformanceFrequency(&frequency);
            DWM_TIMING_INFO timing;
            memset(&timing, 0, sizeof timing);
            timing.cbSize = sizeof timing;
            HRESULT got = dxc_trace_timing != NULL ? dxc_trace_timing(NULL, &timing) : E_FAIL;
            fprintf(stderr,
                    "compose-rust: resize-trace start qpc_frequency=%lld dwm_timing=0x%08lx "
                    "refresh_period_qpc=%llu refresh_rate=%u/%u compose_rate=%u/%u\n",
                    (long long)frequency.QuadPart, (unsigned long)got,
                    (unsigned long long)timing.qpcRefreshPeriod, timing.rateRefresh.uiNumerator,
                    timing.rateRefresh.uiDenominator, timing.rateCompose.uiNumerator,
                    timing.rateCompose.uiDenominator);
        }
    }
    return dxc_trace_state > 0;
}

/** Records one event; safe from any thread. */
static void dxc_trace_event(int kind, int width, int height) {
    if (!dxc_trace_on()) return;
    LONG slot = InterlockedIncrement(&dxc_trace_count) - 1;
    if (slot >= DXC_TRACE_CAPACITY) return;
    struct dxc_trace_record *record = &dxc_trace_records[slot];
    LARGE_INTEGER now, after;
    QueryPerformanceCounter(&now);
    record->qpc = now.QuadPart;
    record->kind = kind;
    record->width = width;
    record->height = height;
    record->step = dxc_trace_step;
    if (dxc_trace_timing != NULL) {
        DWM_TIMING_INFO timing;
        memset(&timing, 0, sizeof timing);
        timing.cbSize = sizeof timing;
        if (SUCCEEDED(dxc_trace_timing(NULL, &timing))) {
            record->frame = timing.cFrame;
            record->compose = timing.qpcCompose;
            record->vblank = timing.qpcVBlank;
            record->refresh = timing.cRefresh;
            record->frame_displayed = timing.cFrameDisplayed;
            record->frame_displayed_qpc = timing.qpcFrameDisplayed;
        }
    }
    QueryPerformanceCounter(&after);
    record->cost = after.QuadPart - now.QuadPart;
}

/** Prints what was recorded and starts again. */
static void dxc_trace_dump(const char *why) {
    if (!dxc_trace_on()) return;
    LONG count = dxc_trace_count;
    if (count > DXC_TRACE_CAPACITY) count = DXC_TRACE_CAPACITY;
    fprintf(stderr, "compose-rust: resize-trace dump %ld events because %s%s\n", (long)count, why,
            dxc_trace_count > DXC_TRACE_CAPACITY ? " (capacity reached, later events lost)" : "");
    for (LONG index = 0; index < count; index++) {
        const struct dxc_trace_record *record = &dxc_trace_records[index];
        fprintf(stderr,
                "compose-rust: resize-trace qpc=%lld kind=%s w=%d h=%d step=%d cframe=%llu "
                "qpc_compose=%llu qpc_vblank=%llu crefresh=%llu cframe_displayed=%llu "
                "qpc_frame_displayed=%llu cost_qpc=%lld\n",
                record->qpc, dxc_trace_names[record->kind], record->width, record->height,
                record->step, record->frame, record->compose, record->vblank, record->refresh,
                record->frame_displayed, record->frame_displayed_qpc, record->cost);
    }
    fflush(stderr);
    InterlockedExchange(&dxc_trace_count, 0);
}

/** The window messages of a size change, recorded as they arrive. */
static void dxc_trace_message(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    switch (message) {
    case WM_NCLBUTTONDOWN:
        dxc_trace_event(DXC_TRACE_NCLBUTTONDOWN, GET_X_LPARAM(lparam), GET_Y_LPARAM(lparam));
        break;
    case WM_ENTERSIZEMOVE:
        dxc_trace_event(DXC_TRACE_ENTERSIZEMOVE, 0, 0);
        break;
    case WM_SIZING: {
        const RECT *rect = (const RECT *)lparam;
        dxc_trace_event(DXC_TRACE_SIZING, rect->right - rect->left, rect->bottom - rect->top);
        break;
    }
    case WM_WINDOWPOSCHANGING: {
        const WINDOWPOS *position = (const WINDOWPOS *)lparam;
        if (!(position->flags & SWP_NOSIZE)) {
            dxc_trace_event(DXC_TRACE_WPCHANGING, position->cx, position->cy);
        }
        break;
    }
    case WM_NCCALCSIZE:
        if (wparam == TRUE) {
            const NCCALCSIZE_PARAMS *params = (const NCCALCSIZE_PARAMS *)lparam;
            dxc_trace_event(DXC_TRACE_NCCALC, params->rgrc[0].right - params->rgrc[0].left,
                            params->rgrc[0].bottom - params->rgrc[0].top);
        }
        break;
    case WM_WINDOWPOSCHANGED: {
        const WINDOWPOS *position = (const WINDOWPOS *)lparam;
        if (!(position->flags & SWP_NOSIZE)) {
            dxc_trace_event(DXC_TRACE_WPCHANGED, position->cx, position->cy);
        }
        break;
    }
    case WM_SIZE: {
        int width = (int)LOWORD(lparam);
        int height = (int)HIWORD(lparam);
        if (width != dxc_trace_last_width || height != dxc_trace_last_height) {
            dxc_trace_step++;
            dxc_trace_last_width = width;
            dxc_trace_last_height = height;
        }
        dxc_trace_event(DXC_TRACE_WM_SIZE, width, height);
        break;
    }
    case WM_PAINT:
        dxc_trace_event(DXC_TRACE_PAINT, 0, 0);
        break;
    case WM_EXITSIZEMOVE: {
        RECT client;
        GetClientRect(window, &client);
        dxc_trace_event(DXC_TRACE_EXITSIZEMOVE, client.right, client.bottom);
        dxc_trace_dump("WM_EXITSIZEMOVE");
        break;
    }
    default:
        break;
    }
}

/**
 * Draws one frame at the size WM_SIZE has just recorded and presents it before WM_SIZE
 * returns, then waits for DWM to take it.
 *
 * WM_SIZE runs inside the SetWindowPos that changed the window, and that call does not
 * return until this does, so the next step of a drag cannot begin before this frame is
 * presented. The window keeps its redirection surface, so until the present lands DWM
 * shows the last frame (a copy-model swapchain, DXGI_SWAP_EFFECT_SEQUENTIAL with
 * DXGI_SCALING_STRETCH, refitted to the client size first, so 1:1), never a gap. The
 * DwmFlush makes the next step wait until DWM has composed this one; without it steps
 * queue faster than DWM shows them and an older size can be on screen with a newer
 * rectangle.
 */
/*
 * Resize frames on the CPU.
 *
 * While the window is changing size its frames are drawn by Skia's raster backend into a
 * DIB section and copied into the window with BitBlt before WM_SIZE returns, the way a
 * GDI application paints. The copy lands in the window's redirection surface on this call
 * stack, so when DWM composes the new size the new picture is already in it; nothing waits
 * on a GPU queue or a present. Once the size stops changing the GPU path takes over again,
 * and its first frame refits the swapchain to the size the window has by then.
 *
 * Opt in from the renderer (dxc_native_set_raster_resize), because the renderer has to
 * draw the frame into the pixels this hands it rather than into the swapchain.
 */
static void dxc_wait_for_gpu(void);
static void dxc_release_buffers(void);
static int32_t dxc_acquire_buffers(int32_t width, int32_t height);
// The child window the swapchain presents to. During a size change it is hidden and the
// top-level window shows its own GDI redirection surface, into which each step's CPU frame is
// copied before WM_SIZE returns; nothing is presented until the size has settled.
// Presents since the current size change began, for the per-step proof line.
// A real drag driven by the test hook is running; see dxc_native_debug_resize.
static int dxc_real_drag_active;
// A drag driven from another process (DXC_REAL_DRAG=external), and the size changes it made.
static int dxc_external_drag;
static int dxc_external_drag_steps;
static int dxc_capture_steps;
static int dxc_capture_failures;
static void dxc_capture_step(HWND window);
// When the last CPU frame was copied into the window.
static int dxc_raster_enabled;
#define DXC_WM_GPU_RETURN (WM_APP + 2)

void dxc_native_set_raster_resize(int32_t enabled) {
    dxc_raster_enabled = enabled != 0;
    fprintf(stderr, "compose-rust: resize-mode: resize frames %s\n",
            dxc_raster_enabled ? "on the CPU (Skia raster, GDI blit)" : "on the GPU");
}

/** 1 while resize frames are drawn on the CPU, so the renderer asks for raster pixels. */
int32_t dxc_native_frame_mode(void) {
    return dxc_cpu_mode ? 1 : 0;
}

static void dxc_draw_one_frame(void);

/**
 * Back to the GPU once the size has settled: the hidden child is fitted to the client
 * area, the swapchain is refitted and a frame at that size is drawn and presented to it
 * while it is still hidden, and only then is it shown, so it appears with that frame.
 */
static void dxc_return_to_gpu(HWND window, int32_t width, int32_t height) {
    if (dxc_surface != NULL) {
        SetWindowPos(dxc_surface, NULL, 0, 0, width, height, SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOMOVE);
    }
    dxc_resize_note(&dxc_sizing, width, height);
    dxc_draw_one_frame();
    if (dxc_surface != NULL) {
        ShowWindow(dxc_surface, SW_SHOWNA);
    }
    if (dxc_on_comp) {
        fprintf(stderr, "compose-rust: resize-mode: composition frames back on the GPU at %dx%d (at %.1f ms)\n",
                (int)width, (int)height, dxc_now_ms());
    } else {
        fprintf(stderr, "compose-rust: resize-mode: surface shown at %dx%d after its frame was presented (at %.1f ms)\n",
                (int)width, (int)height, dxc_now_ms());
    }
    (void)window;
}

static void dxc_switch_frames(int cpu, int32_t width, int32_t height, const char *reason) {
    if (dxc_cpu_mode == cpu) {
        return;
    }
    dxc_cpu_mode = cpu;
    dxc_mode_switches++;
    if (cpu) dxc_presents_in_change = 0;
    fprintf(stderr, "compose-rust: resize-mode: frames %s -> %s at %dx%d because %s (switch %d)\n",
            cpu ? "gpu" : "cpu", cpu ? "cpu" : "gpu", (int)width, (int)height, reason,
            (int)dxc_mode_switches);
}

/**
 * Makes sure the DIB holds width x height. Made once at the size of the monitor the
 * window is on and reused; it grows only for a window larger than that monitor.
 */
static int dxc_ensure_dib(int32_t width, int32_t height) {
    if (dxc_dib != NULL && width <= dxc_dib_width && height <= dxc_dib_height) {
        return 1;
    }
    int32_t dib_width = width;
    int32_t dib_height = height;
    MONITORINFO monitor;
    memset(&monitor, 0, sizeof monitor);
    monitor.cbSize = sizeof monitor;
    if (dxc_window != NULL &&
        GetMonitorInfoW(MonitorFromWindow(dxc_window, MONITOR_DEFAULTTONEAREST), &monitor)) {
        int32_t monitor_width = (int32_t)(monitor.rcMonitor.right - monitor.rcMonitor.left);
        int32_t monitor_height = (int32_t)(monitor.rcMonitor.bottom - monitor.rcMonitor.top);
        if (monitor_width > dib_width) dib_width = monitor_width;
        if (monitor_height > dib_height) dib_height = monitor_height;
    }
    if (dxc_dib_dc == NULL) {
        dxc_dib_dc = CreateCompatibleDC(NULL);
        if (dxc_dib_dc == NULL) {
            return 0;
        }
    }
    if (dxc_dib != NULL) {
        SelectObject(dxc_dib_dc, dxc_dib_previous);
        DeleteObject(dxc_dib);
        dxc_dib = NULL;
        dxc_dib_bits = NULL;
    }
    BITMAPINFO info;
    memset(&info, 0, sizeof info);
    info.bmiHeader.biSize = sizeof info.bmiHeader;
    info.bmiHeader.biWidth = dib_width;
    // Negative: top-down rows, the order Skia writes them in.
    info.bmiHeader.biHeight = -dib_height;
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    info.bmiHeader.biCompression = BI_RGB;
    dxc_dib = CreateDIBSection(dxc_dib_dc, &info, DIB_RGB_COLORS, &dxc_dib_bits, NULL, 0);
    if (dxc_dib == NULL || dxc_dib_bits == NULL) {
        dxc_dib = NULL;
        return 0;
    }
    dxc_dib_previous = SelectObject(dxc_dib_dc, dxc_dib);
    dxc_dib_width = dib_width;
    dxc_dib_height = dib_height;
    fprintf(stderr, "compose-rust: resize-mode: raster bitmap -> %dx%d because a frame needed room\n",
            (int)dib_width, (int)dib_height);
    return 1;
}

/**
 * The pixels the CPU frame draws into: the top left width x height of the DIB, 32-bit
 * BGRA, top-down, row_bytes apart. Zero on success.
 */
int32_t dxc_native_raster_begin(void **pixels, int32_t *row_bytes, int32_t *width, int32_t *height) {
    int32_t w = dxc_resize_target_width > 0 ? dxc_resize_target_width : dxc_sizing.fitted_width;
    int32_t h = dxc_resize_target_height > 0 ? dxc_resize_target_height : dxc_sizing.fitted_height;
    if (w <= 0 || h <= 0 || !dxc_ensure_dib(w, h)) {
        return 1;
    }
    GdiFlush();
    *pixels = dxc_dib_bits;
    *row_bytes = dxc_dib_width * 4;
    *width = w;
    *height = h;
    dxc_raster_width = w;
    dxc_raster_height = h;
    QueryPerformanceCounter(&dxc_step_mark);
    return 0;
}

static void dxc_raster_copy(void);

/**
 * Copies the CPU frame into the window, 1:1, before the message that asked for it returns.
 *
 * Not while the frame is drawn ahead of a size change (dxc_predraw_resize): the window is
 * still the old size then, so the frame waits in the bitmap and WM_SIZE copies it.
 */
void dxc_native_raster_end(void) {
    if (dxc_window == NULL || dxc_dib_dc == NULL) {
        return;
    }
    dxc_step_draw_ms = dxc_elapsed_ms(dxc_step_mark);
    if (dxc_predrawing) {
        dxc_predrawn_set(&dxc_predrawn, dxc_raster_width, dxc_raster_height);
        return;
    }
    dxc_raster_copy();
}

static void dxc_comp_raster_copy(void);

/** The copy of the frame in the bitmap into the window, and the child hidden after it. */
static void dxc_raster_copy(void) {
    // The composition route has no GDI surface to copy into: the frame goes into the
    // composition swapchain's back buffer and is presented and committed instead.
    if (dxc_on_comp) {
        dxc_comp_raster_copy();
        // The native views move with the commit of the frame they were laid out in.
        dxc_nv_apply(dxc_w);
        return;
    }
    // The native views are moved first and the copy is cut around where they now are: the
    // child windows draw into the same GDI surface as this window, and a copy over them would
    // paint the scene over a view until it painted again. Nothing without native views.
    dxc_nv_apply(dxc_w);
    LARGE_INTEGER copy_started;
    QueryPerformanceCounter(&copy_started);
    // Into the top-level window, through GDI, before the message that asked for it returns.
    // The swapchain's child window is hidden for the length of the size change, so what DWM
    // composes for this window is its GDI redirection surface, which this copy fills.
    dxc_last_blit = 0;
    HDC window_dc = GetDC(dxc_window);
    if (window_dc != NULL) {
        dxc_nv_exclude(dxc_w, window_dc);
        dxc_last_blit = BitBlt(window_dc, 0, 0, dxc_raster_width, dxc_raster_height, dxc_dib_dc, 0, 0, SRCCOPY) != 0;
        GdiFlush();
        ReleaseDC(dxc_window, window_dc);
    }
    if (dxc_trace_state > 0) dxc_trace_event(DXC_TRACE_BLIT, dxc_raster_width, dxc_raster_height);
    dxc_last_validated = ValidateRect(dxc_window, NULL) != 0;
    if (dxc_trace_state > 0) dxc_trace_event(DXC_TRACE_VALID, dxc_raster_width, dxc_raster_height);
    QueryPerformanceCounter(&dxc_blit_time);
    // Hidden after the copy, in the same step, so the redirection surface it reveals already
    // holds this frame.
    if (dxc_surface != NULL && IsWindowVisible(dxc_surface)) {
        ShowWindow(dxc_surface, SW_HIDE);
        dxc_last_validated = ValidateRect(dxc_window, NULL) != 0 && dxc_last_validated;
    }
    int presented = dxc_last_blit;
    dxc_step_copy_ms = dxc_elapsed_ms(copy_started);
    if (presented) {
        dxc_presented_width = dxc_raster_width;
        dxc_presented_height = dxc_raster_height;
    } else {
        fprintf(stderr, "compose-rust: resize-mode: skipped frame %dx%d because the blit failed\n",
                (int)dxc_raster_width, (int)dxc_raster_height);
    }
}

// When the frame drawn ahead of a size is committed, on the composition route
// (DXC_DCOMP_COMMIT). order, the default: the frame of the size both rectangles hold (the
// smaller width and the smaller height of the old and the new) is committed in WM_NCCALCSIZE
// and waited for (DXC_DCOMP_SHRINK_WAIT) before the window takes the new rectangle, and the
// frame of the new size, where it is another, is committed in WM_SIZE, after it. ahead: the
// new size's frame committed in WM_NCCALCSIZE after one wait for a composition, without
// waiting for it after. wmsize: committed in WM_SIZE. For comparing the three.
//
// Why order: a commit reaches the screen a composition after the rectangle about as often as
// not, never reliably with it. A frame smaller than the window it is shown in leaves a
// transparent margin inside the window's border for that composition; a frame larger than the
// window is cut by it, which is the picture of another size. The frame of the size both hold
// fits inside the old rectangle and the new one, so whichever composition shows it, it is
// never cut. A size that only grows needs nothing first (that size is the old one, already
// shown); one that only shrinks has its new frame first; one that grows a side and shrinks
// the other (a snap to a half, and back) draws one more frame, of the size both hold.
enum { DXC_COMP_COMMIT_ORDER, DXC_COMP_COMMIT_AHEAD, DXC_COMP_COMMIT_WM_SIZE };
static const char *const dxc_comp_commit_names[] = {"order", "ahead", "wmsize"};
static int dxc_comp_commit_mode;
// How a frame committed ahead of a smaller rectangle is waited for (DXC_DCOMP_SHRINK_WAIT):
// commit, the default: until DWM has taken the commit (WaitForCommitCompletion), so it is
// composed no later than the rectangle sent after it; flush: that and one composition
// (DwmFlush) as well, so it is on screen first; none: no wait. hold: a drag step that shrinks
// waits for nothing; the window is held instead at the size of the last frame DWM has taken,
// and follows it a step behind (dxc_comp_hold_step); outside a drag it is commit. For
// comparing them.
enum { DXC_COMP_WAIT_COMMIT, DXC_COMP_WAIT_FLUSH, DXC_COMP_WAIT_NONE, DXC_COMP_WAIT_HOLD };
static const char *const dxc_comp_wait_names[] = {"commit", "flush", "none", "hold"};
static int dxc_comp_wait_mode;
// For DXC_DCOMP_SHRINK_WAIT=hold, during a drag: the size of the last frame DWM has taken (the
// window may shrink to it), the frame committed since and not yet known to be taken, the edge
// being dragged (WM_SIZING), and whether the size change in flight is one held here, whose
// WM_NCCALCSIZE and WM_SIZE draw nothing (the frame for it is already committed).
static int32_t dxc_comp_confirmed_width, dxc_comp_confirmed_height;
static int32_t dxc_comp_held_width, dxc_comp_held_height;
static WPARAM dxc_comp_sizing_edge;
// The edge being dragged (WM_SIZING), on either route, for DXC_REPORT_LATENCY's step lines.
static WPARAM dxc_drag_sizing_edge;
static int dxc_comp_hold_active;
#define DXC_TIMER_COMP_HOLD 0x4443
// For the current drag: steps whose frame was shown before the window took the size, and where
// a step's time went, in ms (the upload into the surface, the wait for the commit, the
// composition waited for after it, and the drag rule's wait before the commit).
static int dxc_comp_frame_first_steps;
static int dxc_comp_timed_steps;
static double dxc_comp_upload_sum, dxc_comp_upload_max;
static double dxc_comp_wait_sum, dxc_comp_wait_max;
static double dxc_comp_flush_sum, dxc_comp_flush_max;
static double dxc_comp_rule_sum, dxc_comp_rule_max;

// DXC_DCOMP_DRAG_RULE=1: the composition route waits for a composition before each drag step
// where the drag rule says so (dxc_drag_flush, builds below 22000), as the GDI route does. Off
// by default: that wait put the GDI route's copy at the start of a composition interval, and on
// this route the order of the commits (dxc_comp_commit_mode) already decides which frame is
// shown with which rectangle. Run 44659612997 measured it at 24 ms a step on windows-2022, and
// windows-2025, which never takes it, was as clean. Kept for comparing the two.
static int dxc_comp_drag_rule;

/** The edge a WM_SIZING names, for the step lines. */
static const char *dxc_sizing_edge_name(WPARAM edge) {
    switch (edge) {
    case WMSZ_LEFT: return "left";
    case WMSZ_RIGHT: return "right";
    case WMSZ_TOP: return "top";
    case WMSZ_TOPLEFT: return "top-left";
    case WMSZ_TOPRIGHT: return "top-right";
    case WMSZ_BOTTOM: return "bottom";
    case WMSZ_BOTTOMLEFT: return "bottom-left";
    case WMSZ_BOTTOMRIGHT: return "bottom-right";
    default: return "none";
    }
}

static void dxc_comp_time(double *sum, double *max, double ms) {
    *sum += ms;
    if (ms > *max) *max = ms;
}

// Where the upload of the current drag's frames went, in ms, summed over its uploads:
// BeginDraw, the write (UpdateSubresource or the copy, and the context's Flush), EndDraw.
static int dxc_comp_uploads;
static double dxc_comp_begin_sum, dxc_comp_write_sum, dxc_comp_end_sum;

static void dxc_comp_part(LARGE_INTEGER *mark, double *sum) {
    LARGE_INTEGER now;
    QueryPerformanceCounter(&now);
    if (dxc_sizing.dragging) {
        static LARGE_INTEGER frequency;
        if (frequency.QuadPart == 0) QueryPerformanceFrequency(&frequency);
        *sum += (double)(now.QuadPart - mark->QuadPart) * 1000.0 / (double)frequency.QuadPart;
    }
    *mark = now;
}

/**
 * On the composition route: writes the CPU frame in the bitmap into the surface at width x
 * height, which the visual's clip takes at the next commit. No commit. One when it was written.
 */
static int dxc_comp_upload_bitmap(int32_t width, int32_t height) {
    if (dxc_comp == NULL || dxc_comp_context == NULL || dxc_dib_bits == NULL || width > dxc_dib_width ||
        height > dxc_dib_height) {
        return 0;
    }
    LARGE_INTEGER mark;
    QueryPerformanceCounter(&mark);
    void *texture = NULL;
    int32_t x = 0, y = 0;
    if (dxc_dcomp_begin_draw(dxc_comp, width, height, &texture, &x, &y) != 0) {
        return 0;
    }
    dxc_comp_part(&mark, &dxc_comp_begin_sum);
    // The bitmap is BGRA, top-down, the surface's format; the box is where in the surface's
    // texture this update goes.
    D3D11_BOX drawn = {(UINT)x, (UINT)y, 0, (UINT)(x + width), (UINT)(y + height), 1};
    ID3D11DeviceContext_UpdateSubresource(dxc_comp_context, (ID3D11Resource *)texture, 0, &drawn, dxc_dib_bits,
                                          (UINT)dxc_dib_width * 4, 0);
    ID3D11DeviceContext_Flush(dxc_comp_context);
    dxc_comp_part(&mark, &dxc_comp_write_sum);
    int ended = dxc_dcomp_end_draw(dxc_comp, texture) == 0;
    dxc_comp_part(&mark, &dxc_comp_end_sum);
    if (dxc_sizing.dragging) dxc_comp_uploads++;
    if (!ended) {
        return 0;
    }
    dxc_comp_surface_width = width;
    dxc_comp_surface_height = height;
    dxc_resize_fitted(&dxc_sizing, width, height);
    return 1;
}

/**
 * The draw texture as the native device sees it, opened again whenever the draw texture was
 * made again (it only grows). NULL where it cannot be shared.
 */
static ID3D11Texture2D *dxc_comp_shared_texture(void) {
    if (dxc_buffers[0] == NULL) {
        return NULL;
    }
    if (dxc_comp_shared != NULL && dxc_comp_shared_from == dxc_buffers[0]) {
        return dxc_comp_shared;
    }
    if (dxc_comp_shared != NULL) {
        ID3D11Texture2D_Release(dxc_comp_shared);
        dxc_comp_shared = NULL;
    }
    dxc_comp_shared_from = NULL;
    HANDLE handle = NULL;
    ID3D11Device1 *device1 = NULL;
    HRESULT shared = ID3D12Device_CreateSharedHandle(dxc_device, (ID3D12DeviceChild *)dxc_buffers[0], NULL,
                                                     GENERIC_ALL, NULL, &handle);
    HRESULT opened = E_FAIL;
    if (SUCCEEDED(shared) &&
        SUCCEEDED(ID3D11Device_QueryInterface(dxc_comp_device, &IID_ID3D11Device1, (void **)&device1))) {
        opened = ID3D11Device1_OpenSharedResource1(device1, handle, &IID_ID3D11Texture2D, (void **)&dxc_comp_shared);
        ID3D11Device1_Release(device1);
    }
    if (handle != NULL) CloseHandle(handle);
    if (FAILED(opened)) {
        fprintf(stderr, "compose-rust: dcomp: sharing the draw texture failed (CreateSharedHandle 0x%08lx, "
                        "OpenSharedResource1 0x%08lx)\n", (unsigned long)shared, (unsigned long)opened);
        dxc_comp_shared = NULL;
        return NULL;
    }
    dxc_comp_shared_from = dxc_buffers[0];
    return dxc_comp_shared;
}

/**
 * On the composition route: copies the GPU frame (the top left width x height of the draw
 * texture) into the surface. No commit. One when it was copied.
 *
 * Natively, the Direct3D 12 queue signals the shared fence after the frame, the native
 * context waits for it on the GPU, copies, and signals back, and the queue waits for that
 * before the next frame draws into the texture: nothing waits on the CPU.
 */
static int dxc_comp_upload_texture(int32_t width, int32_t height) {
    if (dxc_comp == NULL || dxc_comp_context == NULL) {
        return 0;
    }
    ID3D11Resource *source = NULL;
    if (dxc_comp_native) {
        source = (ID3D11Resource *)dxc_comp_shared_texture();
    } else {
        source = dxc_wrapped;
    }
    if (source == NULL) {
        return 0;
    }
    LARGE_INTEGER mark;
    QueryPerformanceCounter(&mark);
    void *texture = NULL;
    int32_t x = 0, y = 0;
    if (dxc_dcomp_begin_draw(dxc_comp, width, height, &texture, &x, &y) != 0) {
        return 0;
    }
    dxc_comp_part(&mark, &dxc_comp_begin_sum);
    D3D11_BOX drawn = {0, 0, 0, (UINT)width, (UINT)height, 1};
    if (dxc_comp_native) {
        UINT64 drawn_mark = ++dxc_comp_fence_value;
        ID3D12CommandQueue_Signal(dxc_queue, dxc_comp_fence12, drawn_mark);
        ID3D11DeviceContext4_Wait(dxc_comp_context4, dxc_comp_fence11, drawn_mark);
        ID3D11DeviceContext_CopySubresourceRegion(dxc_comp_context, (ID3D11Resource *)texture, 0, (UINT)x, (UINT)y, 0,
                                                  source, 0, &drawn);
        UINT64 copied_mark = ++dxc_comp_fence_value;
        ID3D11DeviceContext4_Signal(dxc_comp_context4, dxc_comp_fence11, copied_mark);
        ID3D11DeviceContext_Flush(dxc_comp_context);
        ID3D12CommandQueue_Wait(dxc_queue, dxc_comp_fence12, copied_mark);
    } else {
        ID3D11On12Device_AcquireWrappedResources(dxc_on12, &dxc_wrapped, 1);
        ID3D11DeviceContext_CopySubresourceRegion(dxc_comp_context, (ID3D11Resource *)texture, 0, (UINT)x, (UINT)y, 0,
                                                  source, 0, &drawn);
        ID3D11On12Device_ReleaseWrappedResources(dxc_on12, &dxc_wrapped, 1);
        ID3D11DeviceContext_Flush(dxc_comp_context);
    }
    dxc_comp_part(&mark, &dxc_comp_write_sum);
    int ended = dxc_dcomp_end_draw(dxc_comp, texture) == 0;
    dxc_comp_part(&mark, &dxc_comp_end_sum);
    if (!ended) {
        return 0;
    }
    dxc_comp_surface_width = width;
    dxc_comp_surface_height = height;
    return 1;
}

/**
 * Makes the native device the surface is written with, on the window's adapter, and the fence
 * shared with the Direct3D 12 queue. One when all of it was made; otherwise nothing is kept
 * and the surface is written with the D3D11On12 device.
 */
static int dxc_comp_make_native(IDXGIAdapter1 *adapter, ID3D12Device *device) {
    HMODULE library = LoadLibraryW(L"d3d11.dll");
    PFN_D3D11_CREATE_DEVICE create =
        library == NULL ? NULL : (PFN_D3D11_CREATE_DEVICE)(void *)GetProcAddress(library, "D3D11CreateDevice");
    D3D_FEATURE_LEVEL levels[2] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0};
    HRESULT made = create == NULL ? E_NOINTERFACE
                                  : create((IDXGIAdapter *)adapter, D3D_DRIVER_TYPE_UNKNOWN, NULL,
                                           D3D11_CREATE_DEVICE_BGRA_SUPPORT, levels, 2, D3D11_SDK_VERSION,
                                           &dxc_comp_device, NULL, &dxc_comp_context);
    HRESULT context4 = SUCCEEDED(made) ? ID3D11DeviceContext_QueryInterface(dxc_comp_context, &IID_ID3D11DeviceContext4,
                                                                           (void **)&dxc_comp_context4)
                                       : E_ABORT;
    HRESULT fence12 = SUCCEEDED(context4) ? ID3D12Device_CreateFence(device, 0, D3D12_FENCE_FLAG_SHARED,
                                                                      &IID_ID3D12Fence, (void **)&dxc_comp_fence12)
                                          : E_ABORT;
    HANDLE handle = NULL;
    HRESULT shared = SUCCEEDED(fence12) ? ID3D12Device_CreateSharedHandle(device, (ID3D12DeviceChild *)dxc_comp_fence12,
                                                                           NULL, GENERIC_ALL, NULL, &handle)
                                        : E_ABORT;
    HRESULT fence11 = E_ABORT;
    if (SUCCEEDED(shared)) {
        ID3D11Device5 *device5 = NULL;
        fence11 = ID3D11Device_QueryInterface(dxc_comp_device, &IID_ID3D11Device5, (void **)&device5);
        if (SUCCEEDED(fence11)) {
            fence11 = ID3D11Device5_OpenSharedFence(device5, handle, &IID_ID3D11Fence, (void **)&dxc_comp_fence11);
            ID3D11Device5_Release(device5);
        }
        CloseHandle(handle);
    }
    fprintf(stderr,
            "compose-rust: dcomp: native device: D3D11CreateDevice 0x%08lx, DeviceContext4 0x%08lx, shared fence "
            "0x%08lx/0x%08lx/0x%08lx\n",
            (unsigned long)made, (unsigned long)context4, (unsigned long)fence12, (unsigned long)shared,
            (unsigned long)fence11);
    if (FAILED(fence11)) {
        if (dxc_comp_fence11 != NULL) { ID3D11Fence_Release(dxc_comp_fence11); dxc_comp_fence11 = NULL; }
        if (dxc_comp_fence12 != NULL) { ID3D12Fence_Release(dxc_comp_fence12); dxc_comp_fence12 = NULL; }
        if (dxc_comp_context4 != NULL) { ID3D11DeviceContext4_Release(dxc_comp_context4); dxc_comp_context4 = NULL; }
        if (dxc_comp_context != NULL) { ID3D11DeviceContext_Release(dxc_comp_context); dxc_comp_context = NULL; }
        if (dxc_comp_device != NULL) { ID3D11Device_Release(dxc_comp_device); dxc_comp_device = NULL; }
        return 0;
    }
    return 1;
}

/** Lets go of the native device and what is shared with it. */
static void dxc_comp_release_native(void) {
    if (dxc_comp_shared != NULL) { ID3D11Texture2D_Release(dxc_comp_shared); dxc_comp_shared = NULL; }
    dxc_comp_shared_from = NULL;
    if (dxc_comp_fence11 != NULL) { ID3D11Fence_Release(dxc_comp_fence11); dxc_comp_fence11 = NULL; }
    if (dxc_comp_fence12 != NULL) { ID3D12Fence_Release(dxc_comp_fence12); dxc_comp_fence12 = NULL; }
    if (dxc_comp_context4 != NULL) { ID3D11DeviceContext4_Release(dxc_comp_context4); dxc_comp_context4 = NULL; }
    if (dxc_comp_native) {
        if (dxc_comp_context != NULL) ID3D11DeviceContext_Release(dxc_comp_context);
        if (dxc_comp_device != NULL) ID3D11Device_Release(dxc_comp_device);
    }
    dxc_comp_context = NULL;
    dxc_comp_device = NULL;
    dxc_comp_native = 0;
}

/**
 * On the composition route: commits the frame written into the surface, with its size and
 * clip, as one change. A failed commit makes the device again once (dxc_dcomp_commit), which
 * leaves an empty surface, so a frame is asked for; a second failure is said once. One when
 * it was committed.
 */
static int dxc_comp_show(const char *what) {
    if (dxc_comp == NULL) {
        return 0;
    }
    dxc_presents_in_change++;
    int committed = dxc_dcomp_commit(dxc_comp);
    if (!dxc_comp_first_show_logged) {
        dxc_comp_first_show_logged = 1;
        fprintf(stderr, "compose-rust: dcomp: first frame shown (%s): commit %d, surface %dx%d, fitted %dx%d\n",
                what, committed, (int)dxc_comp_surface_width, (int)dxc_comp_surface_height,
                (int)dxc_sizing.fitted_width, (int)dxc_sizing.fitted_height);
    }
    if (committed < 0) {
        if (!dxc_comp_commit_failed) {
            dxc_comp_commit_failed = 1;
            fprintf(stderr, "compose-rust: dcomp: commit failed, frames may not reach the window\n");
        }
        return 0;
    }
    if (committed == 1) {
        dxc_comp_presented = 0;
        if (dxc_window != NULL) InvalidateRect(dxc_window, NULL, FALSE);
        return 0;
    }
    dxc_comp_presented = 1;
    return 1;
}

/**
 * Presents the CPU frame drawn ahead of a size change (dxc_predraw_resize) through the
 * swapchain, with its child left visible: the swapchain is refitted, the bitmap is copied
 * into its buffer and presented. One when it was presented.
 *
 * Outside a drag the child is not hidden for the CPU frame. A hidden child cannot be given
 * a new picture (a present to it does not reach DWM), so when it was shown again after the
 * size change DWM composed it once with the picture it held when it was hidden, at its old
 * size, cut by the new one and transparent beyond, before the present that followed landed
 * (compose-rust#684). A child that stays visible takes its new picture the way it does when
 * every resize frame is drawn on the GPU, which never showed that frame.
 */
/**
 * Refits the swapchain to width x height where it is another size and copies the CPU frame
 * in the bitmap into its buffer, without presenting it. One when it was copied.
 */
static int dxc_stage_bitmap(int32_t width, int32_t height) {
    if (dxc_swapchain == NULL || dxc_d3d11_context == NULL || dxc_dib_bits == NULL ||
        width > dxc_dib_width || height > dxc_dib_height) {
        return 0;
    }
    if (dxc_on_comp) {
        // Into the composition surface instead of a swapchain buffer.
        return dxc_comp_upload_bitmap(width, height);
    }
    if (dxc_sizing.fitted_width != width || dxc_sizing.fitted_height != height) {
        dxc_wait_for_gpu();
        dxc_release_buffers();
        HRESULT resized = IDXGISwapChain1_ResizeBuffers(dxc_swapchain, DXC_BUFFER_COUNT, (UINT)width,
                                                        (UINT)height, DXC_SWAPCHAIN_FORMAT, dxc_swapchain_flags);
        if (FAILED(resized)) {
            dxc_log_dxgi_failure("ResizeBuffers", resized);
            return 0;
        }
        dxc_resize_fitted(&dxc_sizing, width, height);
    }
    ID3D11Resource *back = NULL;
    HRESULT got = IDXGISwapChain1_GetBuffer(dxc_swapchain, 0, &IID_ID3D11Resource, (void **)&back);
    if (FAILED(got)) {
        dxc_log_dxgi_failure("GetBuffer", got);
        return 0;
    }
    // The bitmap is BGRA, top-down, the swapchain's format.
    D3D11_BOX drawn = {0, 0, 0, (UINT)width, (UINT)height, 1};
    ID3D11DeviceContext_UpdateSubresource(dxc_d3d11_context, back, 0, &drawn, dxc_dib_bits,
                                          (UINT)dxc_dib_width * 4, 0);
    ID3D11DeviceContext_Flush(dxc_d3d11_context);
    ID3D11Resource_Release(back);
    return 1;
}

/** Presents what the swapchain's buffer holds. One when it was presented. */
static int dxc_present_staged(void) {
    dxc_presents_in_change++;
    HRESULT shown = IDXGISwapChain1_Present(dxc_swapchain, 0, 0);
    if (FAILED(shown)) {
        dxc_log_dxgi_failure("Present", shown);
        return 0;
    }
    return 1;
}

static int dxc_bitmap_to_swapchain(int32_t width, int32_t height) {
    return dxc_stage_bitmap(width, height) && dxc_present_staged();
}

/**
 * The composition route's sink for a CPU frame, in place of the GDI copy: the frame in the
 * bitmap is written into the surface and committed, before the message that asked for it
 * returns. A frame drawn ahead in WM_NCCALCSIZE is already in the surface, and already
 * committed there unless DXC_DCOMP_COMMIT=wmsize; only what is left is done. One when it was
 * shown.
 */
static int dxc_comp_present_bitmap(int32_t width, int32_t height) {
    int staged = dxc_comp_staged_width == width && dxc_comp_staged_height == height &&
                 dxc_sizing.fitted_width == width && dxc_sizing.fitted_height == height;
    int committed = staged && dxc_comp_staged_committed;
    dxc_comp_staged_width = 0;
    dxc_comp_staged_height = 0;
    dxc_comp_staged_committed = 0;
    if (committed) {
        return 1;
    }
    if (!(staged || dxc_comp_upload_bitmap(width, height))) {
        return 0;
    }
    return dxc_comp_show("a CPU frame");
}

/**
 * On the composition route, in WM_NCCALCSIZE: the frame just drawn for the size the window is
 * about to take goes into the surface, then, after the wait for a composition where the caller
 * takes one, is committed, unless DXC_DCOMP_COMMIT=wmsize leaves the commit to WM_SIZE. The
 * window's new rectangle is applied as this message returns, so the commit and the rectangle
 * reach DWM microseconds apart, at the start of a composition interval where there was a wait,
 * and land in the same composition. Committed in WM_SIZE instead, the rectangle went first and
 * a composition between the two showed the old frame in the new rectangle.
 */
static int dxc_comp_stage_ahead(int32_t width, int32_t height) {
    dxc_comp_staged_width = 0;
    dxc_comp_staged_height = 0;
    dxc_comp_staged_committed = 0;
    LARGE_INTEGER started;
    QueryPerformanceCounter(&started);
    int written = dxc_comp_upload_bitmap(width, height);
    if (dxc_sizing.dragging) dxc_comp_time(&dxc_comp_upload_sum, &dxc_comp_upload_max, dxc_elapsed_ms(started));
    if (!written) {
        return 0;
    }
    dxc_comp_staged_width = width;
    dxc_comp_staged_height = height;
    return 1;
}

/**
 * Waits for a frame committed ahead of a smaller rectangle, as DXC_DCOMP_SHRINK_WAIT says, and
 * accounts for it.
 */
static void dxc_comp_wait_shown(int32_t width, int32_t height, LARGE_INTEGER started) {
    if (dxc_comp_wait_mode != DXC_COMP_WAIT_NONE) dxc_dcomp_wait(dxc_comp);
    double taken_ms = dxc_elapsed_ms(started);
    int flushed = dxc_comp_wait_mode == DXC_COMP_WAIT_FLUSH ? dxc_dwm_flush() : 0;
    double flush_ms = dxc_elapsed_ms(started) - taken_ms;
    if (dxc_sizing.dragging) {
        dxc_comp_frame_first_steps++;
        dxc_comp_time(&dxc_comp_wait_sum, &dxc_comp_wait_max, taken_ms);
        dxc_comp_time(&dxc_comp_flush_sum, &dxc_comp_flush_max, flush_ms);
    }
    if (dxc_report_latency) {
        fprintf(stderr,
                "compose-rust: dcomp: frame %dx%d shown before the window took its size: commit and wait (%s) "
                "%.2f ms, %s %.2f ms\n",
                (int)width, (int)height, dxc_comp_wait_names[dxc_comp_wait_mode], taken_ms,
                flushed ? "DwmFlush" : "no DwmFlush", flush_ms);
    }
}

/** Commits the frame staged ahead in WM_NCCALCSIZE, where the order says it goes first. */
static void dxc_comp_commit_ahead(int pure_shrink) {
    if (dxc_comp_staged_width <= 0 || dxc_comp_commit_mode == DXC_COMP_COMMIT_WM_SIZE ||
        (dxc_comp_commit_mode == DXC_COMP_COMMIT_ORDER && !pure_shrink)) {
        return;
    }
    LARGE_INTEGER started;
    QueryPerformanceCounter(&started);
    dxc_comp_staged_committed = dxc_comp_show("a CPU frame drawn ahead");
    if (dxc_comp_commit_mode == DXC_COMP_COMMIT_ORDER && dxc_comp_staged_committed) {
        dxc_comp_wait_shown(dxc_comp_staged_width, dxc_comp_staged_height, started);
    }
}

/**
 * For a size that grows one side and shrinks the other: draws, writes and commits the frame
 * of the size both rectangles hold (the smaller of each side), and waits for it, before the
 * frame of the new size is drawn. The new size's frame is then committed in WM_SIZE, as for a
 * size that grows.
 */
static void dxc_comp_show_both_hold(int32_t width, int32_t height, int32_t now_width, int32_t now_height) {
    int32_t both_width = width < now_width ? width : now_width;
    int32_t both_height = height < now_height ? height : now_height;
    dxc_predrawn_set(&dxc_predrawn, 0, 0);
    dxc_resize_target_width = both_width;
    dxc_resize_target_height = both_height;
    dxc_resizing = 1;
    dxc_predrawing = 1;
    dxc_draw_one_frame();
    dxc_predrawing = 0;
    dxc_resizing = 0;
    if (dxc_predrawn.width != both_width || dxc_predrawn.height != both_height) {
        return;
    }
    LARGE_INTEGER started;
    QueryPerformanceCounter(&started);
    if (!dxc_comp_upload_bitmap(both_width, both_height)) {
        return;
    }
    if (dxc_sizing.dragging) dxc_comp_time(&dxc_comp_upload_sum, &dxc_comp_upload_max, dxc_elapsed_ms(started));
    if (dxc_comp_show("the frame of the size both rectangles hold")) {
        dxc_comp_wait_shown(both_width, both_height, started);
    }
}

static void dxc_comp_raster_copy(void) {
    LARGE_INTEGER copy_started;
    QueryPerformanceCounter(&copy_started);
    dxc_last_blit = dxc_comp_present_bitmap(dxc_raster_width, dxc_raster_height);
    if (dxc_trace_state > 0) dxc_trace_event(DXC_TRACE_BLIT, dxc_raster_width, dxc_raster_height);
    dxc_last_validated = ValidateRect(dxc_window, NULL) != 0;
    if (dxc_trace_state > 0) dxc_trace_event(DXC_TRACE_VALID, dxc_raster_width, dxc_raster_height);
    QueryPerformanceCounter(&dxc_blit_time);
    dxc_step_copy_ms = dxc_elapsed_ms(copy_started);
    if (dxc_last_blit) {
        dxc_presented_width = dxc_raster_width;
        dxc_presented_height = dxc_raster_height;
    } else {
        fprintf(stderr, "compose-rust: resize-mode: skipped frame %dx%d because the composition present failed\n",
                (int)dxc_raster_width, (int)dxc_raster_height);
    }
}

static int dxc_raster_present(int32_t width, int32_t height) {
    if (dxc_on_comp) {
        // A step of a drag goes through dxc_raster_copy, whose composition sink is the same
        // present; this is the size given outside a drag (restore, maximise, snap, a change
        // of DPI). DXC_RESIZE_SWAPCHAIN=0 names a GDI copy and has no meaning here.
        if (dxc_sizing.dragging) {
            return 0;
        }
        LARGE_INTEGER copy_started;
        QueryPerformanceCounter(&copy_started);
        if (!dxc_comp_present_bitmap(width, height)) {
            return 0;
        }
        dxc_last_blit = 1;
        dxc_last_validated = ValidateRect(dxc_window, NULL) != 0;
        dxc_presented_width = width;
        dxc_presented_height = height;
        dxc_step_copy_ms = dxc_elapsed_ms(copy_started);
        dxc_switch_frames(0, width, height, "the frame drawn ahead was committed through the composition surface");
        return 1;
    }
    if (dxc_raster_present_off || dxc_sizing.dragging || dxc_surface == NULL || !IsWindowVisible(dxc_surface)) {
        return 0;
    }
    LARGE_INTEGER copy_started;
    QueryPerformanceCounter(&copy_started);
    if (!dxc_bitmap_to_swapchain(width, height)) {
        return 0;
    }
    dxc_presented_width = width;
    dxc_presented_height = height;
    dxc_step_copy_ms = dxc_elapsed_ms(copy_started);
    // Presented: the window is on the GPU again, and the child follows the size below.
    dxc_switch_frames(0, width, height, "the frame drawn ahead was presented through the swapchain");
    return 1;
}

/**
 * Readies the hidden child for the frame drawn ahead (dxc_predraw_resize), before the window
 * takes its size: the child is given that size and the frame is presented to it, so WM_SIZE
 * only has to show it. One when it is ready.
 */
static int dxc_ready_surface(int32_t width, int32_t height) {
    dxc_surface_ready_width = 0;
    dxc_surface_ready_height = 0;
    if (dxc_surface == NULL || IsWindowVisible(dxc_surface)) {
        return 0;
    }
    if (dxc_surface_return == DXC_SURFACE_RETURN_PARKED || dxc_surface_return == DXC_SURFACE_RETURN_STAGED) {
        // Only refitted and filled: a present to a hidden child is not composed when it is
        // shown, so the present waits for WM_SIZE, after the show.
        if (!dxc_stage_bitmap(width, height)) {
            return 0;
        }
    } else {
        SetWindowPos(dxc_surface, NULL, 0, 0, width, height, SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOMOVE | SWP_NOREDRAW);
        if (!dxc_bitmap_to_swapchain(width, height)) {
            return 0;
        }
    }
    dxc_surface_ready_width = width;
    dxc_surface_ready_height = height;
    return 1;
}

// Compositions a parked child waits for its present to be taken, at most, before it is moved
// in anyway. Each is one DwmFlush, about a refresh.
#define DXC_PARKED_WAITS 4

/**
 * Shows the child readied for this size outside the window's client area, presents the frame
 * staged in its buffer, waits until DWM has taken that present, and only then moves the child
 * into place. One when it was shown.
 *
 * DWM composes a child that is shown again with the last picture it took from it, and a
 * present made while the child was hidden does not count (compose-rust#684). Shown in place
 * and then presented (staged), the child was composed once with the picture it had before it
 * was hidden whenever the present missed the next composition: after maximised -> restored at
 * 200 % on windows-2025, the top left of the maximised picture at the restored size
 * (compose-rust#926, about 2 % of restores). Parked, the child is visible, so its present
 * reaches DWM, but clipped away by the parent, so nothing of it is composed; when it is moved
 * in, the picture DWM holds for it is already the new one. Meanwhile the window's GDI surface,
 * which WM_SIZE has just copied the same frame into, is what shows.
 */
static int dxc_show_parked_surface(int32_t width, int32_t height) {
    UINT keep = SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOREDRAW;
    // Right of and below the client area: a child is clipped by its parent's client area.
    SetWindowPos(dxc_surface, NULL, width, height, width, height, keep);
    dxc_state_marks[DXC_STATE_FITTED] = dxc_now_ms();
    ShowWindow(dxc_surface, SW_SHOWNA);
    if (!dxc_present_staged()) {
        SetWindowPos(dxc_surface, NULL, 0, 0, 0, 0, keep | SWP_NOSIZE);
        return 0;
    }
    UINT presented = 0;
    IDXGISwapChain1_GetLastPresentCount(dxc_swapchain, &presented);
    int waits = 0;
    int taken = 0;
    int assumed = 0;
    while (waits < DXC_PARKED_WAITS && !taken) {
        if (!dxc_dwm_flush()) break;
        waits++;
        DXGI_FRAME_STATISTICS statistics;
        if (SUCCEEDED(IDXGISwapChain1_GetFrameStatistics(dxc_swapchain, &statistics))) {
            taken = statistics.PresentCount >= presented;
        } else if (waits >= 2) {
            // No statistics (none yet, or disjoint): two compositions are what a present
            // takes at most when nothing else holds the GPU. Logged as assumed, not taken, so a
            // log tells this guess apart from a present the statistics confirm.
            taken = 1;
            assumed = 1;
        }
    }
    SetWindowPos(dxc_surface, NULL, 0, 0, 0, 0, SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOSIZE);
    dxc_state_marks[DXC_STATE_SHOWN] = dxc_now_ms();
    double ready_at = dxc_state_marks[DXC_STATE_READY];
    fprintf(stderr,
            "compose-rust: resize-mode: steps after the frame was ready at %.1f ms: WM_WINDOWPOSCHANGED +%.2f, "
            "WM_SIZE +%.2f, copied +%.2f, child parked +%.2f, moved in +%.2f ms after %d composition%s (present %s)\n",
            ready_at, dxc_state_marks[DXC_STATE_CHANGED] - ready_at, dxc_state_marks[DXC_STATE_SIZE] - ready_at,
            dxc_state_marks[DXC_STATE_COPIED] - ready_at, dxc_state_marks[DXC_STATE_FITTED] - ready_at,
            dxc_state_marks[DXC_STATE_SHOWN] - ready_at, waits, waits == 1 ? "" : "s",
            assumed ? "assumed taken, no frame statistics" : taken ? "taken" : "not seen taken");
    return 1;
}

/**
 * Shows the child readied for this size (dxc_ready_surface), with the frame drawn ahead in
 * it, and goes back to the GPU. One when it was shown.
 *
 * The window's GDI surface, which WM_SIZE has just copied the same frame into, is not what
 * DWM composes with the new size at once: after a size change it went on composing the
 * window's earlier GDI picture, at the earlier size, until the child was shown again
 * (compose-rust#684). A child shown with its frame already presented is composed with it.
 */
static int dxc_show_ready_surface(int32_t width, int32_t height) {
    int ready = dxc_surface_ready_width == width && dxc_surface_ready_height == height &&
                dxc_sizing.fitted_width == width && dxc_sizing.fitted_height == height;
    dxc_surface_ready_width = 0;
    dxc_surface_ready_height = 0;
    if (dxc_surface == NULL || IsWindowVisible(dxc_surface) || dxc_surface_return == DXC_SURFACE_RETURN_POSTED) {
        return 0;
    }
    UINT fit = SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOMOVE;
    if (dxc_surface_return == DXC_SURFACE_RETURN_PARKED && ready) {
        if (!dxc_show_parked_surface(width, height)) return 0;
    } else if (dxc_surface_return == DXC_SURFACE_RETURN_NCCALC) {
        if (!ready) return 0;
        ShowWindow(dxc_surface, SW_SHOWNA);
    } else if (dxc_surface_return == DXC_SURFACE_RETURN_STAGED && ready) {
        SetWindowPos(dxc_surface, NULL, 0, 0, width, height, fit);
        dxc_state_marks[DXC_STATE_FITTED] = dxc_now_ms();
        ShowWindow(dxc_surface, SW_SHOWNA);
        dxc_state_marks[DXC_STATE_SHOWN] = dxc_now_ms();
        if (!dxc_present_staged()) return 0;
        double ready_at = dxc_state_marks[DXC_STATE_READY];
        fprintf(stderr,
                "compose-rust: resize-mode: steps after the frame was ready at %.1f ms: WM_WINDOWPOSCHANGED +%.2f, "
                "WM_SIZE +%.2f, copied +%.2f, child fitted +%.2f, shown +%.2f, presented +%.2f ms\n",
                ready_at, dxc_state_marks[DXC_STATE_CHANGED] - ready_at, dxc_state_marks[DXC_STATE_SIZE] - ready_at,
                dxc_state_marks[DXC_STATE_COPIED] - ready_at, dxc_state_marks[DXC_STATE_FITTED] - ready_at,
                dxc_state_marks[DXC_STATE_SHOWN] - ready_at, dxc_now_ms() - ready_at);
    } else if (dxc_surface_return == DXC_SURFACE_RETURN_WM_SIZE) {
        SetWindowPos(dxc_surface, NULL, 0, 0, width, height, fit);
        if (!dxc_bitmap_to_swapchain(width, height)) return 0;
        ShowWindow(dxc_surface, SW_SHOWNA);
    } else {
        // showfirst, and staged where the frame could not be readied ahead.
        SetWindowPos(dxc_surface, NULL, 0, 0, width, height, fit);
        ShowWindow(dxc_surface, SW_SHOWNA);
        if (!dxc_bitmap_to_swapchain(width, height)) return 0;
    }
    dxc_presented_width = width;
    dxc_presented_height = height;
    dxc_switch_frames(0, width, height, "the frame drawn ahead was shown through the swapchain");
    fprintf(stderr, "compose-rust: resize-mode: surface shown at %dx%d in WM_SIZE with the frame drawn ahead (%s, at %.1f ms)\n",
            (int)width, (int)height, dxc_surface_return_names[dxc_surface_return], dxc_now_ms());
    return 1;
}

static void dxc_draw_resize(int32_t width, int32_t height) {
    if (width == dxc_presented_width && height == dxc_presented_height) {
        return;
    }
    LARGE_INTEGER started;
    QueryPerformanceCounter(&started);
    dxc_resize_target_width = width;
    dxc_resize_target_height = height;
    if (dxc_drawing) {
        fprintf(stderr, "compose-rust: resize-mode: skipped present %dx%d because a frame was already being drawn\n",
                (int)width, (int)height);
    }
    if (dxc_raster_enabled && dxc_draw_frame != NULL && !dxc_drawing) {
        dxc_switch_frames(1, width, height, dxc_sizing.dragging ? "the edge is being dragged" : "the size changed");
    }
    dxc_resizing = 1;
    if (!dxc_predrawn_take(&dxc_predrawn, width, height) || !dxc_cpu_mode || dxc_dib_dc == NULL) {
        dxc_step_gpu_idle_ms = dxc_step_refit_ms = dxc_step_draw_ms = dxc_step_present_ms = 0.0;
        dxc_step_copy_ms = 0.0;
        dxc_last_blit = 0;
        dxc_last_validated = 0;
        dxc_draw_one_frame();
    } else {
        // Drawn in WM_NCCALCSIZE, before the window took this size: only the copy is left,
        // and it lands before DWM composes the new rectangle again.
        dxc_raster_width = width;
        dxc_raster_height = height;
        if (!dxc_raster_present(width, height)) {
            dxc_raster_copy();
            dxc_state_marks[DXC_STATE_COPIED] = dxc_now_ms();
            // Not inside a drag: the child stays hidden until the size has settled.
            if (!dxc_sizing.dragging) dxc_show_ready_surface(width, height);
        }
    }
    dxc_resizing = 0;
    if (dxc_cpu_mode) {
        // What this step put on screen and how, for the CI assertion over these lines.
        fprintf(stderr,
                "compose-rust: resize-proof step %dx%d presents=%d child_visible=%d blit=%d validated=%d at %.1f ms\n",
                (int)width, (int)height, (int)dxc_presents_in_change,
                dxc_surface != NULL && IsWindowVisible(dxc_surface) ? 1 : 0, dxc_last_blit, dxc_last_validated,
                dxc_now_ms());
        if (dxc_real_drag_active && getenv("DXC_CAPTURE_CHECK") != NULL) {
            dxc_capture_step(dxc_window);
        }
    } else if (dxc_surface != NULL) {
        // Frames on the GPU during the resize (DXC_RASTER_RESIZE=0): the child follows.
        SetWindowPos(dxc_surface, NULL, 0, 0, width, height, SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOMOVE);
    }
    // Outside a drag the size has stopped changing once this message is done; the GPU
    // takes over on the next turn of the message loop.
    if (dxc_cpu_mode && !dxc_sizing.dragging && !dxc_gpu_return_posted && dxc_window != NULL) {
        dxc_gpu_return_posted = PostMessageW(dxc_window, DXC_WM_GPU_RETURN, 0, 0) != 0;
    }
    int presented = dxc_presented_width == width && dxc_presented_height == height;
    double drawn_ms = dxc_elapsed_ms(started);
    // Not on the CPU path: its copy is in the window's own GDI surface, which DWM composes
    // with the new window size, so there is no present to wait for.
    int flushed = presented && !dxc_cpu_mode ? dxc_dwm_flush() : 0;
    double total_ms = dxc_elapsed_ms(started);
    if (total_ms >= DXC_RESIZE_STEP_CAP_MS) {
        fprintf(stderr, "compose-rust: resize step %dx%d hit the %.0f ms cap (%.2f ms)\n",
                (int)width, (int)height, DXC_RESIZE_STEP_CAP_MS, total_ms);
    }
    if (dxc_report_latency) {
        // In WM_SIZE: the window's rectangle is the step's now (GetWindowRect).
        RECT outer = {0, 0, 0, 0};
        if (dxc_window != NULL) GetWindowRect(dxc_window, &outer);
        fprintf(stderr,
                "compose-rust: resize step %dx%d %s, %s, took %.2f ms: gpu idle %.2f, refit %.2f, "
                "draw %.2f, copy %.2f, present %.2f, DwmFlush %.2f left=%ld top=%ld edge=%s\n",
                (int)width, (int)height, presented ? "presented" : "not presented",
                flushed ? "flushed" : "not flushed", total_ms, dxc_step_gpu_idle_ms,
                dxc_step_refit_ms, dxc_step_draw_ms, dxc_step_copy_ms, dxc_step_present_ms,
                total_ms - drawn_ms, outer.left, outer.top,
                dxc_sizing.dragging ? dxc_sizing_edge_name(dxc_drag_sizing_edge) : "none");
    }
}

/**
 * The Windows build number (20348 for Server 2022, 26100 for Server 2025), or zero where it
 * cannot be read. From RtlGetVersion, looked up at run time: GetVersionEx answers with the
 * build the executable's manifest names, not the one that is running.
 */
static uint32_t dxc_windows_build(void) {
    typedef LONG(WINAPI * dxc_rtl_get_version_fn)(OSVERSIONINFOW *);
    HMODULE ntdll = GetModuleHandleW(L"ntdll.dll");
    dxc_rtl_get_version_fn get =
            ntdll != NULL ? (dxc_rtl_get_version_fn)(void *)GetProcAddress(ntdll, "RtlGetVersion") : NULL;
    OSVERSIONINFOW version;
    memset(&version, 0, sizeof version);
    version.dwOSVersionInfoSize = sizeof version;
    if (get == NULL || get(&version) != 0) {
        return 0;
    }
    return (uint32_t)version.dwBuildNumber;
}

/**
 * Draws the frame for a size the window is about to be given, before it is given it.
 *
 * Called from WM_NCCALCSIZE with the client rectangle the window will have. Outside a drag
 * the window takes a new rectangle (maximised, restored, snapped, any SetWindowPos) before
 * WM_SIZE runs, and WM_SIZE drawing the frame then means DWM can compose the new
 * rectangle, for as long as the draw takes, with the old picture in it: the swapchain's
 * child window keeps its old size and is cut by the new rectangle, and the window's own
 * surface keeps its old pixels in the top left (compose-rust#684). So the frame is drawn
 * here, on the CPU into the bitmap, while the window still shows the old size with the old
 * picture; WM_SIZE then only copies it, which takes far less than a composition. The wait
 * for DWM to compose once more, last, puts that copy at the start of a composition
 * interval rather than at a random point of one.
 *
 * Inside a drag each step is drawn here too (issue 245, dxc_resize_drag_predraw_wanted):
 * drawn in WM_SIZE, a step left the window with its new rectangle and the picture of the
 * step before for the length of the draw, and a composition in that time showed a smaller
 * window with the old picture cut by it. The child is not readied (it stays hidden for the
 * whole drag), and the wait for a composition is taken only where dxc_drag_flush says so.
 */
/**
 * For DXC_DCOMP_SHRINK_WAIT=hold: the frame committed last is known to be taken by DWM, so the
 * window may shrink to its size. The wait is short by now: a step or a timer tick has passed
 * since that commit.
 */
static void dxc_comp_confirm(void) {
    if (dxc_comp_held_width <= 0) {
        return;
    }
    LARGE_INTEGER started;
    QueryPerformanceCounter(&started);
    dxc_dcomp_wait(dxc_comp);
    if (dxc_sizing.dragging) dxc_comp_time(&dxc_comp_wait_sum, &dxc_comp_wait_max, dxc_elapsed_ms(started));
    dxc_comp_confirmed_width = dxc_comp_held_width;
    dxc_comp_confirmed_height = dxc_comp_held_height;
    dxc_comp_held_width = 0;
    dxc_comp_held_height = 0;
}

/** Draws a CPU frame of width x height into the bitmap. One when it was drawn at that size. */
static int dxc_comp_draw_cpu(int32_t width, int32_t height) {
    dxc_switch_frames(1, width, height, "the edge is being dragged");
    dxc_predrawn_set(&dxc_predrawn, 0, 0);
    dxc_resize_target_width = width;
    dxc_resize_target_height = height;
    dxc_resizing = 1;
    dxc_predrawing = 1;
    dxc_draw_one_frame();
    dxc_predrawing = 0;
    dxc_resizing = 0;
    int drawn = dxc_predrawn.width == width && dxc_predrawn.height == height;
    dxc_predrawn_set(&dxc_predrawn, 0, 0);
    return drawn;
}

/**
 * For DXC_DCOMP_SHRINK_WAIT=hold, in WM_WINDOWPOSCHANGING during a drag, on the composition
 * route: DSA's keep_to_frame for a step that shrinks. The frame of the size asked for is drawn
 * and committed without waiting, and the window is given the size of the last frame DWM has
 * taken instead (never smaller than asked): whichever of the two frames a composition shows,
 * it fits inside the window, so nothing is cut, and nothing waits for DWM. The window follows a
 * step behind; a timer brings it to the last frame once the pointer stops. A step that grows,
 * or grows one side, goes the usual way. One when the size was held here.
 */
static int dxc_comp_hold_step(HWND window, WINDOWPOS *pos) {
    if (!dxc_on_comp || dxc_comp_wait_mode != DXC_COMP_WAIT_HOLD || !dxc_sizing.dragging ||
        (pos->flags & SWP_NOSIZE) != 0 || !dxc_raster_enabled || dxc_draw_frame == NULL || dxc_drawing ||
        dxc_drag_predraw_off) {
        return 0;
    }
    RECT outer, client;
    if (!GetWindowRect(window, &outer) || !GetClientRect(window, &client)) {
        return 0;
    }
    int32_t now_width = (int32_t)client.right;
    int32_t now_height = (int32_t)client.bottom;
    int32_t frame_width = (int32_t)(outer.right - outer.left) - now_width;
    int32_t frame_height = (int32_t)(outer.bottom - outer.top) - now_height;
    int32_t width = (int32_t)pos->cx - frame_width;
    int32_t height = (int32_t)pos->cy - frame_height;
    int shrinks = width < now_width || height < now_height;
    int grows = width > now_width || height > now_height;
    dxc_comp_confirm();
    if (!shrinks || grows || width <= 0 || height <= 0) {
        return 0;
    }
    LARGE_INTEGER started;
    QueryPerformanceCounter(&started);
    if (width != dxc_comp_confirmed_width || height != dxc_comp_confirmed_height) {
        if (!dxc_comp_draw_cpu(width, height) || !dxc_comp_upload_bitmap(width, height) ||
            !dxc_comp_show("a drag step held to its frame")) {
            return 0;
        }
        dxc_comp_held_width = width;
        dxc_comp_held_height = height;
        dxc_presented_width = width;
        dxc_presented_height = height;
        dxc_drag_predrawn_steps++;
    }
    // Never smaller than the frame DWM may still show, never larger than the window is now.
    int32_t keep_width = width;
    int32_t keep_height = height;
    if (dxc_comp_confirmed_width > keep_width) keep_width = dxc_comp_confirmed_width;
    if (dxc_comp_confirmed_height > keep_height) keep_height = dxc_comp_confirmed_height;
    if (keep_width > now_width) keep_width = now_width;
    if (keep_height > now_height) keep_height = now_height;
    int32_t keep_outer_width = keep_width + frame_width;
    int32_t keep_outer_height = keep_height + frame_height;
    // The edge not being dragged stays where it is.
    if ((pos->flags & SWP_NOMOVE) == 0) {
        if (pos->x != outer.left) pos->x += pos->cx - keep_outer_width;
        if (pos->y != outer.top) pos->y += pos->cy - keep_outer_height;
    }
    pos->cx = keep_outer_width;
    pos->cy = keep_outer_height;
    dxc_comp_hold_active = 1;
    if (keep_width != width || keep_height != height) {
        SetTimer(window, DXC_TIMER_COMP_HOLD, 16, NULL);
    }
    if (dxc_report_latency) {
        fprintf(stderr, "compose-rust: dcomp: drag step %dx%d held at %dx%d (%.2f ms)\n", (int)width, (int)height,
                (int)keep_width, (int)keep_height, dxc_elapsed_ms(started));
    }
    return 1;
}

/** The hold timer: the window is brought to the last frame DWM has taken, once it is taken. */
static void dxc_comp_hold_tick(HWND window) {
    dxc_comp_confirm();
    RECT outer, client;
    if (!dxc_sizing.dragging || dxc_comp_confirmed_width <= 0 || !GetWindowRect(window, &outer) ||
        !GetClientRect(window, &client)) {
        KillTimer(window, DXC_TIMER_COMP_HOLD);
        return;
    }
    int32_t now_width = (int32_t)client.right;
    int32_t now_height = (int32_t)client.bottom;
    if (dxc_comp_confirmed_width >= now_width && dxc_comp_confirmed_height >= now_height) {
        KillTimer(window, DXC_TIMER_COMP_HOLD);
        return;
    }
    int32_t outer_width = (int32_t)(outer.right - outer.left) - now_width + dxc_comp_confirmed_width;
    int32_t outer_height = (int32_t)(outer.bottom - outer.top) - now_height + dxc_comp_confirmed_height;
    WPARAM edge = dxc_comp_sizing_edge;
    int left = edge == WMSZ_LEFT || edge == WMSZ_TOPLEFT || edge == WMSZ_BOTTOMLEFT;
    int top = edge == WMSZ_TOP || edge == WMSZ_TOPLEFT || edge == WMSZ_TOPRIGHT;
    int x = left ? outer.right - outer_width : outer.left;
    int y = top ? outer.bottom - outer_height : outer.top;
    SetWindowPos(window, NULL, x, y, outer_width, outer_height, SWP_NOZORDER | SWP_NOACTIVATE);
}

static int dxc_hide_surface_early(HWND window, int32_t width, int32_t height);
static void dxc_predraw_resize(HWND window, const RECT *client) {
    if (dxc_predraw_off || dxc_swapchain == NULL || dxc_draw_frame == NULL || IsIconic(window)) {
        return;
    }
    // A step held to its frame (dxc_comp_hold_step): that frame is already committed.
    if (dxc_comp_hold_active) {
        return;
    }
    RECT now;
    if (!GetClientRect(window, &now)) {
        return;
    }
    int32_t width = (int32_t)(client->right - client->left);
    int32_t height = (int32_t)(client->bottom - client->top);
    int dragging = dxc_sizing.dragging;
    if (dragging) {
        if (!dxc_resize_drag_predraw_wanted(&dxc_sizing, dxc_raster_enabled, dxc_drawing, !dxc_drag_predraw_off,
                                            width, height, (int32_t)(now.right - now.left),
                                            (int32_t)(now.bottom - now.top))) {
            return;
        }
    } else if (!dxc_resize_predraw_wanted(&dxc_sizing, dxc_raster_enabled, dxc_drawing, width, height,
                                          (int32_t)(now.right - now.left), (int32_t)(now.bottom - now.top),
                                          dxc_presented_width, dxc_presented_height)) {
        return;
    }
    LARGE_INTEGER started;
    QueryPerformanceCounter(&started);
    int hidden = dxc_hide_surface_early(window, (int32_t)(now.right - now.left), (int32_t)(now.bottom - now.top));
    dxc_switch_frames(1, width, height, dragging ? "the edge is being dragged" : "the size is about to change");
    int32_t now_width = (int32_t)(now.right - now.left);
    int32_t now_height = (int32_t)(now.bottom - now.top);
    int shrinks = width < now_width || height < now_height;
    int grows = width > now_width || height > now_height;
    // Whether the frame both rectangles hold was committed and waited for (it counts as a
    // step shown first), for DXC_REPORT_LATENCY's drag step line.
    int both_held = 0;
    if (dxc_on_comp && dxc_comp_commit_mode == DXC_COMP_COMMIT_ORDER && shrinks && grows) {
        int shown_first = dxc_comp_frame_first_steps;
        dxc_comp_show_both_hold(width, height, now_width, now_height);
        both_held = dxc_comp_frame_first_steps != shown_first;
    }
    dxc_predrawn_set(&dxc_predrawn, 0, 0);
    dxc_resize_target_width = width;
    dxc_resize_target_height = height;
    dxc_resizing = 1;
    dxc_predrawing = 1;
    dxc_draw_one_frame();
    dxc_predrawing = 0;
    dxc_resizing = 0;
    int drawn = dxc_predrawn.width == width && dxc_predrawn.height == height;
    if (dragging) {
        // The child stays hidden until the drag is over, so nothing is readied for it. The
        // wait puts the size change and WM_SIZE's copy at the start of a composition interval.
        double step_drawn_ms = dxc_elapsed_ms(started);
        // On the composition route the step goes into the surface before the wait and is
        // committed after it, here rather than in WM_SIZE (dxc_comp_stage_ahead).
        int step_staged = dxc_on_comp && drawn && dxc_comp_stage_ahead(width, height);
        LARGE_INTEGER rule_started;
        QueryPerformanceCounter(&rule_started);
        int rule_wanted = hidden || dxc_drag_flush;
        if (dxc_on_comp && !dxc_comp_drag_rule) rule_wanted = 0;
        int step_flushed = drawn && rule_wanted ? dxc_dwm_flush() : 0;
        if (dxc_on_comp) {
            dxc_comp_timed_steps++;
            dxc_comp_time(&dxc_comp_rule_sum, &dxc_comp_rule_max, dxc_elapsed_ms(rule_started));
        }
        if (step_staged) dxc_comp_commit_ahead(shrinks && !grows);
        if (drawn) dxc_drag_predrawn_steps++;
        if (step_flushed) dxc_drag_flushed_steps++;
        if (dxc_report_latency) {
            // Where the window is as the step begins (GetWindowRect: the rectangle before the
            // step), where its client area goes, the edge dragged, and on the composition route
            // whether the step's frame went to DWM before the rectangle (committed first, in
            // WM_NCCALCSIZE) or after it (committed in WM_SIZE), and whether the frame both
            // rectangles hold was committed first.
            RECT before;
            GetWindowRect(window, &before);
            const char *commit = !dxc_on_comp ? "copied in WM_SIZE (gdi)"
                                 : !step_staged ? "not staged"
                                 : dxc_comp_staged_committed ? "committed first"
                                                             : "committed in WM_SIZE";
            fprintf(stderr,
                    "compose-rust: resize-mode: drag step %dx%d drawn before the window took it (%s, draw %.2f ms, "
                    "%s %.2f ms, at %.1f ms) left=%ld top=%ld to client left=%ld top=%ld edge=%s, %s%s\n",
                    (int)width, (int)height, drawn ? "waiting for WM_SIZE" : "not drawn", step_drawn_ms,
                    step_flushed ? "DwmFlush" : "no DwmFlush", dxc_elapsed_ms(started) - step_drawn_ms,
                    dxc_now_ms(), before.left, before.top, client->left, client->top,
                    dxc_sizing_edge_name(dxc_drag_sizing_edge), commit,
                    both_held ? ", the frame both rectangles hold committed first" : "");
        }
        return;
    }
    int ready = drawn && hidden &&
                (dxc_surface_return == DXC_SURFACE_RETURN_NCCALC || dxc_surface_return == DXC_SURFACE_RETURN_STAGED ||
                 dxc_surface_return == DXC_SURFACE_RETURN_PARKED) &&
                dxc_ready_surface(width, height);
    // The composition route has no child to hide (hidden is 0 there): the frame goes into the
    // surface now, and is committed here or in WM_SIZE by whether the size shrinks
    // (dxc_comp_commit_mode).
    int staged = dxc_on_comp && drawn && dxc_comp_stage_ahead(width, height);
    double drawn_ms = dxc_elapsed_ms(started);
    // Only for the copy into the GDI surface. A frame presented through the swapchain is
    // waited for after its present, in WM_SIZE, like any GPU resize frame. Always on the
    // composition route, before its commit.
    int flush_wanted = dxc_on_comp ? dxc_comp_commit_mode == DXC_COMP_COMMIT_AHEAD
                                   : (dxc_raster_present_off || dxc_predraw_flush || hidden);
    int flushed = drawn && flush_wanted ? dxc_dwm_flush() : 0;
    if (staged) dxc_comp_commit_ahead(shrinks && !grows);
    fprintf(stderr,
            "compose-rust: resize-mode: drew %dx%d before the window took it (%s%s, draw %.2f ms, %s %.2f ms, at %.1f ms)\n",
            (int)width, (int)height, drawn ? "waiting for WM_SIZE" : "not drawn",
            ready ? ", presented to the hidden surface"
            : !staged ? ""
            : dxc_comp_staged_committed ? ", committed to the composition surface"
                                        : ", staged in the composition surface",
            drawn_ms, flushed ? "DwmFlush" : "no DwmFlush", dxc_elapsed_ms(started) - drawn_ms, dxc_now_ms());
    dxc_state_marks[DXC_STATE_READY] = dxc_now_ms();
}

/**
 * Copies the picture the swapchain's child shows into the window's own GDI surface, at
 * width x height, the size both have now. One when it was copied.
 *
 * The swapchain is copy model with one buffer (DXGI_SWAP_EFFECT_SEQUENTIAL), so that buffer
 * still holds the frame last presented. It is read back through a staging texture into the
 * bitmap and copied into the window, under the child, where it changes nothing on screen
 * until the child is hidden.
 */
static int dxc_copy_shown_to_window(int32_t width, int32_t height) {
    if (dxc_swapchain == NULL || dxc_d3d11 == NULL || dxc_d3d11_context == NULL || dxc_window == NULL ||
        width <= 0 || height <= 0 || !dxc_ensure_dib(width, height)) {
        return 0;
    }
    ID3D11Texture2D *back = NULL;
    HRESULT got = IDXGISwapChain1_GetBuffer(dxc_swapchain, 0, &IID_ID3D11Texture2D, (void **)&back);
    if (FAILED(got)) {
        dxc_log_dxgi_failure("GetBuffer", got);
        return 0;
    }
    D3D11_TEXTURE2D_DESC described;
    ID3D11Texture2D_GetDesc(back, &described);
    if ((int32_t)described.Width < width || (int32_t)described.Height < height) {
        ID3D11Texture2D_Release(back);
        return 0;
    }
    D3D11_TEXTURE2D_DESC staging_described;
    memset(&staging_described, 0, sizeof staging_described);
    staging_described.Width = (UINT)width;
    staging_described.Height = (UINT)height;
    staging_described.MipLevels = 1;
    staging_described.ArraySize = 1;
    staging_described.Format = described.Format;
    staging_described.SampleDesc.Count = 1;
    staging_described.Usage = D3D11_USAGE_STAGING;
    staging_described.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    ID3D11Texture2D *staging = NULL;
    if (FAILED(ID3D11Device_CreateTexture2D(dxc_d3d11, &staging_described, NULL, &staging))) {
        ID3D11Texture2D_Release(back);
        return 0;
    }
    D3D11_BOX shown = {0, 0, 0, (UINT)width, (UINT)height, 1};
    ID3D11DeviceContext_CopySubresourceRegion(dxc_d3d11_context, (ID3D11Resource *)staging, 0, 0, 0, 0,
                                              (ID3D11Resource *)back, 0, &shown);
    ID3D11Texture2D_Release(back);
    int read = 0;
    D3D11_MAPPED_SUBRESOURCE mapped;
    if (SUCCEEDED(ID3D11DeviceContext_Map(dxc_d3d11_context, (ID3D11Resource *)staging, 0, D3D11_MAP_READ, 0,
                                          &mapped))) {
        GdiFlush();
        for (int32_t y = 0; y < height; y++) {
            memcpy((uint8_t *)dxc_dib_bits + (size_t)y * (size_t)dxc_dib_width * 4,
                   (const uint8_t *)mapped.pData + (size_t)y * mapped.RowPitch, (size_t)width * 4);
        }
        ID3D11DeviceContext_Unmap(dxc_d3d11_context, (ID3D11Resource *)staging, 0);
        read = 1;
    }
    ID3D11Texture2D_Release(staging);
    ID3D11DeviceContext_Flush(dxc_d3d11_context);
    if (!read) {
        return 0;
    }
    int copied = 0;
    HDC window_dc = GetDC(dxc_window);
    if (window_dc != NULL) {
        // Not over the native views, which are in this GDI surface too (dxc_raster_copy).
        dxc_nv_exclude(dxc_w, window_dc);
        copied = BitBlt(window_dc, 0, 0, width, height, dxc_dib_dc, 0, 0, SRCCOPY) != 0;
        GdiFlush();
        ReleaseDC(dxc_window, window_dc);
    }
    return copied;
}

/**
 * Hides the swapchain's child before a size given outside a drag reaches the window, with
 * the picture it showed copied into the window's GDI surface first, so the screen does not
 * change. One when the child was hidden.
 *
 * DWM resizes and composes the window's GDI surface together with the window, and the CPU
 * frame WM_SIZE copies into it lands with the new size. The child is another surface: hidden
 * only in WM_SIZE, after the window had taken the new size, it was still composed once with
 * its old picture at its old size over the window, cut by a smaller one (restore, left-half
 * snap and back; compose-rust#684). Hidden here, and composed hidden (the DwmFlush) before
 * the window takes the size, it is not on screen when the window changes.
 */
static int dxc_hide_surface_early(HWND window, int32_t width, int32_t height) {
    if (dxc_hide_early_off || dxc_surface == NULL || !IsWindowVisible(dxc_surface) ||
        dxc_presented_width != width || dxc_presented_height != height ||
        dxc_sizing.fitted_width != width || dxc_sizing.fitted_height != height) {
        return 0;
    }
    LARGE_INTEGER started;
    QueryPerformanceCounter(&started);
    if (!dxc_copy_shown_to_window(width, height)) {
        fprintf(stderr, "compose-rust: resize-mode: surface kept at %dx%d because its picture could not be copied\n",
                (int)width, (int)height);
        return 0;
    }
    double copied_ms = dxc_elapsed_ms(started);
    ShowWindow(dxc_surface, SW_HIDE);
    // Hiding the child asks the window to paint the area it uncovered, which already holds
    // the same picture.
    ValidateRect(window, NULL);
    int flushed = dxc_dwm_flush();
    fprintf(stderr,
            "compose-rust: resize-mode: surface hidden at %dx%d before the size changed (copy %.2f ms, %s %.2f ms, at %.1f ms)\n",
            (int)width, (int)height, copied_ms, flushed ? "DwmFlush" : "no DwmFlush",
            dxc_elapsed_ms(started) - copied_ms, dxc_now_ms());
    return 1;
}

#ifndef WM_GETDPISCALEDSIZE
#define WM_GETDPISCALEDSIZE 0x02E4
#endif
// Posted from WM_SIZE when a window whose DPI changed while it was maximised, minimised or
// snapped is restored: it is given its size at the new DPI once the restore is done.
#define DXC_WM_DPI_RESTORE (WM_APP + 3)

typedef BOOL(WINAPI *dxc_is_window_arranged_fn)(HWND);

/**
 * 1 while the window is snapped (arranged) to an edge or a corner of its monitor. Looked up
 * at run time: user32 has exported IsWindowArranged since Windows 7, but SDK headers declare
 * it only lately. Where it is missing the window is taken not to be snapped.
 */
static int dxc_window_arranged(HWND window) {
    static int looked_up;
    static dxc_is_window_arranged_fn is_arranged;
    if (!looked_up) {
        HMODULE user32 = GetModuleHandleW(L"user32.dll");
        if (user32 != NULL) {
            is_arranged = (dxc_is_window_arranged_fn)(void *)GetProcAddress(user32, "IsWindowArranged");
        }
        looked_up = 1;
    }
    return is_arranged != NULL && is_arranged(window) ? 1 : 0;
}

/**
 * The outer size, in pixels at [dpi], of a window whose client area is [width] x [height]
 * pixels: the frame AdjustWindowRectExForDpi gives the window's style at that DPI, less the
 * part above the content when the window draws its own caption (WM_NCCALCSIZE keeps the top
 * edge where it was).
 */
static void dxc_outer_for_client(int32_t width, int32_t height, UINT dpi, int32_t *outer_width,
                                 int32_t *outer_height) {
    RECT wanted = {0, 0, width, height};
    AdjustWindowRectExForDpi(&wanted, dxc_window_style(), FALSE, 0, dpi);
    *outer_width = wanted.right - wanted.left;
    *outer_height = dxc_options.system_chrome ? wanted.bottom - wanted.top : wanted.bottom;
}

/** 1 when a change of DPI now resizes the window to the size it keeps (win32_dpi.h). */
static int dxc_dpi_resizes_now(HWND window) {
    return dxc_w->dpi_size.dpi != 0 &&
           dxc_dpi_change_resizes(IsZoomed(window) ? 1 : 0, IsIconic(window) ? 1 : 0,
                                  dxc_window_arranged(window));
}

/**
 * Gives the window the client area its kept size comes to at [dpi], the outer top left at
 * ([left], [top]). Goes through SetWindowPos, so the size reaches WM_NCCALCSIZE and WM_SIZE
 * and is drawn ahead, presented and flushed there like any other size change outside a drag.
 *
 * The outer size comes from AdjustWindowRectExForDpi at the new DPI. Should the frame the
 * window then has differ from it, the client area is measured and the outer size corrected
 * by the difference once, so the client area is the kept size whatever the frame came to.
 */
static void dxc_apply_dpi_size(HWND window, int32_t left, int32_t top, UINT dpi, const char *why) {
    int32_t width = 0, height = 0;
    dxc_dpi_size_client(dxc_w->dpi_size, dpi, dxc_options.min_width, dxc_options.min_height, &width, &height);
    int32_t outer_width = 0, outer_height = 0;
    dxc_outer_for_client(width, height, dpi, &outer_width, &outer_height);
    dxc_w->in_dpi_change = 1;
    SetWindowPos(window, NULL, left, top, outer_width, outer_height, SWP_NOZORDER | SWP_NOACTIVATE);
    RECT client = {0, 0, 0, 0};
    GetClientRect(window, &client);
    int32_t short_width = width - (int32_t)(client.right - client.left);
    int32_t short_height = height - (int32_t)(client.bottom - client.top);
    if (short_width != 0 || short_height != 0) {
        SetWindowPos(window, NULL, left, top, outer_width + short_width, outer_height + short_height,
                     SWP_NOZORDER | SWP_NOACTIVATE);
        GetClientRect(window, &client);
    }
    dxc_w->in_dpi_change = 0;
    // The frame at the new DPI is the applied outer size less the client area, so this line
    // and the one WM_DPICHANGED prints give the frame at both DPIs.
    fprintf(stderr,
            "compose-rust: dpi-size: %s: kept %dx%d at %u dpi, client wanted %dx%d at %u dpi, "
            "outer %dx%d, corrected by %d,%d, client now %ldx%ld\n",
            why, (int)dxc_w->dpi_size.width, (int)dxc_w->dpi_size.height, (unsigned)dxc_w->dpi_size.dpi,
            (int)width, (int)height, (unsigned)dpi, (int)outer_width, (int)outer_height,
            (int)short_width, (int)short_height,
            (long)(client.right - client.left), (long)(client.bottom - client.top));
}

static LRESULT dxc_window_message(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    if (dxc_trace_state >= 0 && dxc_trace_on()) dxc_trace_message(window, message, wparam, lparam);
    switch (message) {
    case WM_NCCALCSIZE: {
        if (wparam != TRUE) {
            break;
        }
        NCCALCSIZE_PARAMS *params = (NCCALCSIZE_PARAMS *)lparam;
        if (dxc_options.system_chrome) {
            LRESULT answer = DefWindowProcW(window, message, wparam, lparam);
            dxc_predraw_resize(window, &params->rgrc[0]);
            return answer;
        }
        LONG requested_top = params->rgrc[0].top;
        DefWindowProcW(window, message, wparam, lparam);
        params->rgrc[0].top = IsZoomed(window) ? requested_top + dxc_maximised_overhang(window)
                                               : requested_top;
        // rgrc[0] is now the client rectangle the window is about to have.
        dxc_predraw_resize(window, &params->rgrc[0]);
        return 0;
    }
    case WM_NCHITTEST:
        if (dxc_options.system_chrome) {
            break;
        }
        return dxc_caption_hit_test(window, lparam);
    case WM_NCMOUSEMOVE:
        if (!dxc_options.system_chrome) {
            int over_maximise = wparam == HTMAXBUTTON;
            if (over_maximise) {
                // Asked for, because a pointer leaving a non-client button sends nothing else.
                TRACKMOUSEEVENT track = {sizeof track, TME_LEAVE | TME_NONCLIENT, window, 0};
                TrackMouseEvent(&track);
            }
            // A move from the button onto the caption or the top band is no leave to
            // Windows, so the scene is told here that the button is not under the pointer.
            dxc_forward_caption(window, dxc_caption_nc_move(&dxc_w->caption_pointer, over_maximise), lparam);
            if (over_maximise) {
                return 0;
            }
        }
        break;
    case WM_NCMOUSELEAVE:
        if (!dxc_options.system_chrome) {
            dxc_forward_caption(window, dxc_caption_nc_leave(&dxc_w->caption_pointer, dxc_pointer_held(window)), 0);
        }
        break;
    case WM_MOUSELEAVE:
        // A pointer that left the client area for a native view slot has left the scene.
        dxc_nv_mouse_left(dxc_w, window);
        // Only a window that draws its own caption asks for this message (WM_MOUSEMOVE
        // below): its minimise and close buttons are at the window's edge, where the
        // pointer leaves without another move, and would stay lit.
        if (!dxc_options.system_chrome) {
            dxc_forward_caption(window, dxc_caption_client_leave(&dxc_w->caption_pointer, dxc_pointer_held(window)), 0);
            return 0;
        }
        break;
    case WM_NCLBUTTONDOWN:
    case WM_NCLBUTTONDBLCLK:
        if (!dxc_options.system_chrome && wparam == HTMAXBUTTON) {
            // Swallowed, or the default handler runs its own button loop and toggles the
            // window itself; the renderer's button is the one that acts on the release.
            // Captured like a press in the client area: the release then arrives as
            // WM_LBUTTONUP wherever the pointer is, over the button, over the caption or
            // off the window, so the scene never keeps a button it was told is down.
            SetCapture(window);
            dxc_push_caption_pointer(window, DXC_EVENT_POINTER_DOWN, lparam);
            return 0;
        }
        break;
    case WM_NCLBUTTONUP:
        if (!dxc_options.system_chrome && wparam == HTMAXBUTTON) {
            dxc_push_caption_pointer(window, DXC_EVENT_POINTER_UP, lparam);
            return 0;
        }
        break;
    case WM_GETMINMAXINFO:
        if (dxc_options.min_width > 0 || dxc_options.min_height > 0) {
            UINT dpi = GetDpiForWindow(window);
            if (dpi == 0) {
                dpi = USER_DEFAULT_SCREEN_DPI;
            }
            RECT wanted = {0, 0, MulDiv(dxc_options.min_width, (int)dpi, USER_DEFAULT_SCREEN_DPI),
                           MulDiv(dxc_options.min_height, (int)dpi, USER_DEFAULT_SCREEN_DPI)};
            AdjustWindowRectExForDpi(&wanted, dxc_window_style(), FALSE, 0, dpi);
            MINMAXINFO *info = (MINMAXINFO *)lparam;
            info->ptMinTrackSize.x = wanted.right - wanted.left;
            // The caption strip is client area here, so the top of the frame is not
            // outside the content.
            info->ptMinTrackSize.y = dxc_options.system_chrome ? wanted.bottom - wanted.top
                                                               : wanted.bottom;
            return 0;
        }
        break;
    case WM_MOUSEMOVE:
        // No tracking area. Windows delivers a move whenever the pointer is over the
        // client area, so hover, which is half of what a desktop control does, arrives
        // without having to be asked for.
        if (!dxc_options.system_chrome) {
            // The end of the hover does have to be asked for, each time the pointer comes
            // back: a leave ends the request. Not while the mouse is captured, when a move
            // arrives from anywhere and asking from outside the client area is answered
            // with a leave at once.
            if (GetCapture() != window) {
                TRACKMOUSEEVENT track = {sizeof track, TME_LEAVE, window, 0};
                TrackMouseEvent(&track);
            }
            dxc_caption_client_move(&dxc_w->caption_pointer);
        } else if (dxc_w->native_views.count > 0 && GetCapture() != window) {
            // With native views in the window, the leave onto one of them is asked for here
            // too (dxc_nv_mouse_left).
            TRACKMOUSEEVENT track = {sizeof track, TME_LEAVE, window, 0};
            TrackMouseEvent(&track);
        }
        // Back from a native view slot: the scene hears the pointer come in, then the move.
        if (dxc_w->native_views.pointer_in_view) dxc_nv_pointer_back(dxc_w, lparam);
        dxc_push_pointer(DXC_EVENT_POINTER_MOVE, lparam, 0);
        return 0;
    case WM_SETCURSOR:
        // Over a native view slot the cursor is the view's to set: asked here first by the
        // view's default handler, the window answers that it set none. The pointer has left
        // the scene, which is told so once.
        if ((HWND)wparam != window && dxc_nv_set_cursor(dxc_w, window, (HWND)wparam)) {
            return FALSE;
        }
        if (LOWORD(lparam) == HTCLIENT) {
            // A null cursor is the hidden pointer (CursorShape.None).
            SetCursor(dxc_cursor != NULL ? LoadCursorW(NULL, dxc_cursor) : NULL);
            return 1;
        }
        break;
    case WM_LBUTTONDOWN:
    case WM_RBUTTONDOWN:
    case WM_MBUTTONDOWN:
    case WM_XBUTTONDOWN:
        // A press in the scene takes the keyboard back from a native view that had it.
        dxc_nv_scene_pressed(dxc_w, window);
        // Held so that a drag leaving the window still reports where it went, and so the
        // release that ends it is heard at all.
        SetCapture(window);
        dxc_push_pointer(DXC_EVENT_POINTER_DOWN, lparam, dxc_event_button(message, wparam));
        // WM_XBUTTONDOWN asks for TRUE, where the other button messages ask for zero.
        return message == WM_XBUTTONDOWN ? TRUE : 0;
    case WM_LBUTTONUP:
    case WM_RBUTTONUP:
    case WM_MBUTTONUP:
    case WM_XBUTTONUP:
        ReleaseCapture();
        dxc_push_pointer(DXC_EVENT_POINTER_UP, lparam, dxc_event_button(message, wparam));
        // A right click in a focused text field: the release is heard first, so the scene
        // does not think the button is still held while the menu is up.
        if (message == WM_RBUTTONUP && (dxc_edit_menu & DXC_EDIT_MENU_ON) != 0) {
            POINT where = {GET_X_LPARAM(lparam), GET_Y_LPARAM(lparam)};
            ClientToScreen(window, &where);
            dxc_show_edit_menu(window, 0, where);
        }
        return message == WM_XBUTTONUP ? TRUE : 0;
    case WM_MOUSEWHEEL:
    case WM_MOUSEHWHEEL: {
        // The sign of each axis is decided in win32_wheel.h, where it is tested.
        float scroll_x = 0.0f, scroll_y = 0.0f;
        dxc_wheel_to_scroll(GET_WHEEL_DELTA_WPARAM(wparam), message == WM_MOUSEHWHEEL,
            dxc_wheel_lines(), &scroll_x, &scroll_y);
        dxc_push_scroll(scroll_x, scroll_y);
        return 0;
    }
    case WM_KEYDOWN:
    case WM_SYSKEYDOWN:
    case WM_KEYUP:
    case WM_SYSKEYUP:
        // Anything held with alt, and F10, arrive as a system key. Answered here rather
        // than passed on, because the default handler puts the window into menu mode on a
        // keystroke the scene was meant to read (win32_keys.h, where it is tested). Alt and
        // F4 together is the exception: it is how a window is closed from the keyboard, and
        // the handler that does that is the one being stepped around.
        //
        // While a text field has focus and the window's edit menu is on, the press of the
        // Menu key, or of Shift and F10, puts that menu up at the caret instead, and goes no
        // further (win32_edit_menu.h).
        if (dxc_key_is_press(message) && (dxc_edit_menu & DXC_EDIT_MENU_ON) != 0 &&
            dxc_edit_menu_key_opens((uint32_t)wparam, dxc_held_modifiers())) {
            POINT none = {0, 0};
            dxc_show_edit_menu(window, 1, none);
            return 0;
        }
        dxc_push_key(dxc_key_is_press(message) ? DXC_EVENT_KEY_DOWN : DXC_EVENT_KEY_UP, wparam);
        if (dxc_key_passes_to_system(message, wparam)) break;
        return 0;
    case WM_CONTEXTMENU:
        // Asked for some other way, a pen held down for one: at the point it names, or at
        // the caret for the keyboard's (-1, -1). A right click on the caption arrives here
        // from the default handler too, and that one is the window menu's, so a point
        // outside the client area is passed on.
        if ((dxc_edit_menu & DXC_EDIT_MENU_ON) != 0 &&
            (lparam == (LPARAM)-1 || SendMessageW(window, WM_NCHITTEST, 0, lparam) == HTCLIENT)) {
            POINT where = {GET_X_LPARAM(lparam), GET_Y_LPARAM(lparam)};
            dxc_show_edit_menu(window, lparam == (LPARAM)-1, where);
            return 0;
        }
        break;
    case WM_ENTERSIZEMOVE:
        // The reader has taken hold of an edge, or of the title bar. From here until the
        // matching message below, everything this window hears is dispatched from a loop
        // inside `DefWindowProc` rather than from the renderer's frame loop, and that
        // loop does not return until the reader lets go.
        dxc_resize_begin_drag(&dxc_sizing);
        dxc_drag_predrawn_steps = 0;
        dxc_drag_flushed_steps = 0;
        dxc_drag_started_ms = dxc_now_ms();
        dxc_comp_frame_first_steps = 0;
        dxc_comp_timed_steps = 0;
        if (dxc_on_comp) {
            RECT shown;
            GetClientRect(window, &shown);
            dxc_comp_confirmed_width = (int32_t)shown.right;
            dxc_comp_confirmed_height = (int32_t)shown.bottom;
            dxc_comp_held_width = 0;
            dxc_comp_held_height = 0;
            dxc_comp_hold_active = 0;
        }
        dxc_comp_upload_sum = dxc_comp_upload_max = 0.0;
        dxc_comp_wait_sum = dxc_comp_wait_max = 0.0;
        dxc_comp_flush_sum = dxc_comp_flush_max = 0.0;
        dxc_comp_rule_sum = dxc_comp_rule_max = 0.0;
        dxc_comp_uploads = 0;
        dxc_comp_begin_sum = dxc_comp_write_sum = dxc_comp_end_sum = 0.0;
        dxc_mode("size-move", "off", "on", "WM_ENTERSIZEMOVE");
        // Each step of the drag has to be drawn and presented before DWM shows the new
        // size, so the thread doing it should not lose the CPU to background load.
        if (dxc_priority() >= 1) {
            dxc_thread_priority_before = GetThreadPriority(GetCurrentThread());
            SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_ABOVE_NORMAL);
        }
        return 0;
    case WM_EXITSIZEMOVE:
        // Let go. The frame loop has its turns back, so a size arriving after this is
        // written down and taken by the next frame.
        dxc_resize_end_drag(&dxc_sizing);
        dxc_mode("size-move", "on", "off", "WM_EXITSIZEMOVE");
        fprintf(stderr, "compose-rust: resize-proof drag steps drawn ahead=%d waited for a composition=%d\n",
                dxc_drag_predrawn_steps, dxc_drag_flushed_steps);
        {
            // How fast the window followed: the steps it took a size in, over the drag's length.
            // Waiting for a composition before each step slows this to at most one per refresh.
            double drag_ms = dxc_now_ms() - dxc_drag_started_ms;
            fprintf(stderr, "compose-rust: drag pace steps=%d over %.1f ms = %.1f steps/s (waited=%d)\n",
                    dxc_drag_predrawn_steps, drag_ms, drag_ms > 0.0 ? dxc_drag_predrawn_steps * 1000.0 / drag_ms : 0.0,
                    dxc_drag_flushed_steps);
        }
        if (dxc_on_comp) {
            fprintf(stderr, "compose-rust: dcomp: drag steps shown before the window shrank=%d (DXC_DCOMP_COMMIT=%s)\n",
                    dxc_comp_frame_first_steps, dxc_comp_commit_names[dxc_comp_commit_mode]);
            int timed = dxc_comp_timed_steps > 0 ? dxc_comp_timed_steps : 1;
            int first = dxc_comp_frame_first_steps > 0 ? dxc_comp_frame_first_steps : 1;
            fprintf(stderr,
                    "compose-rust: dcomp: drag timing steps=%d shown_first=%d (DXC_DCOMP_SHRINK_WAIT=%s): "
                    "upload avg %.2f max %.2f ms, drag rule wait avg %.2f max %.2f ms, "
                    "commit wait avg %.2f max %.2f ms, composition wait avg %.2f max %.2f ms\n",
                    dxc_comp_timed_steps, dxc_comp_frame_first_steps, dxc_comp_wait_names[dxc_comp_wait_mode],
                    dxc_comp_upload_sum / timed, dxc_comp_upload_max, dxc_comp_rule_sum / timed, dxc_comp_rule_max,
                    dxc_comp_wait_sum / first, dxc_comp_wait_max, dxc_comp_flush_sum / first, dxc_comp_flush_max);
            int uploads = dxc_comp_uploads > 0 ? dxc_comp_uploads : 1;
            fprintf(stderr,
                    "compose-rust: dcomp: drag upload parts uploads=%d device=%s drag_rule=%s: BeginDraw avg %.2f ms, "
                    "write and Flush avg %.2f ms, EndDraw avg %.2f ms\n",
                    dxc_comp_uploads, dxc_comp_native ? "native" : "on12", dxc_comp_drag_rule ? "on" : "off",
                    dxc_comp_begin_sum / uploads, dxc_comp_write_sum / uploads, dxc_comp_end_sum / uploads);
        }
        // A step drawn ahead for a size the window never took is a frame nobody may show.
        dxc_predrawn_set(&dxc_predrawn, 0, 0);
        if (dxc_on_comp) {
            KillTimer(window, DXC_TIMER_COMP_HOLD);
            dxc_comp_confirm();
            dxc_comp_hold_active = 0;
        }
        dxc_comp_staged_width = 0;
        dxc_comp_staged_height = 0;
        dxc_comp_staged_committed = 0;
        if (dxc_external_drag) {
            // The window's side of an external drag: how many sizes it drew. None is a
            // failure of the drag, not a pass.
            fprintf(stderr, "compose-rust: real drag done steps=%d verdict %s\n", dxc_external_drag_steps,
                    dxc_external_drag_steps > 0 ? "PASS" : "FAIL (no step)");
            fflush(stderr);
            dxc_external_drag_steps = 0;
        }
        if (dxc_real_drag_active) {
            dxc_real_drag_active = 0;
            // A drag that judged no step proves nothing, so it fails.
            fprintf(stderr, "compose-rust: capture-check verdict %s (%d of %d steps failed) real drag\n",
                    dxc_capture_failures == 0 && dxc_capture_steps > 0 ? "PASS" : "FAIL",
                    dxc_capture_failures, dxc_capture_steps);
        }
        if (dxc_cpu_mode) {
            RECT client;
            GetClientRect(window, &client);
            dxc_switch_frames(0, client.right - client.left, client.bottom - client.top,
                              "the drag ended");
            dxc_return_to_gpu(window, client.right - client.left, client.bottom - client.top);
        }
        if (dxc_thread_priority_before != THREAD_PRIORITY_ERROR_RETURN) {
            SetThreadPriority(GetCurrentThread(), dxc_thread_priority_before);
            dxc_thread_priority_before = THREAD_PRIORITY_ERROR_RETURN;
        }
        return 0;
    case WM_IME_STARTCOMPOSITION:
        dxc_ime_composing = 1;
        dxc_pending_high_surrogate = 0;
        dxc_position_ime(window);
        return 0;
    case WM_IME_COMPOSITION: {
        // The cancel dxc_native_ime_reset asked for, coming back: the field kept the text,
        // so nothing is said about it.
        if (dxc_ime_cancelling) return 0;
        if (lparam == 0) {
            dxc_push_text(DXC_EVENT_TEXT_COMPOSE, NULL, 0);
            return 0;
        }
        HIMC context = ImmGetContext(window);
        if (context != NULL) {
            // A result replaces the old marked text; a new composition may follow it
            // in this same message. Preserve that order in the event queue.
            if (lparam & GCS_RESULTSTR) {
                dxc_read_ime_text(context, GCS_RESULTSTR, DXC_EVENT_TEXT_COMMIT);
                dxc_ime_composing = 0;
            }
            if (lparam & GCS_COMPSTR) {
                dxc_read_ime_text(context, GCS_COMPSTR, DXC_EVENT_TEXT_COMPOSE);
                dxc_ime_composing = 1;
            }
            ImmReleaseContext(window, context);
        }
        return 0;
    }
    case WM_IME_ENDCOMPOSITION:
        if (dxc_ime_composing) dxc_push_text(DXC_EVENT_TEXT_COMPOSE, NULL, 0);
        dxc_ime_composing = 0;
        return 0;
    case WM_IME_CHAR:
        // The result already arrived through GCS_RESULTSTR. The default handler can
        // turn this into WM_CHAR, which would commit it a second time.
        return 0;
    case WM_CHAR: {
        if (dxc_ime_composing) return 0;
        uint16_t unit = (uint16_t)wparam;
        if (unit >= 0xd800 && unit <= 0xdbff) {
            dxc_pending_high_surrogate = unit;
            return 0;
        }
        uint16_t text[2];
        size_t units = 1;
        if (unit >= 0xdc00 && unit <= 0xdfff && dxc_pending_high_surrogate != 0) {
            text[0] = dxc_pending_high_surrogate;
            text[1] = unit;
            units = 2;
        } else {
            text[0] = unit;
        }
        dxc_pending_high_surrogate = 0;
        if (unit >= 0x20 && unit != 0x7f) {
            dxc_push_text(DXC_EVENT_TEXT_COMMIT, text, units);
        }
        return 0;
    }
    case WM_DPICHANGED:
        fprintf(stderr, "compose-rust: resize-mode: dpi -> %u because WM_DPICHANGED\n",
                (unsigned)HIWORD(wparam));
        if (dxc_swapchain != NULL) {
            dxc_log_monitor(window, "WM_DPICHANGED");
        }
        // A per monitor aware window is not resized by the system when its DPI changes; it
        // is handed a rectangle and has to apply a size itself. The rectangle's size is
        // Windows' scaling of the old outer rectangle, made without knowing this window's
        // frame (the caption strip is client area, WM_NCCALCSIZE), and the client area inside
        // it came out a few pixels off the size in points (win32_dpi.h). The window is
        // given its kept size at the new DPI instead, at the rectangle's top left: that is
        // where Windows put the window, on the monitor whose DPI this is, and the corner a
        // change of scale leaves in place, as the X11 windows leave theirs. A maximised,
        // minimised or snapped window takes the rectangle as it is: it fills its place, and
        // gets its kept size back when it is restored. Applied with SetWindowPos either way,
        // so the new size goes through WM_NCCALCSIZE and WM_SIZE and is drawn, presented and
        // flushed there like any other resize outside a drag.
        {
            const RECT *suggested = (const RECT *)lparam;
            UINT to = (UINT)HIWORD(wparam);
            RECT before_outer = {0, 0, 0, 0};
            RECT before_client = {0, 0, 0, 0};
            GetWindowRect(window, &before_outer);
            GetClientRect(window, &before_client);
            fprintf(stderr,
                    "compose-rust: dpi-size: WM_DPICHANGED %u -> %u dpi: outer %ldx%ld client %ldx%ld, "
                    "suggested %ldx%ld at %ld,%ld%s\n",
                    (unsigned)dxc_w->layout_dpi, (unsigned)to,
                    (long)(before_outer.right - before_outer.left), (long)(before_outer.bottom - before_outer.top),
                    (long)before_client.right, (long)before_client.bottom,
                    (long)(suggested->right - suggested->left), (long)(suggested->bottom - suggested->top),
                    (long)suggested->left, (long)suggested->top,
                    IsZoomed(window) ? " (maximised)" : IsIconic(window) ? " (minimised)"
                    : dxc_window_arranged(window) ? " (snapped)" : "");
            int resizes = to != 0 && dxc_dpi_resizes_now(window);
            if (to != 0) dxc_w->layout_dpi = to;
            if (resizes) {
                dxc_apply_dpi_size(window, suggested->left, suggested->top, to, "the DPI changed");
            } else {
                if (dxc_w->dpi_size.dpi != 0) dxc_w->dpi_restore_pending = 1;
                if (!IsIconic(window)) {
                    dxc_w->in_dpi_change = 1;
                    SetWindowPos(window, NULL, suggested->left, suggested->top,
                                 suggested->right - suggested->left, suggested->bottom - suggested->top,
                                 SWP_NOZORDER | SWP_NOACTIVATE);
                    dxc_w->in_dpi_change = 0;
                }
            }
        }
        return 0;
    case WM_GETDPISCALEDSIZE:
        // Sent before WM_DPICHANGED (Windows 10 1703 and later) to ask what outer size the
        // window will have at the new DPI. Answered with the size WM_DPICHANGED will apply,
        // so the rectangle Windows then suggests, and where it puts it when a dragged window
        // crosses onto another monitor, is for the window's real size.
        if (lparam != 0 && wparam != 0 && dxc_dpi_resizes_now(window)) {
            int32_t width = 0, height = 0, outer_width = 0, outer_height = 0;
            dxc_dpi_size_client(dxc_w->dpi_size, (UINT)wparam, dxc_options.min_width, dxc_options.min_height,
                                &width, &height);
            dxc_outer_for_client(width, height, (UINT)wparam, &outer_width, &outer_height);
            SIZE *size = (SIZE *)lparam;
            size->cx = outer_width;
            size->cy = outer_height;
            return TRUE;
        }
        break;
    case DXC_WM_DPI_RESTORE:
        // Restored after the DPI changed while the window was maximised, minimised or
        // snapped: given its kept size at the DPI it now has, its top left where it is.
        if (dxc_dpi_resizes_now(window) && dxc_w->layout_dpi != 0) {
            int32_t width = 0, height = 0;
            dxc_dpi_size_client(dxc_w->dpi_size, dxc_w->layout_dpi, dxc_options.min_width,
                                dxc_options.min_height, &width, &height);
            RECT outer, client;
            if (GetWindowRect(window, &outer) && GetClientRect(window, &client) &&
                (client.right - client.left != width || client.bottom - client.top != height)) {
                dxc_apply_dpi_size(window, outer.left, outer.top, dxc_w->layout_dpi,
                                   "restored after the DPI changed");
            }
        }
        return 0;
    case WM_DISPLAYCHANGE:
        if (dxc_swapchain != NULL) {
            dxc_log_monitor(window, "WM_DISPLAYCHANGE");
        }
        break;
    case WM_ACTIVATE:
        // The window gained or lost the keyboard: what the application is told as focus. The
        // default handler still runs (it gives the window the focus).
        dxc_w->active = LOWORD(wparam) != WA_INACTIVE;
        dxc_w->activation_pending = 1;
        // With native views in the window, the slot that had the keyboard gets it back once
        // the default handler has given it to the window, and the change is reported.
        if (dxc_w->native_views.count > 0) {
            LRESULT answer = DefWindowProcW(window, message, wparam, lparam);
            dxc_nv_activated(dxc_w, window, dxc_w->active);
            return answer;
        }
        break;
    case WM_WINDOWPOSCHANGING:
        // The composition route's hold of a shrinking drag step (DXC_DCOMP_SHRINK_WAIT=hold);
        // otherwise, and always on the GDI route, the default handler alone.
        if (dxc_on_comp && dxc_comp_wait_mode == DXC_COMP_WAIT_HOLD && dxc_sizing.dragging) {
            LRESULT answer = DefWindowProcW(window, message, wparam, lparam);
            dxc_comp_hold_step(window, (WINDOWPOS *)lparam);
            return answer;
        }
        break;
    case WM_SIZING:
        dxc_drag_sizing_edge = wparam;
        if (dxc_on_comp) dxc_comp_sizing_edge = wparam;
        break;
    case WM_TIMER:
        if (wparam == DXC_TIMER_COMP_HOLD) {
            dxc_comp_hold_tick(window);
            return 0;
        }
        break;
    case WM_WINDOWPOSCHANGED:
        // Moved or resized, by the user or by SetWindowPos. The default handler turns it into
        // WM_MOVE and WM_SIZE.
        dxc_w->moved_pending = 1;
        dxc_state_marks[DXC_STATE_CHANGED] = dxc_now_ms();
        if (dxc_comp_hold_active) {
            // A size held to its frame: its WM_SIZE draws nothing, and the next change is drawn.
            LRESULT answer = DefWindowProcW(window, message, wparam, lparam);
            dxc_comp_hold_active = 0;
            return answer;
        }
        break;
    case WM_MOVE:
        dxc_w->moved_pending = 1;
        if (dxc_swapchain != NULL &&
            MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST) != dxc_last_monitor) {
            dxc_log_monitor(window, "the window moved to another monitor");
        }
        break;
    case WM_SIZE:
        dxc_w->moved_pending = 1;
        dxc_state_marks[DXC_STATE_SIZE] = dxc_now_ms();
        if (wparam != dxc_last_size_kind &&
            (wparam == SIZE_MAXIMIZED || wparam == SIZE_MINIMIZED || wparam == SIZE_RESTORED)) {
            dxc_mode("window", dxc_size_kind_name(dxc_last_size_kind), dxc_size_kind_name(wparam),
                     "WM_SIZE");
            dxc_last_size_kind = wparam;
        }
        // The size the window keeps across a change of DPI: whatever size it is restored to,
        // by the application or by the user, except the one a DPI change is applying and the
        // one Windows gives a snapped window. The first restore after a DPI change that came
        // while the window was maximised, minimised or snapped asks for the kept size instead,
        // once this resize is over.
        if (wparam == SIZE_RESTORED && !dxc_w->in_dpi_change && dxc_w->layout_dpi != 0 &&
            !dxc_window_arranged(window)) {
            if (dxc_w->dpi_restore_pending) {
                dxc_w->dpi_restore_pending = 0;
                PostMessageW(window, DXC_WM_DPI_RESTORE, 0, 0);
            } else {
                dxc_w->dpi_size = dxc_dpi_size_note(dxc_w->dpi_size, (int32_t)LOWORD(lparam),
                                                    (int32_t)HIWORD(lparam), dxc_w->layout_dpi);
            }
        }
        // Written down, then drawn: the frame refits the swapchain where it begins, never
        // while a buffer is being drawn into. Nothing to do while minimised: the client
        // area is empty and a swapchain cannot have a zero dimension.
        if (dxc_swapchain != NULL && wparam != SIZE_MINIMIZED) {
            dxc_resize_note(&dxc_sizing, (int32_t)LOWORD(lparam), (int32_t)HIWORD(lparam));
            // Drawn here, inside the SetWindowPos that resized the window, whether or not
            // a drag is on: during one there is no other place that runs, and outside one
            // the same ordering keeps the frame ahead of the next size. Not a size held to the
            // frame already committed for it (dxc_comp_hold_step).
            if (!dxc_comp_hold_active) {
                dxc_draw_resize((int32_t)LOWORD(lparam), (int32_t)HIWORD(lparam));
            }
        }
        if (dxc_trace_state > 0) dxc_trace_event(DXC_TRACE_WM_SIZE_RET, (int)LOWORD(lparam), (int)HIWORD(lparam));
        if (dxc_external_drag && dxc_sizing.dragging) dxc_external_drag_steps++;
        return 0;
    case WM_PAINT: {
        // Validated so the window stops asking. Outside a drag the frame is drawn again:
        // the window's redirection surface can lose what was presented into it (restored
        // from minimised, uncovered, a display change), and the frame loop draws only
        // when the scene changed, so without this the window would stay black until it
        // did. During a drag WM_SIZE draws, and a second frame here would present the
        // same size twice.
        //
        // On the composition route there is no redirection surface to lose: the visual
        // keeps the last frame presented, so the paint is only validated, once a frame has
        // been presented at all and no size is waiting for one (a refit refused, asked for
        // again through this message).
        PAINTSTRUCT paint;
        BeginPaint(window, &paint);
        EndPaint(window, &paint);
        int comp_shown = dxc_on_comp && dxc_comp_presented && dxc_sizing.wanted_width <= 0;
        if (!dxc_resizing && !dxc_sizing.dragging && !comp_shown) {
            dxc_draw_one_frame();
        }
        return 0;
    }
    case WM_ERASEBKGND:
        // Answered so the window is never painted white between frames. Every pixel of
        // the client area comes from the swapchain.
        return 1;
    case WM_SETTINGCHANGE:
        // Any settings broadcast may be the Text size slider, so look rather than parse lparam.
        dxc_check_text_scale();
        // A window that follows the system's scheme follows it when the user changes it.
        if (dxc_w->scheme_applied && dxc_w->color_scheme == DXC_SCHEME_SYSTEM &&
            dxc_scheme_is_theme_change((const uint16_t *)lparam)) {
            dxc_apply_color_scheme(window);
        }
        break;
    case WM_CLOSE:
        // The application decides when a window goes where it has asked to be told: it hears
        // the request, and closes the window itself or leaves it open. A window nobody asked
        // that of closes, as it always has.
        if (dxc_w->close_deferred) {
            dxc_w->close_requested = 1;
            return 0;
        }
        DestroyWindow(window);
        return 0;
    case WM_DESTROY:
        // The native views are the caller's: taken out before the window's children are
        // destroyed with it, which happens after this message (every child still exists
        // while it is handled).
        dxc_nv_detach_all(dxc_w);
        RevokeDragDrop(window);
        if (dxc_root_provider != NULL && dxc_root_provider->window == window) {
            dxc_release_children(dxc_root_provider);
            dxc_root_provider->window = NULL;
        }
        dxc_drop_accessibility_arguments(dxc_w);
        dxc_a11y_count = 0;
        dxc_a11y_dirty = 0;
        dxc_a11y_update_posted = 0;
        dxc_window = NULL;
        dxc_window_gone = 1;
        dxc_w->destroyed = 1;
        dxc_w->following = 0;
        // Destroying a window ends that window and nothing else: whether the process quits
        // is the application's to say (dxc_native_post_quit), as it may still have windows.
        dxc_uncount_window(dxc_w);
        return 0;
    case DXC_WM_GPU_RETURN:
        dxc_gpu_return_posted = 0;
        if (dxc_cpu_mode && !dxc_sizing.dragging) {
            RECT client;
            GetClientRect(window, &client);
            dxc_switch_frames(0, client.right - client.left, client.bottom - client.top,
                              "the size stopped changing");
            dxc_return_to_gpu(window, client.right - client.left, client.bottom - client.top);
        }
        return 0;
    case DXC_WM_ACCESSIBILITY_UPDATE:
        dxc_a11y_update_posted = 0;
        {
            DxcRootProvider *root = dxc_ensure_root_provider(window);
            if (root == NULL) return 0;
            dxc_publish_accessibility(root, 1);
            UiaRaiseStructureChangedEvent(
                (IRawElementProviderSimple *)&root->simple_vtbl,
                StructureChangeType_ChildrenInvalidated, NULL, 0);
        }
        return 0;
    case WM_GETOBJECT:
        // A reader is asking what is in the window. The answer is our root
        // provider, which holds whatever the scene last pushed.
        if ((LONG)lparam == UiaRootObjectId) {
            DxcRootProvider *root = dxc_ensure_root_provider(window);
            if (root != NULL) {
                // A push whose update is already posted is left for it, so what changed is
                // announced when it arrives rather than taken in silently here.
                if (!dxc_a11y_update_posted) dxc_publish_accessibility(root, 0);
                return UiaReturnRawElementProvider(
                    window, wparam, lparam,
                    (IRawElementProviderSimple *)&root->simple_vtbl);
            }
        }
        break;
    default:
        break;
    }
    return DefWindowProcW(window, message, wparam, lparam);
}

/**
 * Every window of the class lands here. The state record of the window the message is for
 * is found from the handle, becomes the current one for the length of the message, and is
 * put back after, so a message handled inside another's (a nested loop, a draw callback)
 * leaves the outer one as it found it.
 */
static LRESULT CALLBACK dxc_native_window_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    if (message == WM_NCCREATE) {
        const CREATESTRUCTW *created = (const CREATESTRUCTW *)lparam;
        if (created != NULL && created->lpCreateParams != NULL) {
            SetWindowLongPtrW(window, GWLP_USERDATA, (LONG_PTR)created->lpCreateParams);
        }
    }
    DxcWin *win = (DxcWin *)GetWindowLongPtrW(window, GWLP_USERDATA);
    // WM_GETMINMAXINFO comes before WM_NCCREATE, from inside CreateWindowEx, for the window
    // being made.
    if (win == NULL) win = dxc_creating;
    if (win == NULL) return DefWindowProcW(window, message, wparam, lparam);
    DxcWin *outer = dxc_w;
    dxc_w = win;
    LRESULT result = dxc_window_message(window, message, wparam, lparam);
    dxc_w = outer;
    return result;
}

/**
 * Hands Windows the messages it has been holding.
 *
 * Here rather than in a loop of its own because Windows delivers messages to the thread
 * that made the window, and that is the thread the renderer draws from: a pump anywhere
 * else would be a pump the window never hears. Run when the queue has nothing left, which
 * is twice a frame: once to fill it and once to find it empty.
 */
static void dxc_pump_messages(void) {
    MSG message;
    while (PeekMessageW(&message, NULL, 0, 0, PM_REMOVE)) {
        if (message.message == WM_QUIT) {
            // PostQuitMessage from the application: written down for it to read, not acted on.
            dxc_quit_seen = 1;
            dxc_quit_code = (int32_t)message.wParam;
            continue;
        }
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
}

/**
 * Lets the window answer for itself for a moment.
 *
 * Called once a frame. The thread that draws is the thread Windows delivers to, so a loop
 * that never gave it a turn would be a window that heard nothing.
 *
 * The wait is for something to arrive rather than for the clock. A frame that drew has
 * already waited for the screen inside `Present`, and the caller asks for no wait at all
 * in that case; a window with nothing happening is asked to rest for a frame's length,
 * and comes back the moment anything is pressed.
 */
void dxc_native_pump(double seconds) {
    // With several windows the caller pumps once a turn on any one of them, and this serves
    // the process's queue, so a second pump in the same turn finds it empty and must not
    // wait for a frame again.
    if ((dxc_window != NULL || dxc_table.count > 0) && seconds > 0.0 &&
        dxc_pump_waits(dxc_table.count, dxc_micros_now(), dxc_last_pump_end_micros,
                       DXC_PUMP_QUIET_MICROS)) {
        DWORD wait = (DWORD)(seconds * 1000.0 + 0.5);
        if (wait > 0) {
            // Returns at once where something is already waiting, which is what the last
            // flag asks for. Without it a message that arrived before this call would be
            // paid for with a whole frame of sleeping.
            MsgWaitForMultipleObjectsEx(0, NULL, wait, QS_ALLINPUT, MWMO_INPUTAVAILABLE);
        }
    }
    dxc_pump_messages();
    dxc_follow_turn();
    // Where the keyboard went in this turn, and what the native views were asked for with no
    // frame drawn since.
    dxc_nv_turn();
    dxc_last_pump_end_micros = dxc_micros_now();
}

/** True once the reader has closed the window. */
int32_t dxc_native_window_closed(void) {
    return dxc_window_gone ? 1 : 0;
}

/**
 * The menu bar this platform does not have.
 *
 * Named because one piece of Kotlin drives both desktops and asks for this by name on
 * each. macOS keeps its application menu outside the window, and the shortcuts a reader
 * expects there do nothing without it. Windows keeps nothing outside the window: closing
 * is alt with F4 and the system menu, which the default handler already answers, and the
 * editing shortcuts belong to whatever holds focus, which is the scene.
 */
void dxc_native_install_menu(const char *application_name) {
    (void)application_name;
}


/** The caption strip is fixed on this platform and Kotlin knows its height, so nothing is measured. */
void dxc_native_window_caption(void *view_pointer, float *height, float *buttons_width) {
    (void)view_pointer;
    *height = 0;
    *buttons_width = 0;
}

/**
 * Does with the window what a button of the application's own caption asks: 0 minimises,
 * 1 maximises or restores, 2 closes.
 */
void dxc_native_window_action(int32_t action) {
    if (dxc_window == NULL) {
        return;
    }
    switch (action) {
    case 0:
        ShowWindow(dxc_window, SW_MINIMIZE);
        break;
    case 1:
        ShowWindow(dxc_window, IsZoomed(dxc_window) ? SW_RESTORE : SW_MAXIMIZE);
        break;
    case 2:
        PostMessageW(dxc_window, WM_CLOSE, 0, 0);
        break;
    default:
        break;
    }
}

/**
 * Hands the press the scene is holding to the system's own move ([edge] 0) or resize loop,
 * as if it had landed on the caption or that edge: the capture is let go so the system can
 * take the pointer, and a non-client button-down with the edge's hit test code starts the
 * loop, so snapping, the keyboard and multiple monitors all behave as they do for any window.
 * Where the caption is the system's the system already does this, so this is reached from a
 * strip the application owns, or from a node it made a drag region.
 *
 * The window is the one holding the press: the one with the capture, or else the active one,
 * so a second window of the application moves itself and not the first.
 */
void dxc_native_window_begin_drag(int32_t edge) {
    int32_t code = dxc_drag_hit_code(edge);
    if (code < 0) {
        return;
    }
    HWND target = GetCapture();
    if (target == NULL) {
        target = GetActiveWindow();
    }
    if (target == NULL) {
        target = dxc_window;
    }
    if (target == NULL) {
        return;
    }
    // The root window, not a child surface the press may have been captured on.
    HWND root = GetAncestor(target, GA_ROOT);
    if (root != NULL) {
        target = root;
    }
    POINT point;
    if (!GetCursorPos(&point)) {
        return;
    }
    ReleaseCapture();
    SendMessageW(target, WM_NCLBUTTONDOWN, (WPARAM)code, MAKELPARAM(point.x, point.y));
}

/**
 * Whether the application owns the caption strip of the windows opened after this
 * (`Chrome::FullSize`): its empty part is then client area, and the scene moves the window
 * from it through dxc_native_window_begin_drag. 0, the default, keeps the system's caption.
 * Called after dxc_native_window_configure, which sets it back to 0 for each window.
 */
void dxc_native_window_configure_app_caption(int32_t enabled) {
    dxc_next_options.app_caption = enabled ? 1 : 0;
}

/**
 * How tall the caption strip of the windows opened after this is, in device independent
 * pixels (FR-19.7): the height the scene draws its caption at, so the hit test answers for
 * the same strip. A Normal title bar is taller than Simple's; the renderer says how much,
 * because its design system decides. 0 or less is DXC_CAPTION_HEIGHT_DIP. Called after
 * dxc_native_window_configure, which sets it back to 0 for each window.
 */
void dxc_native_window_configure_caption_height(int32_t dip) {
    dxc_next_options.caption_dip = dip > 0 ? dip : 0;
}

/**
 * Gives the window the picture it named, from its pixels: eight bits each of red, green,
 * blue and alpha, with the colour already multiplied by the alpha, row after row.
 */
void dxc_native_set_icon(const uint8_t *rgba, int32_t width, int32_t height) {
    if (dxc_window == NULL || rgba == NULL || width <= 0 || height <= 0) {
        return;
    }
    BITMAPV5HEADER header;
    memset(&header, 0, sizeof header);
    header.bV5Size = sizeof header;
    header.bV5Width = width;
    // Negative: the rows are top first.
    header.bV5Height = -height;
    header.bV5Planes = 1;
    header.bV5BitCount = 32;
    header.bV5Compression = BI_BITFIELDS;
    header.bV5RedMask = 0x00FF0000;
    header.bV5GreenMask = 0x0000FF00;
    header.bV5BlueMask = 0x000000FF;
    header.bV5AlphaMask = 0xFF000000;
    void *bits = NULL;
    HDC screen = GetDC(NULL);
    HBITMAP color = CreateDIBSection(screen, (BITMAPINFO *)&header, DIB_RGB_COLORS, &bits, NULL, 0);
    ReleaseDC(NULL, screen);
    if (color == NULL || bits == NULL) {
        if (color != NULL) DeleteObject(color);
        return;
    }
    uint8_t *out = (uint8_t *)bits;
    for (int64_t index = 0; index < (int64_t)width * height; index++) {
        uint8_t r = rgba[index * 4 + 0];
        uint8_t g = rgba[index * 4 + 1];
        uint8_t b = rgba[index * 4 + 2];
        uint8_t a = rgba[index * 4 + 3];
        // An icon's alpha is straight, so the colour is divided back out.
        if (a != 0 && a != 255) {
            r = (uint8_t)((r * 255 + a / 2) / a);
            g = (uint8_t)((g * 255 + a / 2) / a);
            b = (uint8_t)((b * 255 + a / 2) / a);
        }
        out[index * 4 + 0] = b;
        out[index * 4 + 1] = g;
        out[index * 4 + 2] = r;
        out[index * 4 + 3] = a;
    }
    HBITMAP mask = CreateBitmap(width, height, 1, 1, NULL);
    ICONINFO info;
    memset(&info, 0, sizeof info);
    info.fIcon = TRUE;
    info.hbmMask = mask;
    info.hbmColor = color;
    HICON icon = CreateIconIndirect(&info);
    DeleteObject(color);
    DeleteObject(mask);
    if (icon != NULL) {
        SendMessageW(dxc_window, WM_SETICON, ICON_BIG, (LPARAM)icon);
        SendMessageW(dxc_window, WM_SETICON, ICON_SMALL, (LPARAM)icon);
    }
}

/** What is on the clipboard as text, copied into [out] as UTF-8, and its length. */
int32_t dxc_native_clipboard_read(char *out, int32_t capacity) {
    if (!OpenClipboard(dxc_window)) {
        return 0;
    }
    int32_t length = 0;
    HANDLE data = GetClipboardData(CF_UNICODETEXT);
    if (data != NULL) {
        const wchar_t *wide = (const wchar_t *)GlobalLock(data);
        if (wide != NULL) {
            int written = WideCharToMultiByte(CP_UTF8, 0, wide, -1, out, capacity, NULL, NULL);
            // The count includes the terminator, and is zero where the text did not fit.
            if (written > 0) {
                length = written - 1;
            }
            GlobalUnlock(data);
        }
    }
    CloseClipboard();
    return length;
}

/** Replaces the clipboard's contents with [text], which is UTF-8. */
void dxc_native_clipboard_write(const char *text) {
    int units = MultiByteToWideChar(CP_UTF8, 0, text, -1, NULL, 0);
    if (units <= 0 || !OpenClipboard(dxc_window)) {
        return;
    }
    HGLOBAL memory = GlobalAlloc(GMEM_MOVEABLE, (SIZE_T)units * sizeof(wchar_t));
    if (memory != NULL) {
        wchar_t *wide = (wchar_t *)GlobalLock(memory);
        if (wide != NULL) {
            MultiByteToWideChar(CP_UTF8, 0, text, -1, wide, units);
            GlobalUnlock(memory);
            EmptyClipboard();
            // The clipboard owns the memory from here on.
            if (SetClipboardData(CF_UNICODETEXT, memory) == NULL) {
                GlobalFree(memory);
            }
        } else {
            GlobalFree(memory);
        }
    }
    CloseClipboard();
}

/*
 * Files dragged over the window, through OLE, which is what says where they are and when
 * they leave. WM_DROPFILES says only that they were dropped.
 */

/** The paths of the files last dragged over the window, NUL between them, and the length. */
int32_t dxc_native_dropped_paths(char *out, int32_t capacity) {
    if (dxc_dropped_length <= 0 || dxc_dropped_length > capacity) {
        return 0;
    }
    memcpy(out, dxc_dropped_paths, (size_t)dxc_dropped_length);
    return dxc_dropped_length;
}

static FORMATETC dxc_hdrop_format(void) {
    FORMATETC format;
    format.cfFormat = CF_HDROP;
    format.ptd = NULL;
    format.dwAspect = DVASPECT_CONTENT;
    format.lindex = -1;
    format.tymed = TYMED_HGLOBAL;
    return format;
}

static void dxc_read_dragged_files(IDataObject *data) {
    dxc_dropped_length = 0;
    FORMATETC format = dxc_hdrop_format();
    STGMEDIUM medium;
    if (FAILED(IDataObject_GetData(data, &format, &medium))) {
        return;
    }
    HDROP drop = (HDROP)medium.hGlobal;
    UINT count = DragQueryFileW(drop, 0xFFFFFFFFu, NULL, 0);
    int32_t used = 0;
    for (UINT index = 0; index < count; index++) {
        wchar_t wide[MAX_PATH * 4];
        UINT units = DragQueryFileW(drop, index, wide, (UINT)(sizeof wide / sizeof *wide));
        if (units == 0) continue;
        // A name with an unpaired surrogate has no UTF-8 form. The file is left out and the
        // others are kept, instead of being handed on with a replacement character that
        // names a file that does not exist.
        int bytes = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, wide, (int)units, NULL, 0, NULL, NULL);
        // Room for the separator as well, and a path that does not fit is left out rather
        // than cut in half.
        if (bytes <= 0 || used + bytes + 1 > DXC_DROPPED_BYTES) continue;
        if (used > 0) dxc_dropped_paths[used++] = '\0';
        WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, wide, (int)units, dxc_dropped_paths + used, bytes, NULL, NULL);
        used += bytes;
    }
    dxc_dropped_length = used;
    ReleaseStgMedium(&medium);
}

static void dxc_push_drag(int32_t kind, POINTL where) {
    POINT point = {where.x, where.y};
    if (dxc_window != NULL) {
        ScreenToClient(dxc_window, &point);
    }
    struct dxc_event record;
    memset(&record, 0, sizeof record);
    record.kind = kind;
    record.x = (float)point.x;
    record.y = (float)point.y;
    dxc_push_event(record);
}

static HRESULT STDMETHODCALLTYPE dxc_drop_query(IDropTarget *self, REFIID id, void **out) {
    if (IsEqualIID(id, &IID_IUnknown) || IsEqualIID(id, &IID_IDropTarget)) {
        *out = self;
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static ULONG STDMETHODCALLTYPE dxc_drop_add_ref(IDropTarget *self) { (void)self; return 1; }
static ULONG STDMETHODCALLTYPE dxc_drop_release(IDropTarget *self) { (void)self; return 1; }

static HRESULT STDMETHODCALLTYPE dxc_drop_enter_for(
    IDropTarget *self, IDataObject *data, DWORD keys, POINTL where, DWORD *effect) {
    (void)self;
    (void)keys;
    FORMATETC format = dxc_hdrop_format();
    dxc_drag_has_files = SUCCEEDED(IDataObject_QueryGetData(data, &format));
    if (!dxc_drag_has_files) {
        *effect = DROPEFFECT_NONE;
        return S_OK;
    }
    dxc_read_dragged_files(data);
    dxc_push_drag(DXC_EVENT_FILES_ENTERED, where);
    *effect = DROPEFFECT_COPY;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_drop_over_for(
    IDropTarget *self, DWORD keys, POINTL where, DWORD *effect) {
    (void)self;
    (void)keys;
    if (!dxc_drag_has_files) {
        *effect = DROPEFFECT_NONE;
        return S_OK;
    }
    dxc_push_drag(DXC_EVENT_FILES_ENTERED, where);
    *effect = DROPEFFECT_COPY;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_drop_leave_for(IDropTarget *self) {
    (void)self;
    if (dxc_drag_has_files) {
        struct dxc_event record;
        memset(&record, 0, sizeof record);
        record.kind = DXC_EVENT_FILES_EXITED;
        dxc_push_event(record);
    }
    dxc_drag_has_files = 0;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_drop_drop_for(
    IDropTarget *self, IDataObject *data, DWORD keys, POINTL where, DWORD *effect) {
    (void)self;
    (void)keys;
    if (!dxc_drag_has_files) {
        *effect = DROPEFFECT_NONE;
        return S_OK;
    }
    dxc_read_dragged_files(data);
    dxc_push_drag(DXC_EVENT_FILES_DROPPED, where);
    dxc_drag_has_files = 0;
    *effect = DROPEFFECT_COPY;
    return S_OK;
}

static DxcWin *dxc_drop_window(IDropTarget *self) {
    return ((struct dxc_drop_target *)self)->win;
}

static HRESULT STDMETHODCALLTYPE dxc_drop_enter(
    IDropTarget *self, IDataObject *data, DWORD keys, POINTL where, DWORD *effect) {
    DxcWin *outer = dxc_w;
    dxc_w = dxc_drop_window(self);
    HRESULT result = dxc_drop_enter_for(self, data, keys, where, effect);
    dxc_w = outer;
    return result;
}

static HRESULT STDMETHODCALLTYPE dxc_drop_over(
    IDropTarget *self, DWORD keys, POINTL where, DWORD *effect) {
    DxcWin *outer = dxc_w;
    dxc_w = dxc_drop_window(self);
    HRESULT result = dxc_drop_over_for(self, keys, where, effect);
    dxc_w = outer;
    return result;
}

static HRESULT STDMETHODCALLTYPE dxc_drop_leave(IDropTarget *self) {
    DxcWin *outer = dxc_w;
    dxc_w = dxc_drop_window(self);
    HRESULT result = dxc_drop_leave_for(self);
    dxc_w = outer;
    return result;
}

static HRESULT STDMETHODCALLTYPE dxc_drop_drop(
    IDropTarget *self, IDataObject *data, DWORD keys, POINTL where, DWORD *effect) {
    DxcWin *outer = dxc_w;
    dxc_w = dxc_drop_window(self);
    HRESULT result = dxc_drop_drop_for(self, data, keys, where, effect);
    dxc_w = outer;
    return result;
}

static IDropTargetVtbl dxc_drop_vtable = {
    dxc_drop_query, dxc_drop_add_ref, dxc_drop_release,
    dxc_drop_enter, dxc_drop_over, dxc_drop_leave, dxc_drop_drop,
};

/** Lets the window be dropped on. Drag and drop is OLE's, which needs a single threaded apartment. */
static void dxc_accept_files(HWND window) {
    OleInitialize(NULL);
    dxc_w->drop.iface.lpVtbl = &dxc_drop_vtable;
    dxc_w->drop.win = dxc_w;
    RegisterDragDrop(window, &dxc_w->drop.iface);
}

/*
 * What is on the screen during a scripted resize, for DXC_CAPTURE_CHECK=1.
 *
 * After each step, once DWM has composed, the window's client area is copied from the
 * screen (the composed desktop, not the window's own surfaces) and compared with the frame
 * this window drew for that step at that size. A step fails when more than 2% of its pixels
 * differ by more than 24 levels: black, a fill, an old frame or a stretched one all do. When
 * the step was drawn on the GPU there is no CPU copy to compare with, and the step is judged
 * by black pixels alone. The strip of the window above the client area, the caption, is
 * counted for black as well.
 */

/*
 * Desktop Duplication: the frame DWM composed, as the display receives it, on the adapter
 * that drives the monitor. A screen-DC BitBlt reads GDI's idea of the screen, which need not
 * be what DWM put on it; this is what was put on it.
 */
static IDXGIOutputDuplication *dxc_dup;
static ID3D11Device *dxc_dup_device;
static ID3D11DeviceContext *dxc_dup_context;
static ID3D11Texture2D *dxc_dup_copy;
static RECT dxc_dup_desktop;
static int dxc_dup_state; // 0 untried, 1 working, -1 unavailable
// How stale the captured frame was.
static int dxc_dup_waits;
static long long dxc_dup_lag;

typedef HRESULT(WINAPI *dxc_d3d11_create_fn)(IDXGIAdapter *, D3D_DRIVER_TYPE, HMODULE, UINT,
                                              const D3D_FEATURE_LEVEL *, UINT, UINT, ID3D11Device **,
                                              D3D_FEATURE_LEVEL *, ID3D11DeviceContext **);

static void dxc_dup_start(HWND window) {
    dxc_dup_state = -1;
    HMONITOR monitor = MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST);
    HMODULE d3d11_library = LoadLibraryW(L"d3d11.dll");
    dxc_d3d11_create_fn create = d3d11_library == NULL ? NULL
        : (dxc_d3d11_create_fn)(void *)GetProcAddress(d3d11_library, "D3D11CreateDevice");
    IDXGIFactory1 *factory = NULL;
    if (create == NULL || FAILED(CreateDXGIFactory1(&IID_IDXGIFactory1, (void **)&factory))) {
        fprintf(stderr, "compose-rust: capture by desktop duplication unavailable (no factory)\n");
        return;
    }
    IDXGIAdapter1 *adapter = NULL;
    for (UINT a = 0; dxc_dup_state < 0 && IDXGIFactory1_EnumAdapters1(factory, a, &adapter) != DXGI_ERROR_NOT_FOUND; a++) {
        IDXGIOutput *output = NULL;
        for (UINT o = 0; dxc_dup_state < 0 && IDXGIAdapter1_EnumOutputs(adapter, o, &output) != DXGI_ERROR_NOT_FOUND; o++) {
            DXGI_OUTPUT_DESC described;
            IDXGIOutput1 *output1 = NULL;
            if (SUCCEEDED(IDXGIOutput_GetDesc(output, &described)) && described.Monitor == monitor &&
                SUCCEEDED(IDXGIOutput_QueryInterface(output, &IID_IDXGIOutput1, (void **)&output1))) {
                HRESULT made = create((IDXGIAdapter *)adapter, D3D_DRIVER_TYPE_UNKNOWN, NULL, 0, NULL, 0,
                                      D3D11_SDK_VERSION, &dxc_dup_device, NULL, &dxc_dup_context);
                HRESULT duplicated = FAILED(made) ? made
                    : IDXGIOutput1_DuplicateOutput(output1, (IUnknown *)dxc_dup_device, &dxc_dup);
                if (SUCCEEDED(duplicated)) {
                    dxc_dup_desktop = described.DesktopCoordinates;
                    dxc_dup_state = 1;
                } else {
                    fprintf(stderr, "compose-rust: capture by desktop duplication unavailable (0x%08lx)\n",
                            (unsigned long)duplicated);
                }
                IDXGIOutput1_Release(output1);
            }
            IDXGIOutput_Release(output);
        }
        IDXGIAdapter1_Release(adapter);
    }
    IDXGIFactory1_Release(factory);
    fprintf(stderr, "compose-rust: capture by %s\n",
            dxc_dup_state > 0 ? "desktop duplication (what DWM composed)" : "screen DC BitBlt");
}

/** Copies the latest composed desktop frame's region into pixels (BGRA, width per row). */
static int dxc_dup_read(int x, int y, int width, int height, uint32_t *pixels) {
    if (dxc_dup_state <= 0) return 0;
    DXGI_OUTDUPL_FRAME_INFO info;
    IDXGIResource *resource = NULL;
    // The frame DWM composed after this step. A timeout means nothing changed since the
    // last one, which the copy below still holds.
    // A frame DWM composed after the copy, not one from before it: frames are taken until
    // the latest one was presented after the copy (or nothing new comes for 200 ms).
    HRESULT acquired;
    dxc_dup_waits = 0;
    for (;;) {
        acquired = IDXGIOutputDuplication_AcquireNextFrame(dxc_dup, 200, &info, &resource);
        if (FAILED(acquired)) break;
        dxc_dup_lag = info.LastPresentTime.QuadPart - dxc_blit_time.QuadPart;
        if (info.LastPresentTime.QuadPart >= dxc_blit_time.QuadPart || dxc_dup_waits >= 8) break;
        dxc_dup_waits++;
        IDXGIResource_Release(resource);
        IDXGIOutputDuplication_ReleaseFrame(dxc_dup);
    }
    if (SUCCEEDED(acquired)) {
        ID3D11Texture2D *frame = NULL;
        if (SUCCEEDED(IDXGIResource_QueryInterface(resource, &IID_ID3D11Texture2D, (void **)&frame))) {
            if (dxc_dup_copy == NULL) {
                D3D11_TEXTURE2D_DESC description;
                ID3D11Texture2D_GetDesc(frame, &description);
                description.Usage = D3D11_USAGE_STAGING;
                description.BindFlags = 0;
                description.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
                description.MiscFlags = 0;
                ID3D11Device_CreateTexture2D(dxc_dup_device, &description, NULL, &dxc_dup_copy);
            }
            if (dxc_dup_copy != NULL) {
                ID3D11DeviceContext_CopyResource(dxc_dup_context, (ID3D11Resource *)dxc_dup_copy, (ID3D11Resource *)frame);
            }
            ID3D11Texture2D_Release(frame);
        }
        IDXGIResource_Release(resource);
        IDXGIOutputDuplication_ReleaseFrame(dxc_dup);
    } else if (acquired != DXGI_ERROR_WAIT_TIMEOUT) {
        fprintf(stderr, "compose-rust: desktop duplication failed (0x%08lx)\n", (unsigned long)acquired);
        dxc_dup_state = -1;
        return 0;
    }
    if (dxc_dup_copy == NULL) return 0;
    D3D11_MAPPED_SUBRESOURCE mapped;
    if (FAILED(ID3D11DeviceContext_Map(dxc_dup_context, (ID3D11Resource *)dxc_dup_copy, 0, D3D11_MAP_READ, 0, &mapped))) {
        return 0;
    }
    int left = x - dxc_dup_desktop.left;
    int top = y - dxc_dup_desktop.top;
    int desktop_width = dxc_dup_desktop.right - dxc_dup_desktop.left;
    int desktop_height = dxc_dup_desktop.bottom - dxc_dup_desktop.top;
    for (int row = 0; row < height; row++) {
        for (int column = 0; column < width; column++) {
            int sx = left + column;
            int sy = top + row;
            uint32_t value = 0;
            if (sx >= 0 && sy >= 0 && sx < desktop_width && sy < desktop_height) {
                value = ((uint32_t *)((uint8_t *)mapped.pData + (size_t)sy * mapped.RowPitch))[sx];
            }
            pixels[(size_t)row * width + column] = value;
        }
    }
    ID3D11DeviceContext_Unmap(dxc_dup_context, (ID3D11Resource *)dxc_dup_copy, 0);
    return 1;
}

static int dxc_capture(int x, int y, int width, int height, uint32_t **pixels, HBITMAP *bitmap, HDC *dc) {
    if (width <= 0 || height <= 0) return 0;
    HDC screen = GetDC(NULL);
    *dc = CreateCompatibleDC(screen);
    BITMAPINFO info;
    memset(&info, 0, sizeof info);
    info.bmiHeader.biSize = sizeof info.bmiHeader;
    info.bmiHeader.biWidth = width;
    info.bmiHeader.biHeight = -height;
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    info.bmiHeader.biCompression = BI_RGB;
    void *bits = NULL;
    *bitmap = CreateDIBSection(*dc, &info, DIB_RGB_COLORS, &bits, NULL, 0);
    if (*bitmap == NULL || bits == NULL) {
        DeleteDC(*dc);
        ReleaseDC(NULL, screen);
        return 0;
    }
    SelectObject(*dc, *bitmap);
    *pixels = (uint32_t *)bits;
    if (dxc_dup_state == 0 && dxc_window != NULL) dxc_dup_start(dxc_window);
    if (!dxc_dup_read(x, y, width, height, *pixels)) {
        BitBlt(*dc, 0, 0, width, height, screen, x, y, SRCCOPY | CAPTUREBLT);
        GdiFlush();
    }
    ReleaseDC(NULL, screen);
    return 1;
}

static int dxc_is_black(uint32_t pixel) {
    return (pixel & 0xFF) < 8 && ((pixel >> 8) & 0xFF) < 8 && ((pixel >> 16) & 0xFF) < 8;
}

static void dxc_capture_step(HWND window) {
    dxc_dwm_flush();
    RECT client;
    GetClientRect(window, &client);
    POINT origin = {0, 0};
    ClientToScreen(window, &origin);
    int width = client.right - client.left;
    int height = client.bottom - client.top;
    uint32_t *pixels = NULL;
    HBITMAP bitmap = NULL;
    HDC dc = NULL;
    if (!dxc_capture(origin.x, origin.y, width, height, &pixels, &bitmap, &dc)) return;
    // Only what is on a monitor is judged: a window that runs off the screen captures black
    // for the part nothing shows.
    MONITORINFO monitor;
    memset(&monitor, 0, sizeof monitor);
    monitor.cbSize = sizeof monitor;
    GetMonitorInfoW(MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST), &monitor);
    // The work area, not the whole monitor: the taskbar sits over whatever of the window
    // reaches below it.
    int visible_right = monitor.rcWork.right - origin.x;
    int visible_bottom = monitor.rcWork.bottom - origin.y;
    // With the caption given to the content, Windows still draws its frame's one-pixel top
    // border over the client's first row (the accent-coloured line every such window has).
    // That row is the system's frame, judged apart and reported, not the frame's content.
    int border_row = !dxc_options.system_chrome && !IsZoomed(window) ? 1 : 0;
    long border_differing = 0;
    int expected = dxc_dib_bits != NULL && dxc_raster_width == width && dxc_raster_height == height;
    long black = 0;
    long differing = 0;
    long total = 0;
    int first_row = -1;
    int last_row = -1;
    int rows = 0;
    int row_had = 0;
    uint32_t sample_seen = 0;
    uint32_t sample_drawn = 0;
    int sample_x = -1;
    for (int y = 0; y < height && y < visible_bottom; y++) {
        row_had = 0;
        for (int x = 0; x < width && x < visible_right; x++) {
            total++;
            uint32_t seen = pixels[(size_t)y * width + x];
            if (dxc_is_black(seen)) black++;
            if (expected) {
                uint32_t drawn = ((uint32_t *)dxc_dib_bits)[(size_t)y * dxc_dib_width + x];
                for (int shift = 0; shift < 24; shift += 8) {
                    int a = (int)((seen >> shift) & 0xFF);
                    int b = (int)((drawn >> shift) & 0xFF);
                    if (a - b > 24 || b - a > 24) {
                        if (y < border_row) { border_differing++; break; }
                        differing++;
                        if (!row_had) {
                            row_had = 1;
                            rows++;
                            if (first_row < 0) first_row = y;
                            last_row = y;
                        }
                        if (sample_x < 0) { sample_x = x; sample_seen = seen; sample_drawn = drawn; }
                        break;
                    }
                }
            }
        }
    }
    DeleteObject(bitmap);
    DeleteDC(dc);
    // The caption: the part of the window above the client area.
    RECT outer;
    GetWindowRect(window, &outer);
    long caption_black = 0;
    long caption_total = 0;
    int caption_height = origin.y - outer.top;
    if (caption_height > 0 &&
        dxc_capture(outer.left, outer.top, outer.right - outer.left, caption_height, &pixels, &bitmap, &dc)) {
        caption_total = (long)(outer.right - outer.left) * caption_height;
        for (long index = 0; index < caption_total; index++) {
            if (dxc_is_black(pixels[index])) caption_black++;
        }
        DeleteObject(bitmap);
        DeleteDC(dc);
    }
    // Not one pixel of the drawn frame may be missing: a row that is not the frame's is an
    // old or undrawn row.
    int failed = expected ? differing > 0 : black * 20 > total;
    DXGI_SWAP_CHAIN_DESC1 described;
    memset(&described, 0, sizeof described);
    if (dxc_swapchain != NULL) IDXGISwapChain1_GetDesc1(dxc_swapchain, &described);
    // The buffer has to match only when the swapchain is what is shown: during a CPU
    // resize its child is hidden and its buffer keeps the size it had.
    if (dxc_swapchain != NULL && dxc_surface != NULL && IsWindowVisible(dxc_surface) &&
        ((int)described.Width != width || (int)described.Height != height)) {
        failed = 1;
    }
    // On the composition route the surface is what is shown, and it has to be the window's size.
    if (dxc_on_comp && (dxc_comp_surface_width != width || dxc_comp_surface_height != height)) {
        failed = 1;
    }
    fprintf(stderr,
            "compose-rust: capture detail %dx%d buffer %ux%u rows_differing=%d first_row=%d last_row=%d "
            "frame_border_pixels=%ld frames_skipped=%d frame_minus_blit_qpc=%lld sample x=%d seen=%08x drawn=%08x\n",
            width, height, described.Width, described.Height, rows, first_row, last_row, border_differing,
            dxc_dup_waits, dxc_dup_lag, sample_x,
            (unsigned)sample_seen, (unsigned)sample_drawn);
    dxc_capture_steps++;
    if (failed) dxc_capture_failures++;
    fprintf(stderr,
            "compose-rust: capture step %dx%d %s black=%ld/%ld differing=%ld caption_black=%ld/%ld %s\n",
            width, height, expected ? "vs-cpu-frame" : "black-only", black, total, differing,
            caption_black, caption_total, failed ? "FAIL" : "ok");
}

/**
 * Hit-tests the window where a reader would press, for DXC_HITTEST_CHECK=1, and prints
 * what Windows answers against what a window with this caption should answer.
 */
void dxc_native_debug_hit_test(void *window_pointer) {
    HWND window = window_pointer != NULL ? (HWND)window_pointer : dxc_window;
    if (window == NULL) return;
    RECT outer;
    GetWindowRect(window, &outer);
    POINT origin = {0, 0};
    ClientToScreen(window, &origin);
    RECT client;
    GetClientRect(window, &client);
    UINT dpi = GetDpiForWindow(window);
    int caption_middle = dxc_options.system_chrome
        ? outer.top + (origin.y - outer.top) / 2 + GetSystemMetricsForDpi(SM_CYSIZEFRAME, dpi) / 2
        : outer.top + dxc_scaled(window, dxc_options.caption_dip > 0 ? dxc_options.caption_dip
                                                                     : DXC_CAPTION_HEIGHT_DIP) / 2;
    // The system's buttons are SM_CXSIZE wide; the ones the content draws are the
    // Windows 11 caption metrics this file lays the custom caption out with.
    int button = dxc_options.system_chrome ? GetSystemMetricsForDpi(SM_CXSIZE, dpi)
                                           : dxc_scaled(window, DXC_CAPTION_BUTTON_WIDTH_DIP);
    struct { const char *where; int x; int y; LRESULT expected; } points[] = {
        {"caption", (outer.left + outer.right) / 2, caption_middle, HTCAPTION},
        {"close", outer.right - button / 2 - 8, caption_middle, dxc_options.system_chrome ? HTCLOSE : HTCLIENT},
        {"maximise", outer.right - button * 3 / 2 - 8, caption_middle, HTMAXBUTTON},
        {"minimise", outer.right - button * 5 / 2 - 8, caption_middle, dxc_options.system_chrome ? HTMINBUTTON : HTCLIENT},
        {"client", origin.x + client.right / 2, origin.y + client.bottom / 2, HTCLIENT},
        {"left edge", outer.left + 2, (outer.top + outer.bottom) / 2, HTLEFT},
        {"right edge", outer.right - 3, (outer.top + outer.bottom) / 2, HTRIGHT},
        {"bottom edge", (outer.left + outer.right) / 2, outer.bottom - 3, HTBOTTOM},
        {"bottom-right", outer.right - 3, outer.bottom - 3, HTBOTTOMRIGHT},
        {"top edge", (outer.left + outer.right) / 2, outer.top + 2, HTTOP},
    };
    int failures = 0;
    for (size_t index = 0; index < sizeof points / sizeof *points; index++) {
        LRESULT got = SendMessageW(window, WM_NCHITTEST, 0, MAKELPARAM(points[index].x, points[index].y));
        int ok = got == points[index].expected;
        if (!ok) failures++;
        fprintf(stderr, "compose-rust: hit-test %s at %d,%d got %ld expected %ld %s\n", points[index].where,
                points[index].x, points[index].y, (long)got, (long)points[index].expected, ok ? "ok" : "FAIL");
    }
    fprintf(stderr, "compose-rust: hit-test verdict %s (%s caption)\n", failures == 0 ? "PASS" : "FAIL",
            dxc_options.system_chrome ? "system" : "custom");
}

/**
 * Takes the window through the sizes a drag would, for measuring. Used only when asked
 * for, by DXC_SYNTH.
 *
 * Sizes are client sizes in points, as on macOS. Each step is a SetWindowPos with the top
 * left held, between the two messages that bracket a real drag, so the frame for each
 * step comes through WM_NCCALCSIZE and WM_SIZE exactly as a dragged edge's does. Runs on
 * the window's own thread and returns when the last step has been taken.
 */
/*
 * A real drag of the right edge, for DXC_REAL_DRAG: mouse input sent from another thread, so
 * the window's own modal sizing loop (DefWindowProc) delivers WM_SIZE at its natural rate
 * while this thread pumps it. "fast" moves the mouse every millisecond, faster than any
 * display refreshes; otherwise every 16 ms. Each WM_SIZE of the drag is captured.
 */
static volatile LONG dxc_real_drag_started;

struct dxc_real_drag_plan {
    int x, y, travel, interval_ms;
};
static struct dxc_real_drag_plan dxc_real_drag;

/** One mouse event at an absolute screen position, as a real mouse would send it. */
static void dxc_send_mouse(DWORD flags, int x, int y) {
    INPUT input;
    memset(&input, 0, sizeof input);
    input.type = INPUT_MOUSE;
    int width = GetSystemMetrics(SM_CXVIRTUALSCREEN);
    int height = GetSystemMetrics(SM_CYVIRTUALSCREEN);
    int left = GetSystemMetrics(SM_XVIRTUALSCREEN);
    int top = GetSystemMetrics(SM_YVIRTUALSCREEN);
    input.mi.dx = (LONG)(((long long)(x - left) * 65535 + width / 2) / (width - 1));
    input.mi.dy = (LONG)(((long long)(y - top) * 65535 + height / 2) / (height - 1));
    input.mi.dwFlags = flags | MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK;
    UINT sent = SendInput(1, &input, sizeof input);
    if (dxc_trace_state > 0) dxc_trace_event(DXC_TRACE_SEND, x, (int)sent);
}

static DWORD WINAPI dxc_real_drag_thread(LPVOID unused) {
    (void)unused;
    Sleep(300);
    dxc_send_mouse(0, dxc_real_drag.x, dxc_real_drag.y);
    Sleep(100);
    dxc_send_mouse(MOUSEEVENTF_LEFTDOWN, dxc_real_drag.x, dxc_real_drag.y);
    Sleep(100);
    int step = dxc_real_drag.interval_ms <= 1 ? 2 : 8;
    for (int moved = step; moved <= dxc_real_drag.travel; moved += step) {
        dxc_send_mouse(0, dxc_real_drag.x + moved, dxc_real_drag.y);
        Sleep((DWORD)dxc_real_drag.interval_ms);
    }
    for (int moved = dxc_real_drag.travel - step; moved >= 0; moved -= step) {
        dxc_send_mouse(0, dxc_real_drag.x + moved, dxc_real_drag.y);
        Sleep((DWORD)dxc_real_drag.interval_ms);
    }
    Sleep(100);
    dxc_send_mouse(MOUSEEVENTF_LEFTUP, dxc_real_drag.x, dxc_real_drag.y);
    return 0;
}

void dxc_native_debug_resize(void *window_pointer, void *view_pointer, int32_t from_width,
                             int32_t from_height, int32_t to_width, int32_t to_height,
                             int32_t steps, int32_t pause_micros) {
    (void)view_pointer;
    HWND window = (HWND)window_pointer;
    if (window == NULL || steps <= 0) {
        return;
    }
    const char *real = getenv("DXC_REAL_DRAG");
    if (real != NULL && real[0] != '\0') {
        // Once: the drag runs out and back on its own, while the caller's loop pumps.
        if (InterlockedExchange(&dxc_real_drag_started, 1) != 0) return;
        if (strcmp(real, "external") == 0) {
            // Driven by another process (resize-probe): the window is only put where the
            // drag can run to the right of it, and the mouse comes from outside. Nothing here
            // captures or waits, so the sizing loop steps at its own rate.
            MONITORINFO work;
            memset(&work, 0, sizeof work);
            work.cbSize = sizeof work;
            GetMonitorInfoW(MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST), &work);
            SetWindowPos(window, NULL, work.rcWork.left, work.rcWork.top, 640, 480, SWP_NOZORDER);
            SetForegroundWindow(window);
            dxc_external_drag_steps = 0;
            dxc_external_drag = 1;
            fprintf(stderr, "compose-rust: real drag ready hwnd=%p pid=%lu tid=%lu\n", (void *)window,
                    (unsigned long)GetCurrentProcessId(), (unsigned long)GetWindowThreadProcessId(window, NULL));
            fflush(stderr);
            return;
        }
        MONITORINFO monitor;
        memset(&monitor, 0, sizeof monitor);
        monitor.cbSize = sizeof monitor;
        GetMonitorInfoW(MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST), &monitor);
        SetWindowPos(window, NULL, monitor.rcWork.left, monitor.rcWork.top, 640, 480,
                     SWP_NOZORDER);
        SetForegroundWindow(window);
        RECT outer;
        GetWindowRect(window, &outer);
        dxc_real_drag.x = outer.right - 3;
        dxc_real_drag.y = (outer.top + outer.bottom) / 2;
        dxc_real_drag.travel = (monitor.rcWork.right - outer.right) - 8;
        dxc_real_drag.interval_ms = strcmp(real, "fast") == 0 ? 1 : 16;
        dxc_real_drag_active = 1;
        fprintf(stderr, "compose-rust: real drag of the right edge by %d px, mouse every %d ms\n",
                dxc_real_drag.travel, dxc_real_drag.interval_ms);
        CloseHandle(CreateThread(NULL, 0, dxc_real_drag_thread, NULL, 0, NULL));
        return;
    }
    UINT dpi = GetDpiForWindow(window);
    if (dpi == 0) {
        dpi = USER_DEFAULT_SCREEN_DPI;
    }
    if (getenv("DXC_CAPTURE_CHECK") != NULL) {
        // At the top left of the work area, so the sizes the drag reaches stay on screen.
        MONITORINFO monitor;
        memset(&monitor, 0, sizeof monitor);
        monitor.cbSize = sizeof monitor;
        if (GetMonitorInfoW(MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST), &monitor)) {
            SetWindowPos(window, NULL, monitor.rcWork.left, monitor.rcWork.top, 0, 0,
                         SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
        }
    }
    SendMessageW(window, WM_ENTERSIZEMOVE, 0, 0);
    for (int32_t step = 1; step <= steps; step++) {
        double t = (double)step / steps;
        int points_width = (int)(from_width + (to_width - from_width) * t + 0.5);
        int points_height = (int)(from_height + (to_height - from_height) * t + 0.5);
        RECT outer;
        outer.left = 0;
        outer.top = 0;
        outer.right = MulDiv(points_width, (int)dpi, USER_DEFAULT_SCREEN_DPI);
        outer.bottom = MulDiv(points_height, (int)dpi, USER_DEFAULT_SCREEN_DPI);
        AdjustWindowRectExForDpi(&outer, dxc_window_style(), FALSE, 0, dpi);
        // Without the system caption the client area starts at the top of the window,
        // as where the window was first sized.
        int outer_height = dxc_options.system_chrome ? outer.bottom - outer.top : outer.bottom;
        if (dxc_trace_state > 0) dxc_trace_event(DXC_TRACE_SWP_CALL, outer.right - outer.left, outer_height);
        SetWindowPos(window, NULL, 0, 0, outer.right - outer.left, outer_height,
                     SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE);
        if (dxc_trace_state > 0) dxc_trace_event(DXC_TRACE_SWP_RET, outer.right - outer.left, outer_height);
        if (getenv("DXC_CAPTURE_CHECK") != NULL) {
            // DXC_CAPTURE_PACE=1: one more composition before looking, in this test hook
            // only, so a step is judged after DWM has had a full frame to show it.
            if (getenv("DXC_CAPTURE_PACE") != NULL) dxc_dwm_flush();
            dxc_capture_step(window);
        }
        if (pause_micros > 0) {
            Sleep((DWORD)((pause_micros + 999) / 1000));
        }
    }
    SendMessageW(window, WM_EXITSIZEMOVE, 0, 0);
    if (getenv("DXC_CAPTURE_CHECK") != NULL) {
        fprintf(stderr, "compose-rust: capture-check verdict %s (%d of %d steps failed)\n",
                dxc_capture_failures == 0 && dxc_capture_steps > 0 ? "PASS" : "FAIL",
                dxc_capture_failures, dxc_capture_steps);
    }
}

/**
 * Posts a key press, the characters it types and its release to the window's own queue,
 * for measuring. Used only when asked for, by DXC_SYNTH.
 *
 * The key code the caller passes is macOS's, which means nothing here; the virtual key
 * is looked up from the first character instead. The characters go as WM_CHAR, one per
 * UTF-16 unit, which is what TranslateMessage would have posted for a real press.
 */
void dxc_native_debug_key(void *window_pointer, int32_t key_code, const char *characters) {
    (void)key_code;
    HWND window = (HWND)window_pointer;
    if (window == NULL || characters == NULL) {
        return;
    }
    WCHAR text[16];
    int units = MultiByteToWideChar(CP_UTF8, 0, characters, -1, text,
                                    (int)(sizeof text / sizeof *text));
    if (units <= 1) {
        return;
    }
    units--;
    SHORT scanned = VkKeyScanW(text[0]);
    UINT key = scanned == -1 ? 0 : (UINT)(scanned & 0xff);
    if (key != 0) {
        PostMessageW(window, WM_KEYDOWN, key, 1);
    }
    for (int index = 0; index < units; index++) {
        PostMessageW(window, WM_CHAR, text[index], 1);
    }
    if (key != 0) {
        PostMessageW(window, WM_KEYUP, key, (LPARAM)0xC0000001);
    }
}

/**
 * Shows the system's context menu at the pointer and answers with the index of the entry
 * chosen, or -1 when it was dismissed. Returns when the menu closes.
 *
 * [items] is one line per entry, fields separated by a tab: the index, enabled (0 or 1)
 * and the label in UTF-8, the format the macOS window reads (packMenu in
 * NativeContextMenu.kt). Which entries are enabled is the caller's to say: Compose
 * already disables Paste when the clipboard holds no text.
 */
int32_t dxc_native_context_menu(void *window_pointer, const char *items) {
    HWND window = window_pointer != NULL ? (HWND)window_pointer : dxc_window;
    if (window == NULL || items == NULL) {
        return -1;
    }
    HMENU menu = CreatePopupMenu();
    if (menu == NULL) {
        return -1;
    }
    int entries = 0;
    const char *line = items;
    while (*line != '\0') {
        const char *end = strchr(line, '\n');
        size_t length = end != NULL ? (size_t)(end - line) : strlen(line);
        char field[512];
        if (length >= sizeof field) length = sizeof field - 1;
        memcpy(field, line, length);
        field[length] = '\0';
        char *first_tab = strchr(field, '\t');
        char *second_tab = first_tab != NULL ? strchr(first_tab + 1, '\t') : NULL;
        if (second_tab != NULL) {
            *first_tab = '\0';
            *second_tab = '\0';
            int index = atoi(field);
            int enabled = atoi(first_tab + 1) != 0;
            WCHAR label[256];
            if (MultiByteToWideChar(CP_UTF8, 0, second_tab + 1, -1, label,
                                    (int)(sizeof label / sizeof *label)) == 0) {
                label[0] = L'\0';
            }
            // Command ids start at 1, because TrackPopupMenuEx answers 0 for "nothing".
            AppendMenuW(menu, MF_STRING | (enabled ? MF_ENABLED : MF_GRAYED),
                        (UINT_PTR)(index + 1), label);
            entries++;
        }
        if (end == NULL) break;
        line = end + 1;
    }
    int32_t chosen = -1;
    if (entries > 0) {
        POINT pointer;
        GetCursorPos(&pointer);
        // Without the window in front the menu does not close when the reader clicks
        // elsewhere; the posted message lets it finish closing (KB135788).
        SetForegroundWindow(window);
        UINT command = (UINT)TrackPopupMenuEx(menu, TPM_RETURNCMD | TPM_RIGHTBUTTON | TPM_NONOTIFY,
                                              pointer.x, pointer.y, window, NULL);
        PostMessageW(window, WM_NULL, 0, 0);
        chosen = command == 0 ? -1 : (int32_t)command - 1;
    }
    DestroyMenu(menu);
    return chosen;
}

// The entry menu up now, which dxc_native_popup_menu_close takes down: TrackPopupMenuEx runs the
// menu's own loop on this thread, and a frame drawn meanwhile may remove the node it belongs to.
static HMENU dxc_popup_open;

/**
 * Puts an entry menu up as Windows' own (compose-rust FR-33.2) and answers when it closes.
 *
 * [lines] are common's encodePopupMenu lines; [icons] the pictures one after the other, premultiplied
 * RGBA, [icon_sizes] their width and height in pairs. The menu opens below the anchor ([x], [y],
 * [width], [height] in the window's client pixels), at its left edge, or its right edge for [rtl],
 * and keeps the anchor uncovered (TPMPARAMS.rcExclude). The keyboard, Narrator, placement at the
 * screen's edge and dismissal are Windows'. The chosen path ("2/0") is written to [out] and its
 * length answered; 0 is a menu closed with nothing chosen, -1 one that could not be put up.
 */
int32_t dxc_native_popup_menu(void *window_pointer, const char *lines, const uint8_t *icons,
                              const int32_t *icon_sizes, int32_t icon_count, float x, float y, float width,
                              float height, int32_t rtl, char *out, int32_t capacity) {
    HWND window = window_pointer != NULL ? (HWND)window_pointer : dxc_window;
    if (window == NULL || lines == NULL || out == NULL || capacity <= 0) return -1;
    out[0] = 0;
    struct dxc_popup_menu *menu = dxc_popup_menu_build(lines, icons, icon_sizes, icon_count);
    if (menu == NULL) return -1;
    if (GetMenuItemCount(menu->root) <= 0) {
        dxc_popup_menu_free(menu);
        return -1;
    }
    POINT top_left = {(LONG)x, (LONG)y};
    POINT bottom_right = {(LONG)(x + width), (LONG)(y + height)};
    ClientToScreen(window, &top_left);
    ClientToScreen(window, &bottom_right);
    TPMPARAMS params;
    memset(&params, 0, sizeof params);
    params.cbSize = sizeof params;
    params.rcExclude.left = top_left.x;
    params.rcExclude.top = top_left.y;
    params.rcExclude.right = bottom_right.x;
    params.rcExclude.bottom = bottom_right.y;
    UINT flags = TPM_RETURNCMD | TPM_NONOTIFY | TPM_TOPALIGN | TPM_VERTICAL |
                 (rtl ? TPM_RIGHTALIGN | TPM_LAYOUTRTL : TPM_LEFTALIGN);
    // Without the window in front the menu does not close when the reader clicks elsewhere;
    // the posted message lets it finish closing (KB135788).
    SetForegroundWindow(window);
    dxc_popup_open = menu->root;
    UINT command = (UINT)TrackPopupMenuEx(menu->root, flags, rtl ? bottom_right.x : top_left.x, bottom_right.y, window,
                                          &params);
    dxc_popup_open = NULL;
    PostMessageW(window, WM_NULL, 0, 0);
    int32_t written = 0;
    if (command >= 1 && command <= (UINT)menu->count) {
        size_t length = strlen(menu->paths[command]);
        if (length >= (size_t)capacity) length = (size_t)capacity - 1;
        memcpy(out, menu->paths[command], length);
        out[length] = 0;
        written = (int32_t)length;
    }
    dxc_popup_menu_free(menu);
    return written;
}

/** Takes an open entry menu down from the application's side; dxc_native_popup_menu then answers 0. */
void dxc_native_popup_menu_close(void) {
    if (dxc_popup_open != NULL) EndMenu();
}

/*
 * The names below are answered here and do nothing.
 *
 * One piece of Kotlin drives every desktop and reaches their windows by name, so each of
 * these files answers every name, including the ones that mean nothing on it. A missing
 * one is a warning on the linkers that look names up at load time and a failure on the
 * ones that do not, which is a defect that travels to whoever builds for the strictest
 * platform. It travelled three times before this was written down.
 */
void dxc_native_set_frame_callback(void *callback, void *isolate_thread) {
    (void)callback;
    (void)isolate_thread;
}

/** Takes the oldest event, or answers zero when there is none. */
int32_t dxc_native_poll_event(struct dxc_event *out) {
    if (dxc_event_count == 0) {
        dxc_pump_messages();
    }
    if (dxc_event_count == 0) {
        return 0;
    }
    *out = dxc_events[dxc_event_head];
    dxc_event_head = (dxc_event_head + 1) % DXC_EVENT_CAPACITY;
    dxc_event_count--;
    return 1;
}

struct dxc_native_window {
    void *window;
    void *device;
    void *queue;
    void *adapter;
    void *swapchain;
};

/** Waits until the queue has finished everything put on it. */
static void dxc_wait_for_gpu(void) {
    if (dxc_queue == NULL || dxc_fence == NULL) {
        return;
    }
    UINT64 mark = ++dxc_fence_value;
    if (FAILED(ID3D12CommandQueue_Signal(dxc_queue, dxc_fence, mark))) {
        return;
    }
    if (ID3D12Fence_GetCompletedValue(dxc_fence) < mark) {
        if (SUCCEEDED(ID3D12Fence_SetEventOnCompletion(dxc_fence, mark, dxc_fence_signalled))) {
            WaitForSingleObject(dxc_fence_signalled, INFINITE);
        }
    }
}

// The texture Skia draws into is kept across resizes and only grows: a new committed
// resource and a new Direct3D 11 wrapper on every drag step are work the step pays for
// before anything is presented. Skia is told the drawn size, and only that top-left
// region is copied into the swapchain.

static void dxc_release_texture(void) {
    if (dxc_wrapped != NULL) {
        ID3D11Resource_Release(dxc_wrapped);
        dxc_wrapped = NULL;
    }
    for (int index = 0; index < DXC_BUFFER_COUNT; index++) {
        if (dxc_buffers[index] != NULL) {
            ID3D12Resource_Release(dxc_buffers[index]);
            dxc_buffers[index] = NULL;
        }
    }
    dxc_texture_width = 0;
    dxc_texture_height = 0;
}

/** Lets go of everything holding the swapchain's buffer, so it can be refitted. */
static void dxc_release_buffers(void) {
    // Direct3D 11 defers destruction until its context is flushed, and the swapchain
    // will not resize while anything of the old size is still alive.
    if (dxc_d3d11_context != NULL) {
        ID3D11DeviceContext_ClearState(dxc_d3d11_context);
        ID3D11DeviceContext_Flush(dxc_d3d11_context);
    }
}

/**
 * Makes sure the texture Skia draws into holds width x height, wrapped for Direct3D 11.
 * Grows it only when it is too small, and then to at least the monitor the window is on,
 * rounded up to 256, so a drag grows it once at most.
 */
static int32_t dxc_acquire_buffers(int32_t width, int32_t height) {
    if (dxc_buffers[0] != NULL && dxc_wrapped != NULL && width <= dxc_texture_width &&
        height <= dxc_texture_height) {
        return 0;
    }
    dxc_release_texture();
    MONITORINFO monitor;
    memset(&monitor, 0, sizeof monitor);
    monitor.cbSize = sizeof monitor;
    if (dxc_window != NULL &&
        GetMonitorInfoW(MonitorFromWindow(dxc_window, MONITOR_DEFAULTTONEAREST), &monitor)) {
        int32_t monitor_width = (int32_t)(monitor.rcMonitor.right - monitor.rcMonitor.left);
        int32_t monitor_height = (int32_t)(monitor.rcMonitor.bottom - monitor.rcMonitor.top);
        if (monitor_width > width) width = monitor_width;
        if (monitor_height > height) height = monitor_height;
    }
    width = (width + 255) / 256 * 256;
    height = (height + 255) / 256 * 256;
    D3D12_HEAP_PROPERTIES heap;
    memset(&heap, 0, sizeof heap);
    heap.Type = D3D12_HEAP_TYPE_DEFAULT;
    D3D12_RESOURCE_DESC description;
    memset(&description, 0, sizeof description);
    description.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
    description.Width = (UINT64)width;
    description.Height = (UINT)height;
    description.DepthOrArraySize = 1;
    description.MipLevels = 1;
    description.Format = DXC_SWAPCHAIN_FORMAT;
    description.SampleDesc.Count = 1;
    description.Layout = D3D12_TEXTURE_LAYOUT_UNKNOWN;
    description.Flags = D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET;
    if (FAILED(ID3D12Device_CreateCommittedResource(
            dxc_device, &heap, dxc_comp_native ? D3D12_HEAP_FLAG_SHARED : D3D12_HEAP_FLAG_NONE, &description,
            D3D12_RESOURCE_STATE_PRESENT,
            NULL, &IID_ID3D12Resource, (void **)&dxc_buffers[0]))) {
        dxc_buffers[0] = NULL;
        return 1;
    }
    // In and out in the present state, which is where dxc_native_frame_end leaves the
    // texture after Skia, and where Skia is told to find it.
    D3D11_RESOURCE_FLAGS flags;
    memset(&flags, 0, sizeof flags);
    flags.BindFlags = D3D11_BIND_RENDER_TARGET;
    if (FAILED(ID3D11On12Device_CreateWrappedResource(
            dxc_on12, (IUnknown *)dxc_buffers[0], &flags, D3D12_RESOURCE_STATE_PRESENT,
            D3D12_RESOURCE_STATE_PRESENT, &IID_ID3D11Resource, (void **)&dxc_wrapped))) {
        dxc_wrapped = NULL;
        dxc_release_texture();
        return 1;
    }
    dxc_texture_width = width;
    dxc_texture_height = height;
    fprintf(stderr, "compose-rust: resize-mode: draw texture -> %dx%d because a frame needed more room\n",
            (int)width, (int)height);
    return 0;
}

/**
 * Lets go of a window that was only half made.
 *
 * Everything the caller had reached by the time it failed, and the globals with it, so
 * that a later frame finds no window rather than a window missing a piece of itself.
 */
static void dxc_abandon_window(IDXGIAdapter1 *adapter) {
    dxc_release_texture();
    dxc_release_buffers();
    if (dxc_commands != NULL) { ID3D12GraphicsCommandList_Release(dxc_commands); dxc_commands = NULL; }
    if (dxc_allocator != NULL) { ID3D12CommandAllocator_Release(dxc_allocator); dxc_allocator = NULL; }
    if (dxc_fence != NULL) { ID3D12Fence_Release(dxc_fence); dxc_fence = NULL; }
    if (dxc_fence_signalled != NULL) { CloseHandle(dxc_fence_signalled); dxc_fence_signalled = NULL; }
    dxc_latency_wait = NULL;
    // The visual holds the composition swapchain as its content, so it goes first.
    if (dxc_comp != NULL) { dxc_dcomp_release(dxc_comp); dxc_comp = NULL; }
    dxc_comp_release_native();
    if (dxc_swapchain != NULL) { IDXGISwapChain1_Release(dxc_swapchain); dxc_swapchain = NULL; }
    if (dxc_on12 != NULL) { ID3D11On12Device_Release(dxc_on12); dxc_on12 = NULL; }
    if (dxc_d3d11_context != NULL) { ID3D11DeviceContext_Release(dxc_d3d11_context); dxc_d3d11_context = NULL; }
    if (dxc_d3d11 != NULL) { ID3D11Device_Release(dxc_d3d11); dxc_d3d11 = NULL; }
    if (dxc_queue != NULL) { ID3D12CommandQueue_Release(dxc_queue); dxc_queue = NULL; }
    if (dxc_device != NULL) { ID3D12Device_Release(dxc_device); dxc_device = NULL; }
    if (adapter != NULL) { IDXGIAdapter1_Release(adapter); }
    if (dxc_window != NULL) { DestroyWindow(dxc_window); dxc_window = NULL; }
}

static float dxc_scale_of(HWND window) {
    UINT dpi = GetDpiForWindow(window);
    if (dpi == 0) {
        dpi = USER_DEFAULT_SCREEN_DPI;
    }
    return (float)dpi / (float)USER_DEFAULT_SCREEN_DPI;
}

static const wchar_t *DXC_WINDOW_CLASS = L"ComposeRustWindow";
static const wchar_t *DXC_SURFACE_CLASS = L"ComposeRustSurface";

/**
 * The swapchain's child window. It takes no input: every press passes through to the
 * window under it (HTTRANSPARENT), which is the one that owns focus, the input method and
 * the accessibility tree. It paints nothing of its own.
 */
static LRESULT CALLBACK dxc_surface_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    switch (message) {
    case WM_NCHITTEST:
        return HTTRANSPARENT;
    case WM_ERASEBKGND:
        return 1;
    case WM_PAINT: {
        PAINTSTRUCT paint;
        BeginPaint(window, &paint);
        EndPaint(window, &paint);
        return 0;
    }
    default:
        return DefWindowProcW(window, message, wparam, lparam);
    }
}

/** Takes a window out of the count that keeps the process-wide state alive; the last one takes it down. */
static void dxc_uncount_window(DxcWin *win) {
    if (win == NULL || !win->counted) return;
    win->counted = 0;
    if (dxc_shared_release(&dxc_shared_state)) {
        // The last window. The class stays registered while a window of it is still being
        // destroyed, in which case this fails and the next window registers it again over
        // the existing one (see dxc_register_class).
        UnregisterClassW(DXC_SURFACE_CLASS, GetModuleHandleW(NULL));
        UnregisterClassW(DXC_WINDOW_CLASS, GetModuleHandleW(NULL));
        dxc_class_registered = 0;
    }
}

static int32_t dxc_register_class(void) {
    if (dxc_class_registered) {
        return 0;
    }
    WNDCLASSEXW description;
    memset(&description, 0, sizeof description);
    description.cbSize = sizeof description;
    // Redrawn whole on either axis changing, because the swapchain owns every pixel and
    // has no use for a partial invalidation.
    description.style = CS_HREDRAW | CS_VREDRAW;
    description.lpfnWndProc = dxc_native_window_proc;
    description.hInstance = GetModuleHandleW(NULL);
    description.hCursor = LoadCursorW(NULL, IDC_ARROW);
    // No background brush. Windows would otherwise fill the client area with it before
    // the first frame lands, which reads as a white flash on a dark scene.
    description.hbrBackground = NULL;
    description.lpszClassName = DXC_WINDOW_CLASS;
    if (RegisterClassExW(&description) == 0 && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
        return 1;
    }
    description.style = 0;
    description.lpfnWndProc = dxc_surface_proc;
    description.lpszClassName = DXC_SURFACE_CLASS;
    if (RegisterClassExW(&description) == 0 && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
        return 1;
    }
    dxc_class_registered = 1;
    return 0;
}

/**
 * Opens a window with a Direct3D 12 swapchain filling it.
 *
 * Returns zero on success. Anything else says which part of the machine did not answer,
 * and those are the failures here that are not a mistake of ours: a machine with no
 * Direct3D 12 adapter has nothing this path can use.
 */
/*
 * Where the window's memory goes, for DXC_METRICS=1: one line at each step of opening the
 * window and at every metrics phase, with the process's private bytes and working set, the
 * video memory the adapter reports for this process, and the sizes of what the window holds
 * itself (the draw texture, the CPU frames' bitmap and the swapchain's buffers), each in MB
 * at four bytes a pixel. What the lines do not separate (the Direct3D runtime, Skia and its
 * caches, the language runtime) is the rest of the private bytes between one step and the
 * next.
 */
static int dxc_memory_points_on = -1;

static double dxc_adapter_video_mb(IDXGIAdapter1 *adapter, DXGI_MEMORY_SEGMENT_GROUP group) {
    IDXGIAdapter3 *adapter3 = NULL;
    double answer = -1.0;
    if (adapter != NULL &&
        SUCCEEDED(IDXGIAdapter1_QueryInterface(adapter, &IID_IDXGIAdapter3, (void **)&adapter3))) {
        DXGI_QUERY_VIDEO_MEMORY_INFO info;
        if (SUCCEEDED(IDXGIAdapter3_QueryVideoMemoryInfo(adapter3, 0, group, &info))) {
            answer = (double)info.CurrentUsage / (1024.0 * 1024.0);
        }
        IDXGIAdapter3_Release(adapter3);
    }
    return answer;
}

static void dxc_memory_point(const char *where, IDXGIAdapter1 *adapter) {
    if (dxc_memory_points_on < 0) {
        const char *asked = getenv("DXC_METRICS");
        dxc_memory_points_on = asked != NULL && asked[0] != '\0' && asked[0] != '0';
    }
    if (!dxc_memory_points_on) return;
    PROCESS_MEMORY_COUNTERS_EX counters;
    memset(&counters, 0, sizeof counters);
    counters.cb = sizeof counters;
    K32GetProcessMemoryInfo(GetCurrentProcess(), (PROCESS_MEMORY_COUNTERS *)&counters, sizeof counters);
    const double mb = 1024.0 * 1024.0;
    // On the composition route what is shown is the surface, at the size of its last frame.
    int32_t swapchain_width = dxc_on_comp ? dxc_comp_surface_width : dxc_sizing.fitted_width;
    int32_t swapchain_height = dxc_on_comp ? dxc_comp_surface_height : dxc_sizing.fitted_height;
    int swapchain_buffers = dxc_on_comp ? dxc_comp_surfaces : DXC_BUFFER_COUNT;
    fprintf(stderr,
            "compose-rust: memory-breakdown %s private_mb=%.1f working_set_mb=%.1f peak_working_set_mb=%.1f "
            "video_local_mb=%.1f video_nonlocal_mb=%.1f draw_texture=%dx%d (%.1f MB) "
            "raster_bitmap=%dx%d (%.1f MB) swapchain=%dx%d x%d (%.1f MB) route=%s\n",
            where, (double)counters.PrivateUsage / mb, (double)counters.WorkingSetSize / mb,
            (double)counters.PeakWorkingSetSize / mb,
            dxc_adapter_video_mb(adapter, DXGI_MEMORY_SEGMENT_GROUP_LOCAL),
            dxc_adapter_video_mb(adapter, DXGI_MEMORY_SEGMENT_GROUP_NON_LOCAL),
            (int)dxc_texture_width, (int)dxc_texture_height,
            (double)dxc_texture_width * dxc_texture_height * 4.0 / mb,
            (int)dxc_dib_width, (int)dxc_dib_height, (double)dxc_dib_width * dxc_dib_height * 4.0 / mb,
            (int)swapchain_width, (int)swapchain_height, swapchain_buffers,
            (double)swapchain_width * swapchain_height * 4.0 * swapchain_buffers / mb, dxc_on_comp || dxc_comp != NULL ? "dcomp" : "gdi");
    fflush(stderr);
}

// dxc_open_window's answer when the window was made for the composition route and the
// composition swapchain could not be put on it. Everything it made is gone again, and the
// window is opened once more on the GDI route.
#define DXC_OPEN_COMP_FAILED 13

/**
 * Whether a window opens on the composition route: only where DXC_DCOMP=1 asks for it, and
 * where a composition device can be made (dxc_open_window). Anything else is the GDI route.
 *
 * The GDI route is the default again. On a real GPU (2026-11-21, by eye, a real application)
 * the composition route showed a see-through strip inside the border on a fast drag that
 * grows the window, and the picture sliding on a drag of the left or top edge: the window's
 * rectangle is not part of the commit, so the rectangle and the picture can land in different
 * compositions, and the origin moves with the left and top edges. The GDI route showed
 * neither. The probe did not see them (it drags the right edge and takes a see-through margin
 * for a smaller window). The composition route stays behind the switch until it passes a
 * probe that does.
 */
static int dxc_comp_wanted(void) {
    const char *asked = getenv("DXC_DCOMP");
    return asked != NULL && strcmp(asked, "1") == 0;
}

static int32_t dxc_open_window(
    const char *title,
    int32_t width,
    int32_t height,
    int comp_wanted,
    struct dxc_native_window *out
) {
    // Asked for before the window exists, so the sizes below are read in real pixels
    // rather than in the ones Windows would have stretched for us. The shim this library
    // is entered through asks for the same thing at startup, and asking twice costs a
    // refusal nobody reads; this file opening a window without it would cost a window at
    // the wrong size.
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);

    if (dxc_register_class() != 0) {
        return 1;
    }

    wchar_t wide_title[256];
    if (MultiByteToWideChar(CP_UTF8, 0, title, -1, wide_title,
                            (int)(sizeof wide_title / sizeof *wide_title)) == 0) {
        wide_title[0] = L'\0';
    }

    // The composition device first, because the window's extended style depends on it. Kept
    // in the window's record from here, so every way out below lets go of it
    // (dxc_abandon_window, or dxc_native_window_open after a failure).
    dxc_comp = comp_wanted ? dxc_dcomp_create() : NULL;
    int comp = dxc_comp != NULL;
    fprintf(stderr, "compose-rust: dcomp: route %s because %s\n", comp ? "dcomp" : "gdi",
            comp ? "a composition device was made"
                 : comp_wanted ? "no composition device could be made" : "DXC_DCOMP is not 1 or the composition route failed");
    // On the GDI route with its redirection surface, as Flutter's view has one: while a
    // resize is being drawn, DWM keeps showing the last presented frame instead of the
    // desktop. On the composition route without one: what DWM shows of the window is the
    // visual, whose present and commit carry the picture and its size together.
    HWND window = CreateWindowExW(
        comp ? WS_EX_NOREDIRECTIONBITMAP : 0,
        DXC_WINDOW_CLASS,
        wide_title,
        dxc_window_style(),
        dxc_w->has_position ? dxc_points_to_pixels(dxc_w->position_x, GetDpiForSystem()) : CW_USEDEFAULT,
        dxc_w->has_position ? dxc_points_to_pixels(dxc_w->position_y, GetDpiForSystem()) : CW_USEDEFAULT,
        width, height,
        NULL, NULL, GetModuleHandleW(NULL), dxc_w);
    if (window == NULL) {
        return 2;
    }

    // The size that was asked for is in points, and the window was made in whatever
    // Windows took those numbers to be. Now that there is a window there is a monitor to
    // ask, so the client area is set to the pixels those points come to.
    UINT dpi = GetDpiForWindow(window);
    if (dpi == 0) {
        dpi = USER_DEFAULT_SCREEN_DPI;
    }
    RECT wanted;
    wanted.left = 0;
    wanted.top = 0;
    wanted.right = MulDiv(width, (int)dpi, USER_DEFAULT_SCREEN_DPI);
    wanted.bottom = MulDiv(height, (int)dpi, USER_DEFAULT_SCREEN_DPI);
    AdjustWindowRectExForDpi(&wanted, dxc_window_style(), FALSE, 0, dpi);
    int outer_width = wanted.right - wanted.left;
    // Without the system caption the content starts at the top of the window, so the
    // frame above it is not part of the outside.
    int outer_height = dxc_options.system_chrome ? wanted.bottom - wanted.top : wanted.bottom;
    // The outside is never larger than the work area of the monitor the window is on, and
    // never reaches past it, so the caption stays where it can be grabbed. Without a
    // position it is centred there. Where that monitor cannot be asked about, the window
    // keeps the place Windows chose for it rather than being moved to a corner that was
    // never a position.
    int min_outer_width = 0;
    int min_outer_height = 0;
    if (dxc_options.min_width > 0 || dxc_options.min_height > 0) {
        RECT least = {0, 0, MulDiv(dxc_options.min_width, (int)dpi, USER_DEFAULT_SCREEN_DPI),
            MulDiv(dxc_options.min_height, (int)dpi, USER_DEFAULT_SCREEN_DPI)};
        AdjustWindowRectExForDpi(&least, dxc_window_style(), FALSE, 0, dpi);
        min_outer_width = least.right - least.left;
        min_outer_height = dxc_options.system_chrome ? least.bottom - least.top : least.bottom;
    }
    UINT placement = SWP_NOZORDER | SWP_NOMOVE;
    int left = 0;
    int top = 0;
    // Where the application said, in points converted at the DPI the window was created at
    // (the DPI of the monitor Windows first put it on), so a position on a monitor with a
    // different scale can be off by that ratio until the window is moved there.
    int wanted_x = dxc_w->has_position ? dxc_points_to_pixels(dxc_w->position_x, dpi) : 0;
    int wanted_y = dxc_w->has_position ? dxc_points_to_pixels(dxc_w->position_y, dpi) : 0;
    // With a position, the monitor is the one at that position, not the one Windows first
    // placed the new window on, so a window asked for on a secondary monitor stays there.
    HMONITOR monitor;
    if (dxc_w->has_position) {
        RECT asked = {wanted_x, wanted_y, wanted_x + outer_width, wanted_y + outer_height};
        monitor = MonitorFromRect(&asked, MONITOR_DEFAULTTONEAREST);
    } else {
        monitor = MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST);
    }
    MONITORINFO monitor_info;
    monitor_info.cbSize = sizeof monitor_info;
    if (monitor != NULL && GetMonitorInfoW(monitor, &monitor_info)) {
        struct dxc_placement_rect work = {monitor_info.rcWork.left, monitor_info.rcWork.top,
            monitor_info.rcWork.right, monitor_info.rcWork.bottom};
        struct dxc_placement_rect outer = dxc_placement_fit(work, outer_width, outer_height,
            min_outer_width, min_outer_height, dxc_w->has_position, wanted_x, wanted_y);
        left = outer.left;
        top = outer.top;
        outer_width = outer.right - outer.left;
        outer_height = outer.bottom - outer.top;
        placement = SWP_NOZORDER;
    } else if (dxc_w->has_position) {
        left = wanted_x;
        top = wanted_y;
        placement = SWP_NOZORDER;
    }
    // The size is kept in points from here on, through changes of DPI (win32_dpi.h). Where
    // the work area made the window smaller, the WM_SIZE below keeps the pixels it got.
    dxc_w->layout_dpi = dpi;
    dxc_w->dpi_size.width = width;
    dxc_w->dpi_size.height = height;
    dxc_w->dpi_size.dpi = USER_DEFAULT_SCREEN_DPI;
    SetWindowPos(window, NULL, left, top, outer_width, outer_height, placement | SWP_FRAMECHANGED);

    RECT client;
    GetClientRect(window, &client);
    UINT pixel_width = (UINT)(client.right - client.left);
    UINT pixel_height = (UINT)(client.bottom - client.top);
    if (pixel_width == 0 || pixel_height == 0) {
        DestroyWindow(window);
        return 3;
    }

    IDXGIFactory4 *factory = NULL;
    if (FAILED(CreateDXGIFactory2(0, &IID_IDXGIFactory4, (void **)&factory))) {
        DestroyWindow(window);
        return 4;
    }

    dxc_memory_point("before the Direct3D device", NULL);

    // The first adapter that is a real one and can make a device. A software adapter is
    // skipped rather than taken: it would draw, slowly, and hide the fact that the
    // machine has nothing to draw with.
    IDXGIAdapter1 *adapter = NULL;
    ID3D12Device *device = NULL;
    for (UINT index = 0;
         IDXGIFactory4_EnumAdapters1(factory, index, &adapter) != DXGI_ERROR_NOT_FOUND;
         index++) {
        DXGI_ADAPTER_DESC1 description;
        if (SUCCEEDED(IDXGIAdapter1_GetDesc1(adapter, &description)) &&
            (description.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) == 0 &&
            SUCCEEDED(D3D12CreateDevice((IUnknown *)adapter, D3D_FEATURE_LEVEL_11_0,
                                        &IID_ID3D12Device, (void **)&device))) {
            dxc_device_luid = description.AdapterLuid;
            break;
        }
        IDXGIAdapter1_Release(adapter);
        adapter = NULL;
    }
    // Unless whoever started this asked for the software one by name. A machine with no
    // graphics card at all, a virtual machine or a CI runner, has nothing else, and there
    // drawing slowly is the point: it is how a window that draws is told from one that does
    // not where nobody is watching. Asked for, never fallen back to, for the reason above.
    if (device == NULL) {
        const char *warp = getenv("DXC_D3D12_WARP");
        IDXGIAdapter *software = NULL;
        if (warp != NULL && warp[0] != '\0' &&
            SUCCEEDED(IDXGIFactory4_EnumWarpAdapter(factory, &IID_IDXGIAdapter1, (void **)&software))) {
            adapter = (IDXGIAdapter1 *)software;
            DXGI_ADAPTER_DESC1 warp_description;
            if (SUCCEEDED(IDXGIAdapter1_GetDesc1(adapter, &warp_description))) {
                dxc_device_luid = warp_description.AdapterLuid;
            }
            if (FAILED(D3D12CreateDevice((IUnknown *)adapter, D3D_FEATURE_LEVEL_11_0,
                                         &IID_ID3D12Device, (void **)&device))) {
                device = NULL;
            }
        }
    }
    if (device == NULL) {
        if (adapter != NULL) IDXGIAdapter1_Release(adapter);
        IDXGIFactory4_Release(factory);
        DestroyWindow(window);
        return 5;
    }

    D3D12_COMMAND_QUEUE_DESC queue_description;
    memset(&queue_description, 0, sizeof queue_description);
    queue_description.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;
    queue_description.Flags = D3D12_COMMAND_QUEUE_FLAG_NONE;
    // High, not global realtime: only realtime needs privileges.
    queue_description.Priority = dxc_priority() >= 1 ? D3D12_COMMAND_QUEUE_PRIORITY_HIGH
                                                     : D3D12_COMMAND_QUEUE_PRIORITY_NORMAL;
    ID3D12CommandQueue *queue = NULL;
    HRESULT queued = ID3D12Device_CreateCommandQueue(device, &queue_description,
                                                     &IID_ID3D12CommandQueue, (void **)&queue);
    if (FAILED(queued) && queue_description.Priority != D3D12_COMMAND_QUEUE_PRIORITY_NORMAL) {
        queue_description.Priority = D3D12_COMMAND_QUEUE_PRIORITY_NORMAL;
        queued = ID3D12Device_CreateCommandQueue(device, &queue_description,
                                                 &IID_ID3D12CommandQueue, (void **)&queue);
    }
    if (FAILED(queued)) {
        ID3D12Device_Release(device);
        IDXGIAdapter1_Release(adapter);
        IDXGIFactory4_Release(factory);
        DestroyWindow(window);
        return 6;
    }
    dxc_memory_point("with the device and its queue", adapter);

    // Direct3D 11 on the renderer's Direct3D 12 device and queue, for the swapchain.
    // Looked up at run time so the build links nothing new.
    ID3D11Device *d3d11 = NULL;
    ID3D11DeviceContext *d3d11_context = NULL;
    ID3D11On12Device *on12 = NULL;
    HMODULE d3d11_library = LoadLibraryW(L"d3d11.dll");
    PFN_D3D11ON12_CREATE_DEVICE create_on12 = d3d11_library == NULL ? NULL
        : (PFN_D3D11ON12_CREATE_DEVICE)(void *)GetProcAddress(d3d11_library, "D3D11On12CreateDevice");
    IUnknown *queues[1] = {(IUnknown *)queue};
    if (create_on12 == NULL ||
        FAILED(create_on12((IUnknown *)device, 0, NULL, 0, queues, 1, 0, &d3d11,
                           &d3d11_context, NULL)) ||
        FAILED(ID3D11Device_QueryInterface(d3d11, &dxc_iid_on12_device, (void **)&on12))) {
        if (d3d11_context != NULL) ID3D11DeviceContext_Release(d3d11_context);
        if (d3d11 != NULL) ID3D11Device_Release(d3d11);
        ID3D12CommandQueue_Release(queue);
        ID3D12Device_Release(device);
        IDXGIAdapter1_Release(adapter);
        IDXGIFactory4_Release(factory);
        DestroyWindow(window);
        return 7;
    }

    // Flutter's window swapchain, as ANGLE makes it for a window without
    // DirectComposition: copy model (DXGI_SWAP_EFFECT_SEQUENTIAL), one buffer, STRETCH.
    // Every resize refits it to exactly the client size before anything is presented, so
    // the stretch is always 1:1.
    DXGI_SWAP_CHAIN_DESC1 swapchain_description;
    memset(&swapchain_description, 0, sizeof swapchain_description);
    swapchain_description.Width = pixel_width;
    swapchain_description.Height = pixel_height;
    swapchain_description.Format = DXC_SWAPCHAIN_FORMAT;
    swapchain_description.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    swapchain_description.BufferCount = DXC_BUFFER_COUNT;
    swapchain_description.SampleDesc.Count = 1;
    swapchain_description.SwapEffect = DXGI_SWAP_EFFECT_SEQUENTIAL;
    swapchain_description.Scaling = DXGI_SCALING_STRETCH;
    swapchain_description.AlphaMode = DXGI_ALPHA_MODE_UNSPECIFIED;
    swapchain_description.Flags = 0;
    IDXGISwapChain1 *swapchain = NULL;
    HRESULT made = E_FAIL;
    if (comp) {
        // The composition route: the visual shows a virtual surface of the client area's
        // size, on a surface factory of the Direct3D 11 device. The swapchain is made only as
        // the handle the renderer hands back with each frame: a composition swapchain, never
        // put on a visual, as small as one can be.
        DXGI_SWAP_CHAIN_DESC1 comp_description = swapchain_description;
        comp_description.Width = DXC_COMP_SWAPCHAIN_SIZE;
        comp_description.Height = DXC_COMP_SWAPCHAIN_SIZE;
        comp_description.BufferCount = DXC_COMP_BUFFER_COUNT;
        comp_description.SwapEffect = DXGI_SWAP_EFFECT_FLIP_SEQUENTIAL;
        comp_description.Scaling = DXGI_SCALING_STRETCH;
        comp_description.AlphaMode = DXGI_ALPHA_MODE_IGNORE;
        made = IDXGIFactory4_CreateSwapChainForComposition(factory, (IUnknown *)d3d11, &comp_description, NULL,
                                                           &swapchain);
        HRESULT step = made;
        const char *failed_at = "CreateSwapChainForComposition";
        if (SUCCEEDED(step)) {
            // The device the surface is written with: a native one unless
            // DXC_DCOMP_UPLOAD=on12 asks for the D3D11On12 device (see DxcWin.comp_native).
            const char *upload = getenv("DXC_DCOMP_UPLOAD");
            int on12_asked = upload != NULL && strcmp(upload, "on12") == 0;
            dxc_comp_native = !on12_asked && dxc_comp_make_native(adapter, device);
            if (!dxc_comp_native) {
                dxc_comp_device = d3d11;
                dxc_comp_context = d3d11_context;
            }
            fprintf(stderr, "compose-rust: dcomp: surface written with the %s device%s\n",
                    dxc_comp_native ? "native Direct3D 11" : "D3D11On12",
                    on12_asked ? " (DXC_DCOMP_UPLOAD=on12)" : dxc_comp_native ? "" : " (the native one could not be made)");
            // DXC_DCOMP_SURFACES: how many surfaces the visual's content takes turns between, 2
            // unless asked (1 to DXC_DCOMP_MAX_SURFACES). With one, BeginDraw waits for DWM to let
            // go of the surface on screen: 7 to 18 ms a drag step in run 44662848036.
            const char *surfaces_asked = getenv("DXC_DCOMP_SURFACES");
            int surfaces = surfaces_asked != NULL ? atoi(surfaces_asked) : 2;
            if (surfaces < 1 || surfaces > DXC_DCOMP_MAX_SURFACES) surfaces = 2;
            fprintf(stderr, "compose-rust: dcomp: %d surfaces taking turns (DXC_DCOMP_SURFACES)\n", surfaces);
            dxc_comp_surfaces = surfaces;
            failed_at = "the surface and the visual";
            step = dxc_dcomp_attach(dxc_comp, window, (IUnknown *)dxc_comp_device, (int32_t)pixel_width,
                                    (int32_t)pixel_height, surfaces) == 0 ? S_OK : E_FAIL;
        }
        if (FAILED(step)) {
            fprintf(stderr, "compose-rust: dcomp: %s failed with 0x%08lx, the window opens on the GDI route\n",
                    failed_at, (unsigned long)step);
            dxc_dcomp_release(dxc_comp);
            dxc_comp = NULL;
            dxc_comp_release_native();
            if (swapchain != NULL) IDXGISwapChain1_Release(swapchain);
            ID3D11On12Device_Release(on12);
            ID3D11DeviceContext_Release(d3d11_context);
            ID3D11Device_Release(d3d11);
            ID3D12CommandQueue_Release(queue);
            ID3D12Device_Release(device);
            IDXGIAdapter1_Release(adapter);
            IDXGIFactory4_Release(factory);
            DestroyWindow(window);
            return DXC_OPEN_COMP_FAILED;
        }
        swapchain_description.Flags = comp_description.Flags;
        dxc_comp_surface_width = (int32_t)pixel_width;
        dxc_comp_surface_height = (int32_t)pixel_height;
        dxc_on_comp = 1;
        const char *commit_at = getenv("DXC_DCOMP_COMMIT");
        dxc_comp_commit_mode = DXC_COMP_COMMIT_ORDER;
        for (int named = 0; commit_at != NULL && named < 3; named++) {
            if (strcmp(commit_at, dxc_comp_commit_names[named]) == 0) dxc_comp_commit_mode = named;
        }
        const char *drag_rule = getenv("DXC_DCOMP_DRAG_RULE");
        dxc_comp_drag_rule = drag_rule != NULL && strcmp(drag_rule, "1") == 0;
        const char *wait_for = getenv("DXC_DCOMP_SHRINK_WAIT");
        dxc_comp_wait_mode = DXC_COMP_WAIT_COMMIT;
        for (int named = 0; wait_for != NULL && named < 4; named++) {
            if (strcmp(wait_for, dxc_comp_wait_names[named]) == 0) dxc_comp_wait_mode = named;
        }
    } else {
        // On a child window covering the client area, not on the window itself. A swapchain's
        // presents reach DWM through a surface of their own over the window's GDI redirection
        // surface, so on the same window a GDI copy made during a resize would not be what DWM
        // shows. On a child, hiding the child during the size change leaves the top-level
        // window's GDI surface, which DWM resizes and composes together with the window.
        dxc_surface = CreateWindowExW(0, DXC_SURFACE_CLASS, L"", WS_CHILD | WS_VISIBLE, 0, 0,
                                      (int)pixel_width, (int)pixel_height, window, NULL,
                                      GetModuleHandleW(NULL), NULL);
        made = dxc_surface == NULL ? E_FAIL : IDXGIFactory4_CreateSwapChainForHwnd(
            factory, (IUnknown *)d3d11, dxc_surface, &swapchain_description, NULL, NULL, &swapchain);
    }
    if (FAILED(made)) {
        ID3D11On12Device_Release(on12);
        ID3D11DeviceContext_Release(d3d11_context);
        ID3D11Device_Release(d3d11);
        ID3D12CommandQueue_Release(queue);
        ID3D12Device_Release(device);
        IDXGIAdapter1_Release(adapter);
        IDXGIFactory4_Release(factory);
        DestroyWindow(window);
        return 7;
    }
    dxc_swapchain_flags = swapchain_description.Flags;
    dxc_memory_point("with the swapchain", adapter);
    int gpu_priority_set = 0;
    if (dxc_priority() >= 2) {
        IDXGIDevice *dxgi_device = NULL;
        if (SUCCEEDED(ID3D11Device_QueryInterface(d3d11, &IID_IDXGIDevice, (void **)&dxgi_device))) {
            gpu_priority_set = SUCCEEDED(IDXGIDevice_SetGPUThreadPriority(dxgi_device, 5));
            IDXGIDevice_Release(dxgi_device);
        }
    }
    fprintf(stderr,
            "compose-rust: resize priority: drag thread above normal %s, command queue %s, "
            "GPU thread priority %s, nothing deferred (accessibility and IME updates already run "
            "outside the resize step)\n",
            dxc_priority() >= 1 ? "on" : "off",
            queue_description.Priority == D3D12_COMMAND_QUEUE_PRIORITY_HIGH ? "high" : "normal",
            gpu_priority_set ? "+5" : (dxc_priority() >= 2 ? "refused" : "off"));
    if (comp) {
        fprintf(stderr,
                "compose-rust: composition surface on a visual of the window, %ux%u; frames drawn ahead of a size "
                "committed by DXC_DCOMP_COMMIT=%s, waited for by DXC_DCOMP_SHRINK_WAIT=%s\n",
                pixel_width, pixel_height, dxc_comp_commit_names[dxc_comp_commit_mode],
                dxc_comp_wait_names[dxc_comp_wait_mode]);
    } else {
        fprintf(stderr, "compose-rust: swapchain on a child window of the window, copy model (sequential), 1 buffer, STRETCH, %ux%u\n",
                pixel_width, pixel_height);
    }
    // DXGI answers alt-enter by putting the window into its own idea of full screen,
    // which is a mode nothing here knows how to draw in.
    IDXGIFactory4_MakeWindowAssociation(factory, comp ? window : dxc_surface, DXGI_MWA_NO_ALT_ENTER);
    IDXGIFactory4_Release(factory);

    dxc_window = window;
    dxc_device = device;
    dxc_queue = queue;
    dxc_swapchain = swapchain;
    dxc_d3d11 = d3d11;
    dxc_d3d11_context = d3d11_context;
    dxc_on12 = on12;
    // The size frames are drawn at from here until something resizes the window. Written
    // down now so that the size the window reports as it is shown, which is this one, is
    // recognised as the size the swapchain already is.
    dxc_resize_fitted(&dxc_sizing, (int32_t)pixel_width, (int32_t)pixel_height);
    dxc_report_latency = getenv("DXC_REPORT_LATENCY") != NULL;
    {
        const char *predraw = getenv("DXC_RESIZE_PREDRAW");
        dxc_predraw_off = predraw != NULL && strcmp(predraw, "0") == 0;
        const char *through = getenv("DXC_RESIZE_SWAPCHAIN");
        dxc_raster_present_off = through != NULL && strcmp(through, "0") == 0;
        const char *flush = getenv("DXC_PREDRAW_FLUSH");
        dxc_predraw_flush = flush != NULL && strcmp(flush, "1") == 0;
        const char *early = getenv("DXC_RESIZE_HIDE_EARLY");
        dxc_hide_early_off = early != NULL && strcmp(early, "0") == 0;
        const char *drag_predraw = getenv("DXC_DRAG_PREDRAW");
        dxc_drag_predraw_off = drag_predraw != NULL && strcmp(drag_predraw, "0") == 0;
        const char *drag_flush = getenv("DXC_DRAG_FLUSH");
        dxc_drag_flush_asked = drag_flush == NULL ? -1 : strcmp(drag_flush, "1") == 0 ? 1
                               : strcmp(drag_flush, "0") == 0 ? 0 : -1;
        uint32_t build = dxc_windows_build();
        dxc_drag_flush = dxc_resize_drag_flush_wanted(dxc_drag_flush_asked, build);
        fprintf(stderr,
                "compose-rust: resize-mode: drag steps drawn %s, %s (windows build %u, DXC_DRAG_FLUSH %s)\n",
                dxc_drag_predraw_off ? "in WM_SIZE" : "ahead in WM_NCCALCSIZE",
                dxc_drag_flush ? "each waits for one composition before the size changes"
                               : "no wait for a composition",
                (unsigned)build, drag_flush != NULL ? drag_flush : "unset");
        const char *surface_return = getenv("DXC_SURFACE_RETURN");
        for (int named = 0; surface_return != NULL && named < DXC_SURFACE_RETURN_COUNT; named++) {
            if (strcmp(surface_return, dxc_surface_return_names[named]) == 0) dxc_surface_return = named;
        }
    }
    dxc_log_monitor(window, "the window opened");
    dxc_memory_point("the window opened", adapter);
    dxc_presented_width = (int32_t)pixel_width;
    dxc_presented_height = (int32_t)pixel_height;

    if (dxc_acquire_buffers((int32_t)pixel_width, (int32_t)pixel_height) != 0) {
        dxc_abandon_window(adapter);
        return 9;
    }

    // A list of our own, holding one barrier and nothing else. Skia records and submits
    // its own work; what it does not do is put the buffer back into the state a swapchain
    // will accept for presenting, and a buffer presented from any other state is a buffer
    // the debug layer rejects and a driver is free to mishandle.
    if (FAILED(ID3D12Device_CreateCommandAllocator(device, D3D12_COMMAND_LIST_TYPE_DIRECT,
                                                   &IID_ID3D12CommandAllocator,
                                                   (void **)&dxc_allocator)) ||
        FAILED(ID3D12Device_CreateCommandList(device, 0, D3D12_COMMAND_LIST_TYPE_DIRECT,
                                              dxc_allocator, NULL,
                                              &IID_ID3D12GraphicsCommandList,
                                              (void **)&dxc_commands)) ||
        FAILED(ID3D12Device_CreateFence(device, 0, D3D12_FENCE_FLAG_NONE, &IID_ID3D12Fence,
                                        (void **)&dxc_fence))) {
        dxc_abandon_window(adapter);
        return 10;
    }
    ID3D12GraphicsCommandList_Close(dxc_commands);
    dxc_fence_signalled = CreateEventW(NULL, FALSE, FALSE, NULL);
    if (dxc_fence_signalled == NULL) {
        dxc_abandon_window(adapter);
        return 11;
    }

    dxc_accept_files(window);
    ShowWindow(window, SW_SHOW);
    SetForegroundWindow(window);
    SetFocus(window);
    dxc_w->active = GetForegroundWindow() == window;
    dxc_w->moved_pending = 0;

    out->window = (void *)window;
    out->device = (void *)device;
    out->queue = (void *)queue;
    out->adapter = (void *)adapter;
    dxc_adapter = adapter;
    out->swapchain = (void *)swapchain;
    return 0;
}

/**
 * What the window is drawn at, in pixels, and how many of them go to a point.
 *
 * The size the swapchain is drawn at and not the client area's. The two agree as soon as
 * a resize has been taken, and where one was refused they do not: a buffer described to
 * Skia as bigger than it is would be painted past its end. The client area answers only
 * before there is a swapchain to ask.
 */
/**
 * Opens a window with a state record of its own. The record is the one the window's messages
 * run against from the first of them, it is chosen for the calls that name no window, and
 * the window is found from its handle until dxc_native_window_release.
 */
static int32_t dxc_open_window_record(const char *title, int32_t width, int32_t height, int comp_wanted,
                                      struct dxc_native_window *out);

int32_t dxc_native_window_open(
    const char *title,
    int32_t width,
    int32_t height,
    struct dxc_native_window *out
) {
    // Where the window opens is taken by the first attempt, and a second attempt on the GDI
    // route has to open it in the same place.
    int32_t has_position = dxc_next_position.has;
    int32_t position_x = dxc_next_position.x;
    int32_t position_y = dxc_next_position.y;
    int32_t result = dxc_open_window_record(title, width, height, dxc_comp_wanted(), out);
    if (result == DXC_OPEN_COMP_FAILED) {
        dxc_next_position.has = has_position;
        dxc_next_position.x = position_x;
        dxc_next_position.y = position_y;
        result = dxc_open_window_record(title, width, height, 0, out);
    }
    return result;
}

static int32_t dxc_open_window_record(const char *title, int32_t width, int32_t height, int comp_wanted,
                                      struct dxc_native_window *out) {
    if (dxc_table.count >= DXC_MAX_WINDOWS) {
        return 12;
    }
    DxcWin *win = (DxcWin *)calloc(1, sizeof *win);
    if (win == NULL) {
        return 12;
    }
    win->options = dxc_next_options;
    win->cursor = IDC_ARROW;
    // The size the window opens with, so the first move of the slider is reported.
    win->text_scale_seen = dxc_native_text_scale_percent() + 1;
    win->has_position = dxc_next_position.has;
    win->position_x = dxc_next_position.x;
    win->position_y = dxc_next_position.y;
    dxc_next_position.has = 0;
    DxcWin *outer = dxc_w;
    DxcWin *outer_creating = dxc_creating;
    dxc_w = win;
    dxc_creating = win;
    dxc_shared_acquire(&dxc_shared_state);
    win->counted = 1;
    int32_t result = dxc_open_window(title, width, height, comp_wanted, out);
    dxc_creating = outer_creating;
    if (result != 0) {
        // A composition device made before a failure that came ahead of dxc_abandon_window.
        if (win->comp != NULL) {
            dxc_dcomp_release(win->comp);
            win->comp = NULL;
        }
        dxc_uncount_window(win);
        dxc_w = outer;
        free(win);
        return result;
    }
    dxc_table_add(&dxc_table, win->window, win);
    return 0;
}

void dxc_native_window_size(void *window_pointer, int32_t *width, int32_t *height, float *scale) {
    HWND window = (HWND)window_pointer;
    dxc_select_handle(window_pointer);
    *width = 0;
    *height = 0;
    *scale = 1.0f;
    if (window == NULL) {
        return;
    }
    *scale = dxc_scale_of(window);
    // The size the swapchain was last refitted to, which is the size drawn at.
    if (dxc_swapchain != NULL && dxc_sizing.fitted_width > 0 && dxc_sizing.fitted_height > 0) {
        *width = dxc_sizing.fitted_width;
        *height = dxc_sizing.fitted_height;
        return;
    }
    RECT client;
    if (GetClientRect(window, &client)) {
        *width = (int32_t)(client.right - client.left);
        *height = (int32_t)(client.bottom - client.top);
    }
}

/**
 * Answers the buffer this frame paints into.
 *
 * Non-zero when there is nothing to paint into: the window has been closed, or it is
 * minimised, or the swapchain could not be made to fit a size it has just been given.
 * None of those is an error. The frame is skipped and the next one asks again.
 */
int32_t dxc_native_frame_begin(void *swapchain_pointer, void **texture_out) {
    IDXGISwapChain1 *swapchain = (IDXGISwapChain1 *)swapchain_pointer;
    dxc_select_swapchain(swapchain_pointer);
    if (swapchain == NULL || dxc_window == NULL) {
        return 1;
    }

    // The size the window was last given, where that is not the size it is already drawn
    // at. Showing the window reports a size as well, and it is the size the swapchain was
    // just made, so the common case costs a comparison rather than a round of releasing
    // and taking back every buffer.
    // One frame of latency. Inside a drag the wait is none at all: a frame skipped there
    // is the black this exists to avoid, so it is drawn whether or not the screen is ready.
    if (dxc_latency_wait != NULL) {
        WaitForSingleObjectEx(dxc_latency_wait,
                              dxc_sizing.dragging || dxc_resizing ? 0 : 100, FALSE);
    }
    int32_t wanted_width = 0;
    int32_t wanted_height = 0;
    if (dxc_resize_take(&dxc_sizing, &wanted_width, &wanted_height)) {
        // Refitted to exactly the size drawn, every step, so the buffer and the client
        // area always agree and STRETCH is always 1:1.
        //
        // Nothing may still be reading the buffers when they are let go, and a swapchain
        // refuses to be refitted while anything holds one.
        LARGE_INTEGER refit_started;
        QueryPerformanceCounter(&refit_started);
        HRESULT resized;
        if (dxc_on_comp) {
            // Nothing to refit: the frame is written into the composition surface at its own
            // size (dxc_native_frame_end), which the surface takes with that frame's commit.
            dxc_step_gpu_idle_ms = 0.0;
            resized = S_OK;
        } else {
            dxc_wait_for_gpu();
            dxc_step_gpu_idle_ms = dxc_elapsed_ms(refit_started);
            dxc_release_buffers();
            resized = IDXGISwapChain1_ResizeBuffers(
                swapchain, DXC_BUFFER_COUNT, (UINT)wanted_width, (UINT)wanted_height,
                DXC_SWAPCHAIN_FORMAT, dxc_swapchain_flags);
        }
        // A refusal leaves the swapchain the size it was, so the old buffers are taken
        // back; not taking them back is a window that stays black from here on. This frame
        // is skipped rather than drawn at the old size, which DXGI_SCALING_STRETCH would show
        // stretched over the new client area. The size is asked for again by the next
        // frame, which is asked for now (WM_PAINT draws one), a few times in a row at most
        // (dxc_resize_refused).
        if (FAILED(resized)) {
            dxc_log_dxgi_failure("ResizeBuffers", resized);
            dxc_acquire_buffers(dxc_sizing.fitted_width, dxc_sizing.fitted_height);
            if (dxc_resize_refused(&dxc_sizing, wanted_width, wanted_height)) {
                InvalidateRect(dxc_window, NULL, FALSE);
            }
            return 2;
        }
        if (dxc_acquire_buffers(wanted_width, wanted_height) != 0) {
            fprintf(stderr, "compose-rust: resize-mode: skipped frame %dx%d because the draw texture could not be made\n",
                    (int)wanted_width, (int)wanted_height);
            return 2;
        }
        dxc_resize_fitted(&dxc_sizing, wanted_width, wanted_height);
        dxc_step_refit_ms = dxc_elapsed_ms(refit_started) - dxc_step_gpu_idle_ms;
    }

    // A CPU frame may have refitted the swapchain without growing the draw texture.
    if (dxc_acquire_buffers(dxc_sizing.fitted_width, dxc_sizing.fitted_height) != 0) {
        return 2;
    }
    dxc_frame_index = 0;
    if (dxc_buffers[dxc_frame_index] == NULL || dxc_wrapped == NULL) {
        fprintf(stderr, "compose-rust: resize-mode: skipped frame because there is no draw texture\n");
        return 3;
    }
    *texture_out = (void *)dxc_buffers[dxc_frame_index];
    // From here until dxc_native_frame_end is the renderer's: the Skia surface, the
    // scene's layout and drawing, and its submit.
    QueryPerformanceCounter(&dxc_step_mark);
    return 0;
}

/** Puts the painted buffer on the screen. */
void dxc_native_frame_end(void *queue_pointer) {
    ID3D12CommandQueue *queue = (ID3D12CommandQueue *)queue_pointer;
    dxc_select_queue(queue_pointer);
    if (queue == NULL || dxc_swapchain == NULL || dxc_buffers[dxc_frame_index] == NULL ||
        dxc_allocator == NULL || dxc_commands == NULL) {
        return;
    }

    // Skia drew into this buffer, so it left it as a render target, and that is what the
    // barrier says it is coming from. The Kotlin side declares the buffer to Skia as
    // being ready to present, which is what it is put back to here, so the two
    // descriptions stay true of the same buffer frame after frame.
    dxc_step_draw_ms = dxc_elapsed_ms(dxc_step_mark);
    LARGE_INTEGER present_started;
    QueryPerformanceCounter(&present_started);
    // The allocator may be reset only once the list recorded from it last frame has run.
    // Usually it has (Skia's submit waited for work queued after it); this waits only
    // when it has not.
    if (dxc_last_list_mark != 0 && ID3D12Fence_GetCompletedValue(dxc_fence) < dxc_last_list_mark &&
        SUCCEEDED(ID3D12Fence_SetEventOnCompletion(dxc_fence, dxc_last_list_mark, dxc_fence_signalled))) {
        WaitForSingleObject(dxc_fence_signalled, INFINITE);
    }
    ID3D12CommandAllocator_Reset(dxc_allocator);
    ID3D12GraphicsCommandList_Reset(dxc_commands, dxc_allocator, NULL);
    D3D12_RESOURCE_BARRIER barrier;
    memset(&barrier, 0, sizeof barrier);
    barrier.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
    barrier.Flags = D3D12_RESOURCE_BARRIER_FLAG_NONE;
    barrier.Transition.pResource = dxc_buffers[dxc_frame_index];
    barrier.Transition.Subresource = D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;
    barrier.Transition.StateBefore = D3D12_RESOURCE_STATE_RENDER_TARGET;
    barrier.Transition.StateAfter = D3D12_RESOURCE_STATE_PRESENT;
    ID3D12GraphicsCommandList_ResourceBarrier(dxc_commands, 1, &barrier);
    ID3D12GraphicsCommandList_Close(dxc_commands);
    ID3D12CommandList *lists[1];
    lists[0] = (ID3D12CommandList *)(void *)dxc_commands;
    ID3D12CommandQueue_ExecuteCommandLists(queue, 1, lists);
    if (SUCCEEDED(ID3D12CommandQueue_Signal(queue, dxc_fence, dxc_fence_value + 1))) {
        dxc_last_list_mark = ++dxc_fence_value;
    }

    // A resize frame presents only at exactly the size WM_SIZE recorded, and only once
    // for that size: a frame of any other size would be shown cut or padded against the
    // new rectangle, and a second present of the same size is a frame out of order.
    if (dxc_resizing &&
        (dxc_sizing.fitted_width != dxc_resize_target_width ||
         dxc_sizing.fitted_height != dxc_resize_target_height ||
         (dxc_presented_width == dxc_resize_target_width &&
          dxc_presented_height == dxc_resize_target_height))) {
        fprintf(stderr, "compose-rust: resize-mode: skipped present %dx%d because %s (target %dx%d)\n",
                (int)dxc_sizing.fitted_width, (int)dxc_sizing.fitted_height,
                (dxc_sizing.fitted_width != dxc_resize_target_width ||
                 dxc_sizing.fitted_height != dxc_resize_target_height)
                    ? "the frame was drawn at another size"
                    : "that size was already presented",
                (int)dxc_resize_target_width, (int)dxc_resize_target_height);
        dxc_wait_for_gpu();
        return;
    }
    // The composition route: the frame is copied into the surface and committed, so its size
    // changes with it, never before or after. Outside a resize the wait for a composition
    // paces the frame loop, as the present's interval of one does on the GDI route. The
    // swapchain is not presented to.
    if (dxc_on_comp) {
        LARGE_INTEGER comp_copy_started;
        QueryPerformanceCounter(&comp_copy_started);
        int written = dxc_comp_upload_texture(dxc_sizing.fitted_width, dxc_sizing.fitted_height);
        dxc_step_copy_ms = dxc_elapsed_ms(comp_copy_started);
        if (written && dxc_comp_show("a GPU frame")) {
            dxc_presented_width = dxc_sizing.fitted_width;
            dxc_presented_height = dxc_sizing.fitted_height;
            // The native views move with this commit, before the wait for a composition.
            dxc_nv_apply(dxc_w);
            if (!dxc_resizing) dxc_dwm_flush();
        }
        dxc_step_present_ms = dxc_elapsed_ms(present_started) - dxc_step_copy_ms;
        return;
    }
    // The copy into the swapchain's buffer, on the same queue after the barrier above,
    // through Direct3D 11. One GPU copy of the frame per present.
    LARGE_INTEGER copy_started;
    QueryPerformanceCounter(&copy_started);
    ID3D11Resource *back = NULL;
    HRESULT got = IDXGISwapChain1_GetBuffer(dxc_swapchain, 0, &IID_ID3D11Resource, (void **)&back);
    if (FAILED(got)) {
        dxc_log_dxgi_failure("GetBuffer", got);
        dxc_wait_for_gpu();
        return;
    }
    ID3D11On12Device_AcquireWrappedResources(dxc_on12, &dxc_wrapped, 1);
    D3D11_BOX drawn = {0, 0, 0, (UINT)dxc_sizing.fitted_width, (UINT)dxc_sizing.fitted_height, 1};
    ID3D11DeviceContext_CopySubresourceRegion(dxc_d3d11_context, back, 0, 0, 0, 0,
                                              dxc_wrapped, 0, &drawn);
    ID3D11On12Device_ReleaseWrappedResources(dxc_on12, &dxc_wrapped, 1);
    ID3D11DeviceContext_Flush(dxc_d3d11_context);
    ID3D11Resource_Release(back);
    dxc_step_copy_ms = dxc_elapsed_ms(copy_started);
    // Interval one outside a resize, so the frame waits for the screen; a window that
    // presents without waiting spends a machine to draw frames nobody sees. Zero inside
    // one, where the DwmFlush that follows paces it instead and a vertical blank waited
    // for first would hold the drag for one more frame.
    dxc_presents_in_change++;
    HRESULT shown = IDXGISwapChain1_Present(dxc_swapchain, dxc_resizing ? 0 : 1, 0);
    if (FAILED(shown)) {
        dxc_log_dxgi_failure("Present", shown);
    }
    dxc_presented_width = dxc_sizing.fitted_width;
    dxc_presented_height = dxc_sizing.fitted_height;

    // No wait for the GPU here. The next frame's Skia work and the copy are on the same
    // queue, which runs them in order, and Skia's submit waits for its own work, which
    // follows this frame's barrier list, before the allocator is reset again. A refit
    // still waits, in dxc_native_frame_begin, before letting go of anything.
    dxc_step_present_ms = dxc_elapsed_ms(present_started) - dxc_step_copy_ms;
    // The native views move once the frame they were laid out in is presented, so the window
    // manager hands DWM their new place for the composition that shows that frame.
    dxc_nv_apply(dxc_w);
}

/** Says where the caret is, in pixels from the window's top left. */
void dxc_native_set_ime_spot(float x, float y) {
    dxc_ime_spot_x = (LONG)x;
    dxc_ime_spot_y = (LONG)y;
    if (dxc_window != NULL && dxc_ime_composing) {
        dxc_position_ime(dxc_window);
    }
}

/*
 * Named by the Kotlin every desktop of the native image shares, and answered here by doing
 * nothing: this window reads a paste through the clipboard call and keeps the system's
 * caption, so a pending paste and the macOS title bar and chrome have nothing to carry here.
 */
int32_t dxc_native_take_paste(char *out, int32_t capacity) {
    (void)out; (void)capacity;
    return 0;
}

void dxc_native_window_title_bar(void *view_pointer, float *out) {
    (void)view_pointer;
    out[0] = 0;
    out[1] = 0;
    out[2] = -1;
    out[3] = -1;
}

void dxc_native_window_chrome(
    int32_t full_size_content,
    int32_t transparent_title_bar,
    int32_t title_hidden,
    int32_t unified_toolbar
) {
    (void)full_size_content;
    (void)transparent_title_bar;
    (void)title_hidden;
    (void)unified_toolbar;
}

// macOS only, like the two above: the accessibility bridge's calls. No reader is listening
// through them here, and there is no value waiting.
int32_t dxc_native_accessibility_active(void) {
    return 0;
}

int32_t dxc_native_accessibility_snapshot(char *out, int32_t capacity) {
    (void)out;
    (void)capacity;
    return 0;
}

int32_t dxc_native_ax_api_probe(int32_t *trusted, int32_t *windows) {
    *trusted = -1;
    *windows = -1;
    return -1;
}

// ---------------------------------------------------------------------------
// Several windows in one process (FR-53)
// ---------------------------------------------------------------------------
//
// The calls that name no window run for the selected one: the window the last
// dxc_native_window_select named, or the one opened last. A caller with several windows
// selects before it calls. The calls that name a window or one of its parts choose it
// themselves.

static int64_t dxc_micros_now(void) {
    static LARGE_INTEGER frequency;
    LARGE_INTEGER now;
    if (frequency.QuadPart == 0) {
        QueryPerformanceFrequency(&frequency);
    }
    QueryPerformanceCounter(&now);
    return (int64_t)(now.QuadPart / frequency.QuadPart) * 1000000LL +
           (int64_t)(now.QuadPart % frequency.QuadPart) * 1000000LL / frequency.QuadPart;
}

static void dxc_select_handle(void *handle) {
    DxcWin *win = (DxcWin *)dxc_table_find(&dxc_table, handle);
    if (win != NULL) {
        dxc_w = win;
    }
}

static void dxc_select_swapchain(void *swapchain) {
    if (swapchain == NULL) return;
    for (int32_t index = 0; index < DXC_MAX_WINDOWS; index++) {
        DxcWin *win = (DxcWin *)dxc_table.states[index];
        if (win != NULL && (void *)win->swapchain == swapchain) {
            dxc_w = win;
            return;
        }
    }
}

static void dxc_select_queue(void *queue) {
    if (queue == NULL) return;
    for (int32_t index = 0; index < DXC_MAX_WINDOWS; index++) {
        DxcWin *win = (DxcWin *)dxc_table.states[index];
        if (win != NULL && (void *)win->queue == queue) {
            dxc_w = win;
            return;
        }
    }
}

void dxc_native_window_select(void *window) {
    dxc_select_handle(window);
}

/** The handle of the window whose message is being handled (or the selected one), 0 if none. */
int64_t dxc_native_current_window(void) {
    return (int64_t)(intptr_t)dxc_w->window;
}

/** How many windows are open: made, and not yet destroyed. */
int32_t dxc_native_window_count(void) {
    return dxc_shared_state.users;
}

/**
 * Where the next window opens, in screen points. Called before dxc_native_window_open and
 * used by that one window; without it the system chooses.
 */
void dxc_native_window_configure_position(int32_t has_position, int32_t x, int32_t y) {
    dxc_next_position.has = has_position != 0;
    dxc_next_position.x = x;
    dxc_next_position.y = y;
}

/**
 * When deferred, the system's request to close the selected window (its close button, Alt+F4,
 * the system menu) is kept for dxc_native_take_close_request and the window stays open until
 * dxc_native_window_destroy. Off, the request closes the window, as it always has.
 */
void dxc_native_window_set_close_deferred(int32_t deferred) {
    dxc_w->close_deferred = deferred != 0;
}

int32_t dxc_native_take_close_request(void) {
    int32_t asked = dxc_w->close_requested ? 1 : 0;
    dxc_w->close_requested = 0;
    return asked;
}

/** Destroys the window. The others, and the process, carry on. */
void dxc_native_window_destroy(void *window) {
    dxc_select_handle(window);
    if (dxc_window != NULL) {
        DestroyWindow(dxc_window);
    }
}

/**
 * Gives back everything the window held, once it is destroyed and nothing draws into it any
 * more: the GPU objects, the bitmap and the state record. The handle is not valid after this.
 */
void dxc_native_window_release(void *window) {
    DxcWin *win = (DxcWin *)dxc_table_find(&dxc_table, window);
    if (win == NULL) return;
    DxcWin *outer = dxc_w;
    dxc_w = win;
    dxc_wait_for_gpu();
    IDXGIAdapter1 *adapter = dxc_adapter;
    dxc_adapter = NULL;
    dxc_abandon_window(adapter);
    if (dxc_dib_dc != NULL) {
        if (dxc_dib != NULL) {
            SelectObject(dxc_dib_dc, dxc_dib_previous);
            DeleteObject(dxc_dib);
            dxc_dib = NULL;
        }
        DeleteDC(dxc_dib_dc);
        dxc_dib_dc = NULL;
    }
    dxc_uncount_window(win);
    dxc_table_remove(&dxc_table, window);
    dxc_w = outer == win ? &dxc_no_window : outer;
    free(win);
}

/** Asks the thread's message loop to end with [code]. Whether the process quits is the application's to decide. */
void dxc_native_post_quit(int32_t code) {
    PostQuitMessage(code);
}

/** 1 once a quit posted by dxc_native_post_quit has come through the pump, with its code. */
int32_t dxc_native_quit_posted(int32_t *code) {
    if (code != NULL) *code = dxc_quit_code;
    return dxc_quit_seen ? 1 : 0;
}

static UINT dxc_dpi_of(HWND window) {
    UINT dpi = window != NULL ? GetDpiForWindow(window) : 0;
    return dpi == 0 ? USER_DEFAULT_SCREEN_DPI : dpi;
}

/** The size of the window's outer frame for content of [width] by [height] points, in pixels. */
static void dxc_outer_size(int32_t width, int32_t height, UINT dpi, int32_t *outer_width, int32_t *outer_height) {
    dxc_outer_for_client(dxc_points_to_pixels(width, dpi), dxc_points_to_pixels(height, dpi), dpi,
                         outer_width, outer_height);
}

void dxc_native_window_set_position(int32_t x, int32_t y) {
    if (dxc_window == NULL) return;
    UINT dpi = dxc_dpi_of(dxc_window);
    SetWindowPos(dxc_window, NULL, dxc_points_to_pixels(x, dpi), dxc_points_to_pixels(y, dpi), 0, 0,
                 SWP_NOZORDER | SWP_NOSIZE | SWP_NOACTIVATE);
}

void dxc_native_window_set_content_size(int32_t width, int32_t height) {
    if (dxc_window == NULL) return;
    int32_t outer_width = 0, outer_height = 0;
    dxc_outer_size(width, height, dxc_dpi_of(dxc_window), &outer_width, &outer_height);
    // Kept in points, so a change of DPI later gives exactly these points at the new scale.
    // Where the window cannot take the size (a maximised window, a minimum), WM_SIZE keeps
    // the pixels it got instead.
    if (!IsZoomed(dxc_window) && !IsIconic(dxc_window)) {
        dxc_w->dpi_size.width = width;
        dxc_w->dpi_size.height = height;
        dxc_w->dpi_size.dpi = USER_DEFAULT_SCREEN_DPI;
    }
    SetWindowPos(dxc_window, NULL, 0, 0, outer_width, outer_height,
                 SWP_NOZORDER | SWP_NOMOVE | SWP_NOACTIVATE);
}

/** The outer frame in screen points. 0 where the window is gone. */
int32_t dxc_native_window_bounds(int32_t *x, int32_t *y, int32_t *width, int32_t *height) {
    RECT rect;
    if (dxc_window == NULL || !GetWindowRect(dxc_window, &rect)) return 0;
    UINT dpi = dxc_dpi_of(dxc_window);
    *x = dxc_pixels_to_points(rect.left, dpi);
    *y = dxc_pixels_to_points(rect.top, dpi);
    *width = dxc_pixels_to_points(rect.right - rect.left, dpi);
    *height = dxc_pixels_to_points(rect.bottom - rect.top, dpi);
    return 1;
}

void dxc_native_window_set_title(const char *title) {
    if (dxc_window == NULL || title == NULL) return;
    wchar_t wide[256];
    if (MultiByteToWideChar(CP_UTF8, 0, title, -1, wide, (int)(sizeof wide / sizeof *wide)) == 0) {
        wide[0] = L'\0';
    }
    SetWindowTextW(dxc_window, wide);
}

void dxc_native_window_set_min_size(int32_t width, int32_t height) {
    dxc_options.min_width = width;
    dxc_options.min_height = height;
}

/** 0 hidden, 1 shown, 2 minimised, 3 full screen (maximised here). */
void dxc_native_window_set_visibility(int32_t visibility) {
    if (dxc_window == NULL) return;
    switch (visibility) {
    case 0: ShowWindow(dxc_window, SW_HIDE); break;
    case 2: ShowWindow(dxc_window, SW_MINIMIZE); break;
    case 3: ShowWindow(dxc_window, SW_MAXIMIZE); break;
    default: ShowWindow(dxc_window, IsIconic(dxc_window) ? SW_RESTORE : SW_SHOW); break;
    }
}

/** 1 when the user's apps theme is the dark one. */
int32_t dxc_native_system_dark(void) {
    DWORD light = 1;
    DWORD size = sizeof light;
    if (RegGetValueW(HKEY_CURRENT_USER, L"Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                     L"AppsUseLightTheme", RRF_RT_REG_DWORD, NULL, &light, &size) != ERROR_SUCCESS) {
        return 0;
    }
    return light == 0 ? 1 : 0;
}

static int dxc_take_foreground(HWND window) {
    if (GetForegroundWindow() == window) return 1;
    SetForegroundWindow(window);
    if (GetForegroundWindow() == window) return 1;
    // The system lets the process that owns the foreground window give it away. Joining that
    // thread's input for a moment makes this process look like it.
    HWND foreground = GetForegroundWindow();
    DWORD current = GetCurrentThreadId();
    DWORD other = foreground != NULL ? GetWindowThreadProcessId(foreground, NULL) : 0;
    BOOL attached = other != 0 && other != current && AttachThreadInput(current, other, TRUE);
    BringWindowToTop(window);
    SetForegroundWindow(window);
    if (attached) AttachThreadInput(current, other, FALSE);
    if (GetForegroundWindow() == window) return 1;
    // A synthetic Alt press counts as input from the user, which is what the lock waits for.
    keybd_event(VK_MENU, 0, 0, 0);
    keybd_event(VK_MENU, 0, KEYEVENTF_KEYUP, 0);
    SetForegroundWindow(window);
    return GetForegroundWindow() == window;
}

/**
 * Brings the window to the front and gives it the keyboard. 1 when it has it. 0 when the
 * foreground lock refused: the window's taskbar button flashes instead, and no activation
 * is reported because none happened.
 */
int32_t dxc_native_window_focus(void) {
    if (dxc_window == NULL) return 0;
    if (IsIconic(dxc_window)) ShowWindow(dxc_window, SW_RESTORE);
    if (dxc_take_foreground(dxc_window)) {
        SetFocus(dxc_window);
        return 1;
    }
    FLASHWINFO flash;
    memset(&flash, 0, sizeof flash);
    flash.cbSize = sizeof flash;
    flash.hwnd = dxc_window;
    flash.dwFlags = FLASHW_TRAY | FLASHW_TIMERNOFG;
    flash.uCount = 3;
    FlashWindowEx(&flash);
    return 0;
}

/** 1 while the window is the active one, as WM_ACTIVATE last said. */
int32_t dxc_native_window_is_active(void) {
    return dxc_w->active ? 1 : 0;
}

/** 1 once for each change of activation since the last call, with the state now. */
int32_t dxc_native_take_activation(int32_t *active) {
    if (!dxc_w->activation_pending) return 0;
    dxc_w->activation_pending = 0;
    *active = dxc_w->active ? 1 : 0;
    return 1;
}

/** 1 once if the window moved or changed size since the last call, with its outer frame in points. */
int32_t dxc_native_take_moved(int32_t *x, int32_t *y, int32_t *width, int32_t *height) {
    if (!dxc_w->moved_pending) return 0;
    dxc_w->moved_pending = 0;
    return dxc_native_window_bounds(x, y, width, height);
}

static int dxc_primary_down(void) {
    // The primary button is the one the user's settings call primary: a swapped mouse has it on the right.
    int key = GetSystemMetrics(SM_SWAPBUTTON) ? VK_RBUTTON : VK_LBUTTON;
    return (GetAsyncKeyState(key) & 0x8000) != 0;
}

/** The pointer in the selected window's points, and whether the primary button is down. 0 where unknown. */
int32_t dxc_native_pointer_on_screen(float *x, float *y, int32_t *primary_down) {
    POINT pointer;
    if (!GetCursorPos(&pointer)) return 0;
    UINT dpi = dxc_window != NULL ? dxc_dpi_of(dxc_window) : GetDpiForSystem();
    *x = (float)pointer.x * 96.0f / (float)dpi;
    *y = (float)pointer.y * 96.0f / (float)dpi;
    *primary_down = dxc_primary_down() ? 1 : 0;
    return 1;
}

/**
 * Starts moving the selected window under the pointer on every pump turn, keeping the point
 * ([anchor_x], [anchor_y]) of its content, in points, under it. 0 when the button is not down.
 */
int32_t dxc_native_follow_start(float anchor_x, float anchor_y) {
    if (dxc_window == NULL || !dxc_primary_down()) return 0;
    dxc_w->following = 1;
    dxc_w->follow_anchor_x = anchor_x;
    dxc_w->follow_anchor_y = anchor_y;
    dxc_w->follow_end_pending = 0;
    return 1;
}

/** 1 once when a follow ended, with whether it was cancelled (Escape). */
int32_t dxc_native_take_follow_end(int32_t *cancelled) {
    if (!dxc_w->follow_end_pending) return 0;
    dxc_w->follow_end_pending = 0;
    *cancelled = dxc_w->follow_ended == DXC_FOLLOW_CANCELLED ? 1 : 0;
    return 1;
}

static void dxc_follow_step(DxcWin *win) {
    if (!win->following || win->window == NULL) return;
    int32_t decision = dxc_follow_decide(dxc_primary_down() ? 1 : 0,
                                         (GetAsyncKeyState(VK_ESCAPE) & 0x8000) != 0 ? 1 : 0);
    if (decision != DXC_FOLLOW_KEEP) {
        win->following = 0;
        win->follow_ended = decision;
        win->follow_end_pending = 1;
        return;
    }
    POINT pointer;
    RECT rect;
    POINT content = {0, 0};
    if (!GetCursorPos(&pointer) || !GetWindowRect(win->window, &rect) ||
        !ClientToScreen(win->window, &content)) {
        return;
    }
    UINT dpi = dxc_dpi_of(win->window);
    int left = dxc_follow_origin(pointer.x, win->follow_anchor_x, dpi, content.x - rect.left);
    int top = dxc_follow_origin(pointer.y, win->follow_anchor_y, dpi, content.y - rect.top);
    if (left != rect.left || top != rect.top) {
        SetWindowPos(win->window, NULL, left, top, 0, 0, SWP_NOZORDER | SWP_NOSIZE | SWP_NOACTIVATE);
    }
}

/** Once a pump turn, for every window that is following the pointer. */
static void dxc_follow_turn(void) {
    for (int32_t index = 0; index < DXC_MAX_WINDOWS; index++) {
        DxcWin *win = (DxcWin *)dxc_table.states[index];
        if (win != NULL) {
            dxc_follow_step(win);
        }
    }
}

// ---------------------------------------------------------------------------
// Native views: platform windows the application made, placed above the scene
// ---------------------------------------------------------------------------
//
// common's NativeViews on Windows (compose-rust FR-81). The application makes its view (an
// HWND, such as a web view's host window) against the top-level window, which is what
// dxc_native_view_parent answers, and hands it over with dxc_native_view_attach. The window
// only places, clips, orders and focuses it: it never destroys it, and when the view leaves
// (detached, or the window closes) it is hidden and moved under the message-only window, where
// it waits for the application to destroy it.
//
// A slot is the view inside a container this window makes (win32_native_views.h says why),
// a child of the top-level window with WS_CLIPSIBLINGS, put above every slot attached before
// it and above the swapchain's child window. The holes are a window region on the container,
// which cuts the picture and sends a click in a hole to the scene below.
//
// On the GDI route the swapchain is on a child window that covers the client area. The
// containers are its siblings above it, and it is given WS_CLIPSIBLINGS with the first slot,
// so it is cut around them; while a size changes it is hidden and the frame is copied into the
// top-level window's GDI surface, cut around the slots too (dxc_nv_exclude). The slots are
// never hidden with it.
//
// On the DirectComposition route the window has no GDI surface (WS_EX_NOREDIRECTIONBITMAP)
// and its frames are a visual on a target made below the window's child windows
// (win32_dcomp.cpp), so DWM composes the slots above the frame. A child window that draws with
// GDI needs a surface of its own there, so the container is a layered child window (Windows 8
// and later, which an application declares in its manifest), and the view draws into the
// container's surface. Where a layered child cannot be made the container is an ordinary one,
// and a view that draws through GDI shows nothing on this route; that is said on standard
// error.
//
// Placement follows the frame. What the caller asks for during a frame's layout is written
// down and given to the windows after the frame is presented (dxc_native_frame_end), copied
// into the window (dxc_raster_copy, during a size change) or committed (the composition
// route), so a view and the frame it was laid out in reach DWM for the same composition as
// far as the window can arrange it. What is asked for with no frame after it is given at the
// end of the next pump.
//
// Focus is the system's: a click in a view gives it the keyboard, and the window reads where
// the keyboard is at the end of each pump (GetFocus) and reports a move into, out of or between
// slots as DXC_EVENT_NATIVE_VIEW_FOCUS_IN and _OUT, the slot id in key_code. A press in the
// scene takes the keyboard back. Deactivated, the window says the slot lost it; activated
// again, it gives the slot the keyboard back and says so.

static const wchar_t *DXC_NV_CONTAINER_CLASS = L"ComposeRustNativeViewSlot";
static int dxc_nv_class_registered;

/** A slot's container: it draws nothing, the view inside covers it. */
static LRESULT CALLBACK dxc_nv_container_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    switch (message) {
    case WM_ERASEBKGND:
        return 1;
    case WM_PAINT: {
        PAINTSTRUCT paint;
        BeginPaint(window, &paint);
        EndPaint(window, &paint);
        return 0;
    }
    default:
        return DefWindowProcW(window, message, wparam, lparam);
    }
}

static int dxc_nv_register_class(void) {
    if (dxc_nv_class_registered) return 1;
    WNDCLASSEXW description;
    memset(&description, 0, sizeof description);
    description.cbSize = sizeof description;
    description.lpfnWndProc = dxc_nv_container_proc;
    description.hInstance = GetModuleHandleW(NULL);
    // No cursor of its own, so the view's is the one set over it.
    description.hCursor = NULL;
    description.hbrBackground = NULL;
    description.lpszClassName = DXC_NV_CONTAINER_CLASS;
    if (RegisterClassExW(&description) == 0 && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
        return 0;
    }
    dxc_nv_class_registered = 1;
    return 1;
}

/** The open window a call names, or NULL. */
static DxcWin *dxc_nv_window(void *window) {
    DxcWin *win = (DxcWin *)dxc_table_find(&dxc_table, window);
    if (win == NULL || win->destroyed || win->window == NULL) return NULL;
    return win;
}

static struct dxc_nv_slot *dxc_nv_find(DxcWin *win, int32_t id) {
    for (int index = 0; index < DXC_NV_MAX_SLOTS; index++) {
        struct dxc_nv_slot *slot = &win->native_views.slots[index];
        if (slot->used && slot->id == id) return slot;
    }
    return NULL;
}

/** The slot [hwnd] is in (its container, its view or a window inside the view), or NULL. */
static struct dxc_nv_slot *dxc_nv_holding(DxcWin *win, HWND hwnd) {
    if (win->native_views.count == 0) return NULL;
    int depth = 0;
    for (HWND at = hwnd; at != NULL && at != win->window && depth < 64; at = GetAncestor(at, GA_PARENT), depth++) {
        for (int index = 0; index < DXC_NV_MAX_SLOTS; index++) {
            struct dxc_nv_slot *slot = &win->native_views.slots[index];
            if (slot->used && slot->container == at) return slot;
        }
    }
    return NULL;
}

/** Whether [hwnd] is a view or a container of a slot of any window of the process. */
static int dxc_nv_taken(HWND hwnd) {
    for (int32_t w = 0; w < DXC_MAX_WINDOWS; w++) {
        DxcWin *win = (DxcWin *)dxc_table.states[w];
        if (win == NULL) continue;
        for (int index = 0; index < DXC_NV_MAX_SLOTS; index++) {
            struct dxc_nv_slot *slot = &win->native_views.slots[index];
            if (slot->used && (slot->view == hwnd || slot->container == hwnd)) return 1;
        }
    }
    return 0;
}

/** Writes an event into [win]'s queue, whichever window is selected. */
static void dxc_nv_push(DxcWin *win, struct dxc_event record) {
    DxcWin *outer = dxc_w;
    dxc_w = win;
    dxc_push_event(record);
    dxc_w = outer;
}

static void dxc_nv_report(DxcWin *win, const struct dxc_nv_focus_event *events, int count) {
    for (int index = 0; index < count; index++) {
        struct dxc_event record;
        memset(&record, 0, sizeof record);
        record.kind = events[index].in ? DXC_EVENT_NATIVE_VIEW_FOCUS_IN : DXC_EVENT_NATIVE_VIEW_FOCUS_OUT;
        record.key_code = events[index].id;
        dxc_nv_push(win, record);
    }
}

static void dxc_nv_push_pointer(DxcWin *win, int32_t kind, float x, float y) {
    struct dxc_event record;
    memset(&record, 0, sizeof record);
    record.kind = kind;
    record.x = x;
    record.y = y;
    record.buttons = dxc_pointer_buttons(dxc_pressed_buttons(), 0);
    record.modifiers = dxc_held_modifiers();
    record.time_millis = dxc_message_time();
    dxc_nv_push(win, record);
}

/** The focus moved to slot [slot] or, for NULL, out of every slot; reported if that changed. */
static void dxc_nv_focus_to(DxcWin *win, struct dxc_nv_slot *slot) {
    struct dxc_nv_focus_event events[2];
    int count = dxc_nv_focus_moved(&win->native_views.focus, slot != NULL, slot != NULL ? slot->id : 0, events);
    dxc_nv_report(win, events, count);
}

/**
 * Reads where the keyboard is and reports a change. Only while the window is active: an
 * inactive window has no focus to read, and the slot that had it is remembered.
 */
static void dxc_nv_sample_focus(DxcWin *win) {
    struct dxc_nv_slots *views = &win->native_views;
    if (views->count == 0 && !views->focus.has_focused) return;
    if (!win->active || win->window == NULL) return;
    HWND focus = GetFocus();
    struct dxc_nv_slot *slot = focus != NULL ? dxc_nv_holding(win, focus) : NULL;
    if (slot != NULL) views->focus_window = focus;
    dxc_nv_focus_to(win, slot);
}

/** Gives the keyboard back to the scene, if a slot has it, and reports it. */
static void dxc_nv_focus_scene(DxcWin *win) {
    struct dxc_nv_slots *views = &win->native_views;
    if (win->active && win->window != NULL) {
        HWND focus = GetFocus();
        if (focus != NULL && dxc_nv_holding(win, focus) != NULL) SetFocus(win->window);
        dxc_nv_sample_focus(win);
    } else {
        views->focus_window = NULL;
        dxc_nv_focus_to(win, NULL);
    }
}

/** Builds the region of [slot]'s container: its bounds with every hole inside them cut out. */
static HRGN dxc_nv_region(struct dxc_nv_rect bounds, const int32_t *holes, int32_t hole_count) {
    if (holes == NULL || hole_count <= 0 || dxc_nv_rect_empty(bounds)) return NULL;
    HRGN region = NULL;
    for (int32_t index = 0; index < hole_count; index++) {
        const int32_t *hole = holes + (size_t)index * 5;
        struct dxc_nv_rect rect = dxc_nv_rect_of(hole[0], hole[1], hole[2], hole[3]);
        struct dxc_nv_rect local;
        if (!dxc_nv_hole_in_container(rect, bounds, &local)) continue;
        if (region == NULL) {
            region = CreateRectRgn(0, 0, bounds.width, bounds.height);
            if (region == NULL) return NULL;
        }
        int32_t radius = dxc_nv_hole_radius(local, hole[4]);
        struct dxc_nv_rect box = dxc_nv_round_region_box(local, radius);
        HRGN cut = radius > 0
            ? CreateRoundRectRgn(box.x, box.y, box.x + box.width, box.y + box.height, radius * 2, radius * 2)
            : CreateRectRgn(box.x, box.y, box.x + box.width, box.y + box.height);
        if (cut != NULL) {
            CombineRgn(region, region, cut, RGN_DIFF);
            DeleteObject(cut);
        }
    }
    return region;
}

/** Gives the windows of every slot what was asked for since the last time. */
static void dxc_nv_apply(DxcWin *win) {
    struct dxc_nv_slots *views = &win->native_views;
    if (!views->pending) return;
    views->pending = 0;
    for (int index = 0; index < DXC_NV_MAX_SLOTS; index++) {
        struct dxc_nv_slot *slot = &views->slots[index];
        if (!slot->used || !slot->pending) continue;
        slot->pending = 0;
        if (slot->region_changed) {
            // The system owns the region from here, and deletes the one it replaces.
            SetWindowRgn(slot->container, slot->region, slot->shown ? TRUE : FALSE);
            slot->region = NULL;
            slot->region_changed = 0;
        }
        if (!dxc_nv_shows(slot->visible, slot->bounds)) {
            if (slot->shown) {
                ShowWindow(slot->container, SW_HIDE);
                slot->shown = 0;
            }
            continue;
        }
        struct dxc_nv_rect view = dxc_nv_view_in_container(slot->frame, slot->bounds);
        if (!slot->placed || !dxc_nv_rect_equal(view, slot->placed_view)) {
            SetWindowPos(slot->view, NULL, view.x, view.y, view.width, view.height,
                         SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOOWNERZORDER);
        }
        UINT flags = SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOOWNERZORDER;
        int moved = !slot->placed || !dxc_nv_rect_equal(slot->bounds, slot->placed_bounds);
        if (!moved) flags |= SWP_NOMOVE | SWP_NOSIZE;
        if (!slot->shown) flags |= SWP_SHOWWINDOW;
        if (moved || !slot->shown) {
            struct dxc_nv_rect b = slot->bounds;
            SetWindowPos(slot->container, NULL, b.x, b.y, b.width, b.height, flags);
        }
        slot->placed = 1;
        slot->placed_view = view;
        slot->placed_bounds = slot->bounds;
        slot->shown = 1;
    }
}

/**
 * Cuts the shown slots out of [dc], a DC of the top-level window: a copy of the scene into the
 * window's GDI surface leaves the views, which draw into the same surface, as they are. Their
 * holes stay in the copy, which is what shows there.
 */
static void dxc_nv_exclude(DxcWin *win, HDC dc) {
    struct dxc_nv_slots *views = &win->native_views;
    if (views->count == 0) return;
    // Everything a copy can reach, less each shown slot's shape (its rectangle, less its holes).
    HRGN keep = NULL;
    for (int index = 0; index < DXC_NV_MAX_SLOTS; index++) {
        struct dxc_nv_slot *slot = &views->slots[index];
        if (!slot->used || !slot->shown) continue;
        struct dxc_nv_rect b = slot->placed_bounds;
        HRGN shape = CreateRectRgn(0, 0, 0, 0);
        if (shape == NULL) continue;
        if (GetWindowRgn(slot->container, shape) != ERROR) {
            OffsetRgn(shape, b.x, b.y);
        } else {
            SetRectRgn(shape, b.x, b.y, b.x + b.width, b.y + b.height);
        }
        if (keep == NULL) keep = CreateRectRgn(0, 0, 32767, 32767);
        if (keep != NULL) CombineRgn(keep, keep, shape, RGN_DIFF);
        DeleteObject(shape);
    }
    if (keep != NULL) {
        SelectClipRgn(dc, keep);
        DeleteObject(keep);
    }
}

/** Whether the keyboard is in [slot] now, whatever was last reported. */
static int dxc_nv_has_keyboard(DxcWin *win, struct dxc_nv_slot *slot) {
    if (win->native_views.focus.has_focused && win->native_views.focus.focused == slot->id) return 1;
    HWND focus = GetFocus();
    return focus != NULL && dxc_nv_holding(win, focus) == slot;
}

/**
 * Takes [slot]'s view out of the window, without destroying it, and frees the slot. [closing]
 * while the window is being destroyed: nothing is given the keyboard and nothing is reported.
 */
static void dxc_nv_take_out(DxcWin *win, struct dxc_nv_slot *slot, int closing) {
    struct dxc_nv_slots *views = &win->native_views;
    if (closing) {
        views->focus.has_focused = 0;
        views->focus.focused = 0;
    } else if (dxc_nv_has_keyboard(win, slot)) {
        // The keyboard goes back to the scene first, and that is reported.
        dxc_nv_focus_scene(win);
    }
    if (views->focus_window != NULL &&
        (views->focus_window == slot->view || IsChild(slot->view, views->focus_window))) {
        views->focus_window = NULL;
    }
    if (IsWindow(slot->view)) {
        ShowWindow(slot->view, SW_HIDE);
        SetParent(slot->view, HWND_MESSAGE);
        DWORD style = (DWORD)GetWindowLongPtrW(slot->view, GWL_STYLE);
        DWORD kept = (style & ~(DWORD)(WS_CHILD | WS_POPUP)) | slot->view_style;
        if (kept != style) SetWindowLongPtrW(slot->view, GWL_STYLE, (LONG_PTR)kept);
    }
    if (slot->region != NULL) DeleteObject(slot->region);
    if (slot->container != NULL && IsWindow(slot->container)) DestroyWindow(slot->container);
    memset(slot, 0, sizeof *slot);
    views->count--;
    if (views->count == 0) views->pointer_in_view = 0;
}

static void dxc_nv_detach_all(DxcWin *win) {
    struct dxc_nv_slots *views = &win->native_views;
    for (int index = 0; index < DXC_NV_MAX_SLOTS && views->count > 0; index++) {
        if (views->slots[index].used) dxc_nv_take_out(win, &views->slots[index], 1);
    }
    views->pending = 0;
}

/** Once a pump turn, for every window with native views. */
static void dxc_nv_turn(void) {
    for (int32_t index = 0; index < DXC_MAX_WINDOWS; index++) {
        DxcWin *win = (DxcWin *)dxc_table.states[index];
        if (win == NULL || win->destroyed || win->window == NULL) continue;
        if (win->native_views.count == 0 && !win->native_views.focus.has_focused) continue;
        dxc_nv_sample_focus(win);
        dxc_nv_apply(win);
    }
}

/** WM_ACTIVATE, after the default handler: say the slot lost or has the keyboard again. */
static void dxc_nv_activated(DxcWin *win, HWND window, int active) {
    struct dxc_nv_slots *views = &win->native_views;
    struct dxc_nv_focus_event events[1];
    if (!active) {
        // The focus is still where it was while this message is handled.
        HWND focus = GetFocus();
        if (focus != NULL && dxc_nv_holding(win, focus) != NULL) views->focus_window = focus;
        dxc_nv_report(win, events, dxc_nv_focus_key(&views->focus, 0, events));
        return;
    }
    if (views->focus.has_focused) {
        struct dxc_nv_slot *slot = dxc_nv_find(win, views->focus.focused);
        HWND back = views->focus_window;
        if (slot == NULL || !slot->visible || back == NULL || !IsWindow(back) || dxc_nv_holding(win, back) != slot) {
            back = slot != NULL && slot->visible ? slot->view : NULL;
        }
        if (back != NULL && !IsIconic(window)) {
            SetFocus(back);
        } else {
            views->focus_window = NULL;
            dxc_nv_focus_to(win, NULL);
        }
    }
    dxc_nv_report(win, events, dxc_nv_focus_key(&views->focus, 1, events));
}

/** A press in the scene: a slot that has the keyboard gives it back. */
static void dxc_nv_scene_pressed(DxcWin *win, HWND window) {
    (void)window;
    if (win->native_views.count == 0) return;
    dxc_nv_focus_scene(win);
}

/** WM_SETCURSOR for a window other than the top-level: 1 when it is in a slot. */
static int dxc_nv_set_cursor(DxcWin *win, HWND window, HWND over) {
    if (dxc_nv_holding(win, over) == NULL) return 0;
    if (!win->native_views.pointer_in_view) {
        win->native_views.pointer_in_view = 1;
        POINT where;
        if (GetCursorPos(&where) && ScreenToClient(window, &where)) {
            dxc_nv_push_pointer(win, DXC_EVENT_POINTER_EXIT, (float)where.x, (float)where.y);
        }
    }
    return 1;
}

/** The pointer is over the scene again after a slot: the scene hears it come in. */
static void dxc_nv_pointer_back(DxcWin *win, LPARAM where) {
    win->native_views.pointer_in_view = 0;
    dxc_nv_push_pointer(win, DXC_EVENT_POINTER_ENTER, (float)GET_X_LPARAM(where), (float)GET_Y_LPARAM(where));
}

/**
 * WM_MOUSELEAVE: the pointer left the client area, maybe for a slot, whose own window may not
 * pass WM_SETCURSOR on to its parent. The scene is told it left only for a slot; a pointer
 * that left the window is what it was before native views.
 */
static void dxc_nv_mouse_left(DxcWin *win, HWND window) {
    if (win->native_views.count == 0 || win->native_views.pointer_in_view) return;
    POINT where;
    if (!GetCursorPos(&where)) return;
    HWND under = WindowFromPoint(where);
    if (under == NULL || under == window || dxc_nv_holding(win, under) == NULL) return;
    dxc_nv_set_cursor(win, window, under);
}

/**
 * The handle a view is made against: the top-level window, 0 once it is closed or for a
 * handle that is not an open window.
 */
int64_t dxc_native_view_parent(void *window) {
    DxcWin *win = dxc_nv_window(window);
    return win != NULL ? (int64_t)(intptr_t)win->window : 0;
}

/**
 * Puts the HWND [handle] into [window] as slot [id], hidden, above every slot attached before
 * it. 1 when taken; 0 when it is not usable here: 0 or not a window, a window of another
 * process, the window itself or one of its own, already in a slot of any window, or [id]
 * already in use.
 */
int32_t dxc_native_view_attach(void *window, int32_t id, int64_t handle) {
    DxcWin *win = dxc_nv_window(window);
    if (win == NULL || handle == 0) return 0;
    struct dxc_nv_slots *views = &win->native_views;
    HWND view = (HWND)(intptr_t)handle;
    if (views->count >= DXC_NV_MAX_SLOTS || dxc_nv_find(win, id) != NULL) return 0;
    if (!IsWindow(view) || view == win->window || view == win->surface || IsChild(view, win->window) ||
        dxc_table_find(&dxc_table, view) != NULL) {
        return 0;
    }
    DWORD process = 0;
    GetWindowThreadProcessId(view, &process);
    if (process != GetCurrentProcessId() || dxc_nv_taken(view) || !dxc_nv_register_class()) return 0;
    struct dxc_nv_slot *slot = NULL;
    for (int index = 0; index < DXC_NV_MAX_SLOTS && slot == NULL; index++) {
        if (!views->slots[index].used) slot = &views->slots[index];
    }
    if (slot == NULL) return 0;
    if (!views->styles_set) {
        // The swapchain's child is cut around its siblings above it, the slots, so DWM shows
        // them over the frame (the GDI route; the composition route has no such child).
        if (win->surface != NULL) {
            LONG_PTR style = GetWindowLongPtrW(win->surface, GWL_STYLE);
            SetWindowLongPtrW(win->surface, GWL_STYLE, style | WS_CLIPSIBLINGS);
        }
        views->styles_set = 1;
    }
    HWND container = NULL;
    DWORD style = WS_CHILD | WS_CLIPSIBLINGS | WS_CLIPCHILDREN;
    if (win->on_comp) {
        container = CreateWindowExW(WS_EX_LAYERED, DXC_NV_CONTAINER_CLASS, L"", style, 0, 0, 0, 0,
                                    win->window, NULL, GetModuleHandleW(NULL), NULL);
        if (container != NULL) {
            SetLayeredWindowAttributes(container, 0, 255, LWA_ALPHA);
        } else {
            fprintf(stderr, "compose-rust: native-view: no layered container (error %lu): a view that draws "
                            "with GDI shows nothing on the composition route\n", (unsigned long)GetLastError());
        }
    }
    if (container == NULL) {
        container = CreateWindowExW(0, DXC_NV_CONTAINER_CLASS, L"", style, 0, 0, 0, 0,
                                    win->window, NULL, GetModuleHandleW(NULL), NULL);
    }
    if (container == NULL) return 0;
    DWORD before = (DWORD)GetWindowLongPtrW(view, GWL_STYLE);
    DWORD as_child = (before & ~(DWORD)WS_POPUP) | (DWORD)WS_CHILD;
    if (as_child != before) SetWindowLongPtrW(view, GWL_STYLE, (LONG_PTR)as_child);
    if (SetParent(view, container) == NULL) {
        if (as_child != before) SetWindowLongPtrW(view, GWL_STYLE, (LONG_PTR)before);
        DestroyWindow(container);
        return 0;
    }
    // Shown inside the container, which is hidden until the slot is placed and shown.
    SetWindowPos(view, NULL, 0, 0, 0, 0, SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOOWNERZORDER | SWP_SHOWWINDOW);
    SetWindowPos(container, HWND_TOP, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_NOOWNERZORDER);
    memset(slot, 0, sizeof *slot);
    slot->used = 1;
    slot->id = id;
    slot->view = view;
    slot->container = container;
    slot->view_style = before & (DWORD)(WS_CHILD | WS_POPUP);
    if (views->count == 0 && !views->focus.has_focused) views->focus.is_key = win->active != 0;
    views->count++;
    // The view may have had the keyboard already, as a child of the window.
    dxc_nv_sample_focus(win);
    return 1;
}

/**
 * Where slot [id] is and what of it shows, in client pixels: its frame, the clip of its
 * ancestors ([has_clip]), and [hole_count] holes, five numbers each (x, y, width, height and
 * the corner radius), where the scene drawn above it shows instead. Given to the windows with
 * the next frame.
 */
void dxc_native_view_place(void *window, int32_t id, int32_t x, int32_t y, int32_t width, int32_t height,
                           int32_t has_clip, int32_t clip_x, int32_t clip_y, int32_t clip_width,
                           int32_t clip_height, const int32_t *holes, int32_t hole_count) {
    DxcWin *win = dxc_nv_window(window);
    if (win == NULL) return;
    struct dxc_nv_slot *slot = dxc_nv_find(win, id);
    if (slot == NULL) return;
    slot->frame = dxc_nv_rect_of(x, y, width, height);
    slot->bounds = dxc_nv_bounds(slot->frame, has_clip != 0, dxc_nv_rect_of(clip_x, clip_y, clip_width, clip_height));
    HRGN region = dxc_nv_region(slot->bounds, holes, hole_count);
    if (region != NULL || slot->wants_region) {
        if (slot->region != NULL) DeleteObject(slot->region);
        slot->region = region;
        slot->region_changed = 1;
        slot->wants_region = region != NULL;
    }
    slot->pending = 1;
    win->native_views.pending = 1;
}

/** Shows or hides slot [id] with the next frame. A hidden slot that had the keyboard gives it to the scene now. */
void dxc_native_view_set_visible(void *window, int32_t id, int32_t visible) {
    DxcWin *win = dxc_nv_window(window);
    if (win == NULL) return;
    struct dxc_nv_slot *slot = dxc_nv_find(win, id);
    if (slot == NULL || slot->visible == (visible != 0)) return;
    if (!visible && dxc_nv_has_keyboard(win, slot)) dxc_nv_focus_scene(win);
    slot->visible = visible != 0;
    slot->pending = 1;
    win->native_views.pending = 1;
}

/**
 * Gives slot [id] the keyboard, if it is shown. While the window is not active the slot is
 * remembered and given the keyboard when it is.
 */
void dxc_native_view_focus(void *window, int32_t id) {
    DxcWin *win = dxc_nv_window(window);
    if (win == NULL) return;
    struct dxc_nv_slot *slot = dxc_nv_find(win, id);
    if (slot == NULL || !slot->visible) return;
    if (win->active) {
        SetFocus(slot->view);
        dxc_nv_sample_focus(win);
    } else {
        win->native_views.focus_window = slot->view;
        dxc_nv_focus_to(win, slot);
    }
}

/** Gives the keyboard back to the scene. */
void dxc_native_view_focus_scene(void *window) {
    DxcWin *win = dxc_nv_window(window);
    if (win != NULL) dxc_nv_focus_scene(win);
}

/** Takes slot [id] out of the window. The view is hidden and kept: the caller destroys it. */
void dxc_native_view_detach(void *window, int32_t id) {
    DxcWin *win = dxc_nv_window(window);
    if (win == NULL) return;
    struct dxc_nv_slot *slot = dxc_nv_find(win, id);
    if (slot != NULL) dxc_nv_take_out(win, slot, 0);
}

/** 1 and the slot in [id] while a slot has the keyboard (or had it when the window was deactivated). */
int32_t dxc_native_view_focused(void *window, int32_t *id) {
    DxcWin *win = dxc_nv_window(window);
    if (win == NULL || !win->native_views.focus.has_focused) return 0;
    if (id != NULL) *id = win->native_views.focus.focused;
    return 1;
}
