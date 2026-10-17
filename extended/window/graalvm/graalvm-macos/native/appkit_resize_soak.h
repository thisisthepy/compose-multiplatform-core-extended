// DXC_RESIZE_SOAK=<rounds>: a long drag of the window's edge, for telling memory that levels
// off from memory that keeps growing. The same soak the Kotlin/Native window runs
// (ResizeSoak.kt in native/macos), with the same lines, so one reader judges both.
//
// Each round is one drag made of mouse events, as a hand makes it: the right edge is
// pressed, moved 450 points out and back in 100 events at 60 a second, and let go. AppKit
// runs its own live resize for that, so every event is a frame drawn at a new size from
// inside the resize. Memory is read with the button still held at the end of each round
// ("held") and two seconds after it is let go ("settled"), once the run loop has turned and
// the window server has given back the surfaces of the sizes it left. Five seconds of rest
// come first and are read twice ("idle"). After the last round there is one more reading
// two seconds on ("collected"), which the renderer can take a collection before.
//
// The heap is the image's and is not visible from here: the renderer prints its own line
// for it ("compose-rust: soak heap ...") while a soak is running, see dxc_native_soak_state.
//
// Nothing here runs unless asked for, and it touches nothing the window does on its own.
#ifndef DXC_APPKIT_RESIZE_SOAK_H
#define DXC_APPKIT_RESIZE_SOAK_H

#import <AppKit/AppKit.h>
#import <Metal/Metal.h>
#include <mach/mach.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

#define DXC_SOAK_EVENTS 100
#define DXC_SOAK_DISTANCE 450.0

static int dxc_soak_rounds(void) {
    static int cached = -1;
    if (cached < 0) {
        const char *value = getenv("DXC_RESIZE_SOAK");
        cached = value != NULL ? atoi(value) : 0;
        if (cached < 0) cached = 0;
    }
    return cached;
}

// The phase the soak is in, for the renderer's heap line: 0 not running or done, 1 running,
// 2 the last round has ended and a collection is wanted before the final reading.
static volatile int dxc_soak_state;

/** What the renderer polls to know whether to print its heap line and when to collect. */
int32_t dxc_native_soak_state(void) { return dxc_soak_state; }

static long dxc_soak_mb(long long bytes) { return bytes < 0 ? -1 : (long)((bytes + 524288) / 1048576); }

static void dxc_soak_read(const char *phase, int round, NSWindow *window, id<MTLDevice> device,
                          const char *extra, int breakdown) {
    task_vm_info_data_t info;
    mach_msg_type_number_t count = TASK_VM_INFO_COUNT;
    long long footprint = -1, resident = -1;
    if (task_info(mach_task_self(), TASK_VM_INFO, (task_info_t)&info, &count) == KERN_SUCCESS) {
        footprint = (long long)info.phys_footprint;
        resident = (long long)info.resident_size;
    }
    fprintf(stderr,
            "compose-rust: soak phase %s round=%d resizes=%d width=%d footprint_mb=%ld "
            "resident_mb=%ld metal_allocated_mb=%ld%s%s\n",
            phase, round, round * DXC_SOAK_EVENTS, (int)window.frame.size.width,
            dxc_soak_mb(footprint), dxc_soak_mb(resident),
            dxc_soak_mb((long long)device.currentAllocatedSize), extra[0] ? " " : "", extra);
    fflush(stderr);
    const char *wanted = getenv("DXC_METRICS_BREAKDOWN");
    if (breakdown && wanted != NULL && strcmp(wanted, "1") == 0) {
        char command[256];
        snprintf(command, sizeof command,
                 "/usr/bin/footprint %d 2>/dev/null | sed 's/^/compose-rust: soak breakdown round=%d %s /' 1>&2",
                 (int)getpid(), round, phase);
        system(command);
    }
}

static void dxc_soak_after(double seconds, void (^block)(void)) {
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(seconds * NSEC_PER_SEC)),
                   dispatch_get_main_queue(), block);
}

static void dxc_soak_post(NSWindow *window, NSEventType type, double x, double y) {
    NSEvent *event = [NSEvent mouseEventWithType:type
                                        location:NSMakePoint(x, y)
                                   modifierFlags:0
                                       timestamp:NSProcessInfo.processInfo.systemUptime
                                    windowNumber:window.windowNumber
                                         context:nil
                                     eventNumber:0
                                      clickCount:1
                                        pressure:1];
    if (event != nil) [NSApp postEvent:event atStart:NO];
}

static void dxc_soak_round(int number, NSWindow *window, id<MTLDevice> device) {
    double start_width = window.frame.size.width;
    double y = window.frame.size.height / 2;
    // Two points inside the edge, which AppKit counts as the edge.
    double x0 = start_width - 2;
    __block int sent = 0;
    __block double widest = start_width;
    dxc_soak_post(window, NSEventTypeLeftMouseDown, x0, y);
    NSTimer *timer = [NSTimer timerWithTimeInterval:1.0 / 60 repeats:YES block:^(NSTimer *t) {
        if (sent < DXC_SOAK_EVENTS) {
            sent++;
            // Out for the first half, back for the second, so every round ends where it
            // started and the window's own size is not what grows.
            int half = DXC_SOAK_EVENTS / 2;
            double along = sent <= half ? (double)sent / half : (double)(DXC_SOAK_EVENTS - sent) / half;
            dxc_soak_post(window, NSEventTypeLeftMouseDragged, x0 + DXC_SOAK_DISTANCE * along, y);
            if (window.frame.size.width > widest) widest = window.frame.size.width;
            return;
        }
        [t invalidate];
        char extra[64];
        snprintf(extra, sizeof extra, "widened_by=%d", (int)(widest - start_width));
        dxc_soak_read("held", number, window, device, extra, 0);
        dxc_soak_post(window, NSEventTypeLeftMouseUp, x0, y);
        dxc_soak_after(2, ^{
            dxc_soak_read("settled", number, window, device, "", 1);
            if (number < dxc_soak_rounds()) {
                dxc_soak_round(number + 1, window, device);
            } else {
                dxc_soak_state = 2;
                dxc_soak_after(2, ^{
                    dxc_soak_read("collected", number, window, device, "", 1);
                    dxc_soak_state = 0;
                    fprintf(stderr, "compose-rust: soak done rounds=%d\n", dxc_soak_rounds());
                    fflush(stderr);
                });
            }
        });
    }];
    [[NSRunLoop currentRunLoop] addTimer:timer forMode:NSRunLoopCommonModes];
}

/** Starts the soak on the main thread, if one was asked for. */
static void dxc_soak_start(NSWindow *window, id<MTLDevice> device) {
    if (dxc_soak_rounds() == 0) return;
    dxc_soak_state = 1;
    dxc_soak_after(5, ^{
        dxc_soak_read("idle", 0, window, device, "", 0);
        dxc_soak_after(2, ^{
            dxc_soak_read("idle", 0, window, device, "", 1);
            dxc_soak_round(1, window, device);
        });
    });
}

#endif
