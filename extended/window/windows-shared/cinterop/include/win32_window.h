#ifndef DXC_WIN32_WINDOW_H
#define DXC_WIN32_WINDOW_H

// The surface of `c/win32_window.c`, `c/win32_file_dialog.c` and `c/win32_notifications.c`: what a consumer can call
// and the records it reads back. One header for both consumers. The GraalVM path reaches
// these by symbol name from its Kotlin `@CFunction` wrapper; the Kotlin/Native path runs
// cinterop over this file (`../win32_window.def`) and is linked, by MSVC, against the object
// MSVC compiled from the C. Nothing here includes `windows.h`, so cinterop and any C compiler
// can read it.
//
// The C file declares its records itself and does not include this header, so the two are
// kept in step by c/test/win32_shared_test.c, which links against the object and checks the
// record sizes the Kotlin reader assumes.

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

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
};

struct dxc_element {
    int32_t role;
    // In pixels from the top left of the client area, which is what the scene
    // measures in on this platform.
    float x;
    float y;
    float width;
    float height;
    char label[DXC_TEXT_BYTES];
};

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
};

struct dxc_native_window {
    void *window;
    void *device;
    void *queue;
    void *adapter;
    void *swapchain;
};

typedef void (*dxc_draw_frame_fn)(void *isolate_thread);

// Opening the window, and what it is made of.
void dxc_native_window_configure(int32_t resizable, int32_t min_width, int32_t min_height,
                                 int32_t system_chrome, int32_t backdrop);
int32_t dxc_native_window_open(const char *title, int32_t width, int32_t height,
                               struct dxc_native_window *out);
void dxc_native_window_size(void *window_pointer, int32_t *width, int32_t *height, float *scale);
void dxc_native_window_chrome(int32_t full_size_content, int32_t transparent_title_bar,
                              int32_t title_hidden, int32_t unified_toolbar);
void dxc_native_window_caption(void *view_pointer, float *height, float *buttons_width);
void dxc_native_window_title_bar(void *view_pointer, float *out);
// macOS only, defined here because the native image's shared Kotlin declares them: the
// accessibility bridge's calls. No reader listens through them on this platform.
int32_t dxc_native_accessibility_active(void);
int32_t dxc_native_take_accessibility_argument(char *out, int32_t capacity);
int32_t dxc_native_accessibility_snapshot(char *out, int32_t capacity);
int32_t dxc_native_ax_api_probe(int32_t *trusted, int32_t *windows);
void dxc_native_window_action(int32_t action);

/** 1 while the window is maximised, so the renderer draws restore rather than maximise. */
int32_t dxc_native_window_is_zoomed(void);
void dxc_native_window_begin_drag(int32_t edge);
void dxc_native_set_icon(const uint8_t *rgba, int32_t width, int32_t height);
void dxc_native_install_menu(const char *application_name);

// Frames. The GPU frame is a swapchain buffer; while the window changes size the frame is
// drawn on the CPU into the pixels raster_begin hands out and copied into the window before
// the resize returns (frame_mode says which one is wanted now).
int32_t dxc_native_frame_begin(void *swapchain_pointer, void **texture_out);
void dxc_native_frame_end(void *queue_pointer);
void dxc_native_set_draw_callback(dxc_draw_frame_fn callback, void *isolate_thread);
void dxc_native_set_frame_callback(void *callback, void *isolate_thread);
void dxc_native_set_raster_resize(int32_t enabled);
int32_t dxc_native_frame_mode(void);
int32_t dxc_native_raster_begin(void **pixels, int32_t *row_bytes, int32_t *width, int32_t *height);
void dxc_native_raster_end(void);
// When the frame drawn now will be on the screen, in nanoseconds on the performance counter.
int64_t dxc_native_frame_time_nanos(void);

// The X11 window's calls, which the native image also carries here. Each answers nothing.
void dxc_native_wake(void);
void dxc_native_window_origin(int32_t *x, int32_t *y);
int32_t dxc_native_window_focused(void);
int32_t dxc_native_ime_reset(char *out, int32_t capacity);
int64_t dxc_native_resize_serial(void);
void dxc_native_window_server_size(int32_t *width, int32_t *height);

// The message loop and what it heard.
void dxc_native_pump(double seconds);
int32_t dxc_native_window_closed(void);
int32_t dxc_native_poll_event(struct dxc_event *out);

// Input, text and the system's own services.
void dxc_native_set_cursor(int32_t shape);
void dxc_native_set_ime_spot(float x, float y);
void dxc_native_set_accessibility(const struct dxc_element *elements, int32_t count,
                                  void *window_pointer);
int32_t dxc_native_clipboard_read(char *out, int32_t capacity);
void dxc_native_clipboard_write(const char *text);
int32_t dxc_native_take_paste(char *out, int32_t capacity);
int32_t dxc_native_dropped_paths(char *out, int32_t capacity);
int32_t dxc_native_context_menu(void *window_pointer, const char *items);

// Measurement and checks, all switched on by environment variables of the application.
void dxc_native_report_metrics(const char *label);
void dxc_native_debug_resize(void *window_pointer, void *view_pointer, int32_t from_width,
                             int32_t from_height, int32_t to_width, int32_t to_height,
                             int32_t steps, int32_t pause_micros);
void dxc_native_debug_hit_test(void *window_pointer);
void dxc_native_debug_key(void *window_pointer, int32_t key_code, const char *characters);

// Several windows in one process (FR-53). Every call above that names no window runs for the
// selected one: the window opened last, or the one dxc_native_window_select named. A caller
// with several windows selects before it calls. Positions and sizes are screen points at the
// DPI of the window the call is about; the pump serves the whole process, so a caller pumps
// once per turn on any one window.
void dxc_native_window_select(void *window);
// The handle of the window whose message is being handled, so a draw callback finds its window.
int64_t dxc_native_current_window(void);
int32_t dxc_native_window_count(void);
// Where the next window opens; without a call the system chooses.
void dxc_native_window_configure_position(int32_t has_position, int32_t x, int32_t y);
// Closing. Deferred, the system's request to close is kept for take_close_request and the window
// stays until destroy; not deferred, a request closes it, as it always has. Destroying one window
// never ends the process: whether to quit is the application's, through post_quit.
void dxc_native_window_set_close_deferred(int32_t deferred);
int32_t dxc_native_take_close_request(void);
void dxc_native_window_destroy(void *window);
void dxc_native_window_release(void *window);
void dxc_native_post_quit(int32_t code);
int32_t dxc_native_quit_posted(int32_t *code);
// Placement.
void dxc_native_window_set_position(int32_t x, int32_t y);
void dxc_native_window_set_content_size(int32_t width, int32_t height);
int32_t dxc_native_window_bounds(int32_t *x, int32_t *y, int32_t *width, int32_t *height);
void dxc_native_window_set_title(const char *title);
void dxc_native_window_set_min_size(int32_t width, int32_t height);
void dxc_native_window_set_visibility(int32_t visibility);
int32_t dxc_native_system_dark(void);
// Changing the selected window after it opened (FR-53). set_resizable returns 0 with no window;
// set_color_scheme takes 0 follow the system, 1 light, 2 dark and returns 1 when DWM took it;
// caption_dark writes what the caption was last told and returns 1 once a scheme was applied.
int32_t dxc_native_window_set_resizable(int32_t resizable);
int32_t dxc_native_window_set_color_scheme(int32_t scheme);
int32_t dxc_native_window_caption_dark(int32_t *dark);
// Focus. Focus returns 0 when the foreground lock refused and the window flashed instead.
int32_t dxc_native_window_focus(void);
int32_t dxc_native_window_is_active(void);
// What happened since the last call, each reported once: an activation change with the state now,
// a move or resize with the outer frame, and the end of a follow.
int32_t dxc_native_take_activation(int32_t *active);
int32_t dxc_native_take_moved(int32_t *x, int32_t *y, int32_t *width, int32_t *height);
// The pointer and moving a window under it.
int32_t dxc_native_pointer_on_screen(float *x, float *y, int32_t *primary_down);
int32_t dxc_native_follow_start(float anchor_x, float anchor_y);
int32_t dxc_native_take_follow_end(int32_t *cancelled);

// File open and save dialogs, win32_file_dialog.c: the Common Item Dialog, owned by a window.
// A dialog runs on a thread of its own, so the window goes on pumping and drawing while it is
// open; only input to the owning window waits. Show returns at once, and every dialog it
// started answers exactly once: the answer waits on a list which the thread that pumps reads
// with next and take after each pump (the pump is woken when an answer arrives). Requests are
// the caller's numbers. One dialog per window at a time is the caller's to keep.
enum {
    DXC_FILE_DIALOG_OPEN_FILE = 1,
    DXC_FILE_DIALOG_OPEN_FILES = 2,
    DXC_FILE_DIALOG_OPEN_FOLDER = 3,
    DXC_FILE_DIALOG_OPEN_FOLDERS = 4,
    DXC_FILE_DIALOG_SAVE = 5,
};
enum {
    DXC_FILE_DIALOG_CHOSEN = 1,
    DXC_FILE_DIALOG_CANCELLED = 2,
    // No dialog could be shown, or what was chosen cannot be carried: a path with no UTF-8
    // form, more than 10000 paths or more than 32 MiB of them. Never a part of a selection.
    DXC_FILE_DIALOG_UNAVAILABLE = 3,
};
// 0 when the dialog was started; otherwise nothing was started and no answer comes. Text is
// UTF-8 and empty means the system's choice. filters is one line per file type: the label, a
// tab, and the extensions without dots separated by commas ("Images\tpng,jpg\nAll files");
// no extensions is every file. A save asks before replacing a file and adds the selected
// type's first extension to a name typed without one. Call on the thread that pumps.
int32_t dxc_native_file_dialog_show(void *window, int64_t request, int32_t kind, const char *title,
                                    const char *directory, const char *name, const char *filters,
                                    int32_t default_filter);
// Asks an open dialog to close. Its answer still comes, once, as cancelled. A dialog whose
// owner is destroyed closes the same way by itself.
void dxc_native_file_dialog_cancel(int64_t request);
// The oldest answer not taken: 1 and its request, status, selected type (from 0, -1 for none),
// path count and the bytes take needs. 0 when there is none.
int32_t dxc_native_file_dialog_next(int64_t *request, int32_t *status, int32_t *filter_index,
                                    int32_t *path_count, int32_t *path_bytes);
// Copies the paths of that answer, absolute, UTF-8, a NUL between one and the next, forgets
// the answer and returns the bytes written. -1 when there is no such answer or no room.
int32_t dxc_native_file_dialog_take(int64_t request, char *out, int32_t capacity);

// The toasts, win32_notifications.c. The application defines
// compose_rust_renderer_request_frame(void), which the toasts call when an answer arrives.
int32_t dxc_notify_start(void);
void dxc_notify_request_permission(void);
void dxc_notify_refresh_permission(void);
void dxc_notify_post(const char *key, const char *title, const char *body, const char *channel,
                     const char *action1, const char *action2, int32_t urgent);
void dxc_notify_withdraw(const char *key);
void dxc_notify_withdraw_all(void);
int32_t dxc_notify_next_event(char *key, int32_t capacity, int32_t *value);

#ifdef __cplusplus
}
#endif

#endif
