#ifndef DXC_WIN32_WINDOW_H
#define DXC_WIN32_WINDOW_H

// The surface of `win32_window.c` and `win32_notifications.c`: what a consumer can call
// and the records it reads back. One header for both consumers. The GraalVM path reaches
// these by symbol name from its Kotlin `@CFunction` wrapper; the Kotlin/Native path
// (MinGW) runs cinterop over this file (`../cinterop/win32_window.def`) and links the
// same object the MSVC compiler made. Nothing here includes `windows.h`, so cinterop and
// any C compiler can read it.

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

int32_t dxc_native_window_open(const char *title, int32_t width, int32_t height,
                               struct dxc_native_window *out);
void dxc_native_window_size(void *window_pointer, int32_t *width, int32_t *height, float *scale);
int32_t dxc_native_frame_begin(void *swapchain_pointer, void **texture_out);
void dxc_native_frame_end(void *queue_pointer);
void dxc_native_set_draw_callback(dxc_draw_frame_fn callback, void *isolate_thread);
void dxc_native_set_frame_callback(void *callback, void *isolate_thread);
void dxc_native_set_cursor(int32_t shape);
void dxc_native_set_accessibility(const struct dxc_element *elements, int32_t count,
                                  void *window_pointer);
void dxc_native_pump(double seconds);
int32_t dxc_native_window_closed(void);
void dxc_native_install_menu(const char *application_name);
void dxc_native_window_caption(void *view_pointer, float *height, float *buttons_width);
void dxc_native_window_action(int32_t action);
void dxc_native_window_begin_drag(int32_t edge);
void dxc_native_set_icon(const uint8_t *rgba, int32_t width, int32_t height);
int32_t dxc_native_clipboard_read(char *out, int32_t capacity);
void dxc_native_clipboard_write(const char *text);
int32_t dxc_native_dropped_paths(char *out, int32_t capacity);
void dxc_native_debug_key(void *window_pointer, int32_t key_code, const char *characters);
int32_t dxc_native_poll_event(struct dxc_event *out);
void dxc_native_set_ime_spot(float x, float y);

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
