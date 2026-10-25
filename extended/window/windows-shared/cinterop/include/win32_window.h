#ifndef DXC_WIN32_WINDOW_H
#define DXC_WIN32_WINDOW_H

// The surface of `c/win32_window.c` and `c/win32_notifications.c`: what a consumer can call
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
