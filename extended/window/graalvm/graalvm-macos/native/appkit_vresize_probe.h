// DXC_VRESIZE_PROBE=1: drags the window's bottom edge and then its top edge with mouse
// events, width unchanged, and watches from another thread where the content lands on the
// screen while it happens.
//
// Every frame gets three marks laid over it after the scene has drawn it, at fixed places
// counted from the frame's top left corner: a magenta square 64 points down, a cyan one 32
// points up from the frame's bottom edge, and two rows of 16 blocks that spell the frame's
// number and the height it was drawn at. A second thread takes the window's own image
// through the public CGWindowListCreateImage as fast as it can, finds the magenta square,
// and prints how far below the top of the window it is. Content that stays put on the screen
// keeps that number constant through the drag; content that jumps does not, and the rows say
// which frame was on the screen when it did and what height it had been drawn for.
//
// The main thread prints, at each event of the drag, the window's frame, its content layout
// rectangle, the view's bounds, and the layer's bounds, anchor point, gravity, flip and
// drawable size, so a moved frame can be matched with the geometry around it.
//
// Nothing here runs unless asked for, and nothing it prints is read by the window itself.
#ifndef DXC_APPKIT_VRESIZE_PROBE_H
#define DXC_APPKIT_VRESIZE_PROBE_H

#import <AppKit/AppKit.h>
#import <Metal/Metal.h>
#import <QuartzCore/QuartzCore.h>
#include <dlfcn.h>
#include <mach/mach_time.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>

// Where the marks are, in points from the frame's top left (the cyan one from its bottom).
#define DXC_VPROBE_MARK_X 40
#define DXC_VPROBE_MARK_TOP 64
#define DXC_VPROBE_MARK_SIZE 16
#define DXC_VPROBE_MARK_BOTTOM 32
#define DXC_VPROBE_BITS_TOP 88
#define DXC_VPROBE_BIT_W 4
#define DXC_VPROBE_BIT_H 6
#define DXC_VPROBE_BITS 16

typedef CGImageRef (*dxc_vprobe_capture_fn)(CGRect, uint32_t, uint32_t, uint32_t);

static atomic_int dxc_vprobe_frame_count;
static atomic_int dxc_vprobe_sampling;
static atomic_int dxc_vprobe_last_height;
static uint32_t dxc_vprobe_window_number;
static double dxc_vprobe_scale = 1;
static uint64_t dxc_vprobe_started;

static int dxc_vprobe_enabled(void) {
    static int cached = -1;
    if (cached < 0) cached = getenv("DXC_VRESIZE_PROBE") != NULL ? 1 : 0;
    return cached;
}

static double dxc_vprobe_ms(void) {
    static mach_timebase_info_data_t base;
    if (base.denom == 0) mach_timebase_info(&base);
    return (double)(mach_absolute_time() - dxc_vprobe_started) * base.numer / base.denom / 1.0e6;
}

// One solid colour, as a buffer a blit can copy a rectangle of. BGRA, as the drawable is.
static id<MTLBuffer> dxc_vprobe_colour(id<MTLDevice> device, int index, uint32_t bgra) {
    static id<MTLBuffer> buffers[4];
    if (buffers[index] == nil) {
        enum { SIDE = 128 };
        buffers[index] = [device newBufferWithLength:SIDE * SIDE * 4 options:MTLResourceStorageModeShared];
        uint32_t *pixels = buffers[index].contents;
        for (int i = 0; i < SIDE * SIDE; i++) pixels[i] = bgra;
    }
    return buffers[index];
}

static void dxc_vprobe_fill(id<MTLBlitCommandEncoder> blit, id<MTLTexture> texture,
                            id<MTLBuffer> colour, long x, long y, long w, long h) {
    if (x < 0 || y < 0 || w <= 0 || h <= 0) return;
    if (x + w > (long)texture.width || y + h > (long)texture.height) return;
    if (w > 128 || h > 128) return;
    [blit copyFromBuffer:colour
            sourceOffset:0
       sourceBytesPerRow:(NSUInteger)w * 4
     sourceBytesPerImage:(NSUInteger)(w * h * 4)
              sourceSize:MTLSizeMake((NSUInteger)w, (NSUInteger)h, 1)
               toTexture:texture
        destinationSlice:0
        destinationLevel:0
       destinationOrigin:MTLOriginMake((NSUInteger)x, (NSUInteger)y, 0)];
}

static void dxc_vprobe_bits(id<MTLBlitCommandEncoder> blit, id<MTLTexture> texture,
                            id<MTLBuffer> white, id<MTLBuffer> black, int row, uint32_t value,
                            double scale) {
    long top = lround((DXC_VPROBE_BITS_TOP + row * DXC_VPROBE_BIT_H) * scale);
    long h = lround(DXC_VPROBE_BIT_H * scale);
    for (int bit = 0; bit < DXC_VPROBE_BITS; bit++) {
        long left = lround((DXC_VPROBE_MARK_X + bit * DXC_VPROBE_BIT_W) * scale);
        long w = lround(DXC_VPROBE_BIT_W * scale);
        int on = (value >> (DXC_VPROBE_BITS - 1 - bit)) & 1;
        dxc_vprobe_fill(blit, texture, on ? white : black, left, top, w, h);
    }
}

/**
 * Lays the marks over a frame the scene has drawn into [texture], which is [height] pixels
 * of content tall (the drawable can be taller than what was drawn).
 */
static void dxc_vprobe_mark(id<MTLCommandBuffer> buffer, id<MTLTexture> texture, long height,
                            double scale) {
    if (!dxc_vprobe_enabled() || buffer == nil || texture == nil) return;
    int frame = atomic_fetch_add(&dxc_vprobe_frame_count, 1) + 1;
    atomic_store(&dxc_vprobe_last_height, (int)height);
    id<MTLDevice> device = texture.device;
    id<MTLBuffer> magenta = dxc_vprobe_colour(device, 0, 0xFFFF00FF);
    id<MTLBuffer> cyan = dxc_vprobe_colour(device, 1, 0xFFFFFF00);
    id<MTLBuffer> white = dxc_vprobe_colour(device, 2, 0xFFFFFFFF);
    id<MTLBuffer> black = dxc_vprobe_colour(device, 3, 0xFF000000);
    id<MTLBlitCommandEncoder> blit = [buffer blitCommandEncoder];
    long side = lround(DXC_VPROBE_MARK_SIZE * scale);
    long left = lround(DXC_VPROBE_MARK_X * scale);
    dxc_vprobe_fill(blit, texture, magenta, left, lround(DXC_VPROBE_MARK_TOP * scale), side, side);
    dxc_vprobe_fill(blit, texture, cyan, left,
                    height - lround(DXC_VPROBE_MARK_BOTTOM * scale) - side, side, side);
    dxc_vprobe_bits(blit, texture, white, black, 0, (uint32_t)frame, scale);
    dxc_vprobe_bits(blit, texture, white, black, 1, (uint32_t)height, scale);
    [blit endEncoding];
    if (atomic_load(&dxc_vprobe_sampling)) {
        fprintf(stderr, "compose-rust: vresize drawn t_ms=%.2f frame=%d texture=%lux%lu height_px=%ld\n",
                dxc_vprobe_ms(), frame, (unsigned long)texture.width,
                (unsigned long)texture.height, height);
    }
}

static int dxc_vprobe_is(const uint8_t *p, int r, int g, int b) {
    return abs(p[0] - r) < 48 && abs(p[1] - g) < 48 && abs(p[2] - b) < 48;
}

/** Reads one row of blocks, located from where the magenta square was found. */
static long dxc_vprobe_read_bits(const uint8_t *rgba, size_t width, size_t height,
                                 long marker_top_px, int row, double scale) {
    long center_y = marker_top_px +
        lround((DXC_VPROBE_BITS_TOP - DXC_VPROBE_MARK_TOP + row * DXC_VPROBE_BIT_H +
                DXC_VPROBE_BIT_H / 2.0) * scale);
    if (center_y < 0 || center_y >= (long)height) return -1;
    long value = 0;
    for (int bit = 0; bit < DXC_VPROBE_BITS; bit++) {
        long x = lround((DXC_VPROBE_MARK_X + bit * DXC_VPROBE_BIT_W + DXC_VPROBE_BIT_W / 2.0) * scale);
        if (x < 0 || x >= (long)width) return -1;
        const uint8_t *p = rgba + ((size_t)center_y * width + (size_t)x) * 4;
        value = (value << 1) | ((p[0] + p[1] + p[2]) > 3 * 128 ? 1 : 0);
    }
    return value;
}

/** Takes the window's image once and prints where the marks are on it. */
static void dxc_vprobe_sample(dxc_vprobe_capture_fn capture, int index) {
    double at = dxc_vprobe_ms();
    // kCGWindowListOptionIncludingWindow; kCGWindowImageBoundsIgnoreFraming and
    // kCGWindowImageBestResolution, so one pixel of the image is one backing pixel.
    CGImageRef image = capture(CGRectNull, 1 << 3, dxc_vprobe_window_number, (1 << 0) | (1 << 3));
    if (image == NULL) {
        fprintf(stderr, "compose-rust: vresize sample i=%d t_ms=%.2f error=no-image\n", index, at);
        return;
    }
    size_t width = CGImageGetWidth(image), height = CGImageGetHeight(image);
    uint8_t *rgba = calloc(width * height * 4, 1);
    CGColorSpaceRef space = CGColorSpaceCreateDeviceRGB();
    CGContextRef context = CGBitmapContextCreate(rgba, width, height, 8, width * 4, space,
                                                 (CGBitmapInfo)kCGImageAlphaPremultipliedLast);
    CGContextDrawImage(context, CGRectMake(0, 0, width, height), image);
    CGContextRelease(context);
    CGColorSpaceRelease(space);
    CGImageRelease(image);

    double scale = dxc_vprobe_scale;
    long column = lround((DXC_VPROBE_MARK_X + DXC_VPROBE_MARK_SIZE / 2.0) * scale);
    long top = -1, bottom = -1, cyan_bottom = -1;
    if (column < (long)width) {
        for (long y = 0; y < (long)height; y++) {
            const uint8_t *p = rgba + ((size_t)y * width + (size_t)column) * 4;
            if (dxc_vprobe_is(p, 255, 0, 255)) {
                if (top < 0) top = y;
                bottom = y;
            } else if (top >= 0) {
                break;
            }
        }
        for (long y = (long)height - 1; y >= 0; y--) {
            const uint8_t *p = rgba + ((size_t)y * width + (size_t)column) * 4;
            if (dxc_vprobe_is(p, 0, 255, 255)) {
                cyan_bottom = y;
                break;
            }
        }
    }
    long frame = top >= 0 ? dxc_vprobe_read_bits(rgba, width, height, top, 0, scale) : -1;
    long drawn = top >= 0 ? dxc_vprobe_read_bits(rgba, width, height, top, 1, scale) : -1;
    free(rgba);
    fprintf(stderr,
            "compose-rust: vresize sample i=%d t_ms=%.2f image=%zux%zu scale=%.2f found=%d "
            "marker_top_pt=%.2f marker_h_pt=%.2f bottom_gap_pt=%.2f shown_frame=%ld "
            "shown_height_px=%ld image_height_px=%zu last_drawn_frame=%d last_drawn_height_px=%d\n",
            index, at, width, height, scale, top >= 0 ? 1 : 0, top >= 0 ? top / scale : -1.0,
            top >= 0 ? (bottom - top + 1) / scale : -1.0,
            cyan_bottom >= 0 ? ((long)height - 1 - cyan_bottom) / scale : -1.0, frame, drawn,
            height, atomic_load(&dxc_vprobe_frame_count), atomic_load(&dxc_vprobe_last_height));
}

static void dxc_vprobe_geometry(NSWindow *window, NSView *view, CAMetalLayer *layer,
                                const char *edge, int step) {
    NSRect frame = window.frame;
    NSRect layout = window.contentLayoutRect;
    NSRect bounds = view.bounds;
    CGRect layer_bounds = layer.bounds;
    CGPoint anchor = layer.anchorPoint;
    CGPoint position = layer.position;
    CGSize drawable = layer.drawableSize;
    int flips = 0;
    for (CALayer *above = layer; above != nil; above = above.superlayer) {
        if (above.geometryFlipped) flips++;
    }
    fprintf(stderr,
            "compose-rust: vresize step edge=%s i=%d t_ms=%.2f window_frame=%.1f,%.1f,%.1fx%.1f "
            "content_layout=%.1f,%.1f,%.1fx%.1f view_bounds=%.1fx%.1f layer_bounds=%.1fx%.1f "
            "layer_position=%.1f,%.1f anchor=%.2f,%.2f gravity=%s layer_flipped=%d flips=%d "
            "drawable=%.0fx%.0f frames_drawn=%d live=%d\n",
            edge, step, dxc_vprobe_ms(), frame.origin.x, frame.origin.y, frame.size.width,
            frame.size.height, layout.origin.x, layout.origin.y, layout.size.width,
            layout.size.height, bounds.size.width, bounds.size.height, layer_bounds.size.width,
            layer_bounds.size.height, position.x, position.y, anchor.x, anchor.y,
            layer.contentsGravity.UTF8String, layer.geometryFlipped ? 1 : 0, flips,
            drawable.width, drawable.height, atomic_load(&dxc_vprobe_frame_count),
            view.inLiveResize ? 1 : 0);
}

static void dxc_vprobe_post(NSWindow *window, NSEventType type, NSPoint screen) {
    NSPoint origin = window.frame.origin;
    NSEvent *event = [NSEvent mouseEventWithType:type
                                        location:NSMakePoint(screen.x - origin.x, screen.y - origin.y)
                                   modifierFlags:0
                                       timestamp:NSProcessInfo.processInfo.systemUptime
                                    windowNumber:window.windowNumber
                                         context:nil
                                     eventNumber:0
                                      clickCount:1
                                        pressure:1];
    if (event != nil) [NSApp postEvent:event atStart:NO];
}

/**
 * Starts the drag three seconds after [window] is shown. Runs on the main thread, from timers
 * in the common modes, so it keeps posting inside the tracking loop AppKit runs for a live
 * resize as a hand's events would arrive.
 */
static void dxc_vprobe_start(NSWindow *window, NSView *view, CAMetalLayer *layer) {
    if (!dxc_vprobe_enabled()) return;
    dxc_vprobe_started = mach_absolute_time();
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 3 * NSEC_PER_SEC), dispatch_get_main_queue(), ^{
        dxc_vprobe_capture_fn capture =
            (dxc_vprobe_capture_fn)dlsym(RTLD_DEFAULT, "CGWindowListCreateImage");
        if (capture == NULL) {
            fprintf(stderr, "compose-rust: vresize error=no-capture\n");
            fprintf(stderr, "compose-rust: vresize done\n");
            return;
        }
        dxc_vprobe_window_number = (uint32_t)window.windowNumber;
        dxc_vprobe_scale = window.backingScaleFactor > 0 ? window.backingScaleFactor : 1;
        NSRect visible = window.screen.visibleFrame;
        NSRect start = window.frame;
        enum { EVENTS = 40 };
        // As far as the screen allows, up to 160 points, with ten to spare.
        double down = fmin(160, NSMinY(start) - NSMinY(visible) - 10);
        double up = fmin(160, NSMaxY(visible) - NSMaxY(start) - 10);
        fprintf(stderr,
                "compose-rust: vresize begin window_frame=%.1f,%.1f,%.1fx%.1f visible=%.1f,%.1f,%.1fx%.1f "
                "scale=%.2f bottom_travel_pt=%.1f top_travel_pt=%.1f\n",
                start.origin.x, start.origin.y, start.size.width, start.size.height,
                visible.origin.x, visible.origin.y, visible.size.width, visible.size.height,
                dxc_vprobe_scale, down, up);

        atomic_store(&dxc_vprobe_sampling, 1);
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INTERACTIVE, 0), ^{
            int index = 0;
            while (atomic_load(&dxc_vprobe_sampling)) dxc_vprobe_sample(capture, index++);
        });

        __block int tick = 0;
        double x = NSMidX(start) - 40;
        NSTimer *timer = [NSTimer timerWithTimeInterval:1.0 / 60 repeats:YES block:^(NSTimer *t) {
            // Bottom edge: press 2 points inside it, drag down and back. Then the top edge,
            // up and back. Twenty quiet ticks before, between and after.
            int phase_length = 1 + 2 * EVENTS + 1 + 20;
            int at = tick++ - 20;
            if (at < 0) return;
            int edge = at / phase_length;
            int i = at % phase_length;
            NSRect frame = window.frame;
            if (edge >= 2) {
                [t invalidate];
                atomic_store(&dxc_vprobe_sampling, 0);
                dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 300 * NSEC_PER_MSEC),
                               dispatch_get_main_queue(), ^{
                    fprintf(stderr, "compose-rust: vresize done\n");
                });
                return;
            }
            const char *name = edge == 0 ? "bottom" : "top";
            double travel = edge == 0 ? -down : up;
            double y0 = edge == 0 ? NSMinY(start) + 2 : NSMaxY(start) - 2;
            if (i == 0) {
                dxc_vprobe_post(window, NSEventTypeLeftMouseDown, NSMakePoint(x, y0));
            } else if (i <= 2 * EVENTS) {
                int k = i <= EVENTS ? i : 2 * EVENTS - i;
                dxc_vprobe_post(window, NSEventTypeLeftMouseDragged,
                                NSMakePoint(x, y0 + travel * k / EVENTS));
            } else if (i == 2 * EVENTS + 1) {
                dxc_vprobe_post(window, NSEventTypeLeftMouseUp, NSMakePoint(x, y0));
            }
            if (i <= 2 * EVENTS + 1) dxc_vprobe_geometry(window, view, layer, name, i);
            (void)frame;
        }];
        [NSRunLoop.currentRunLoop addTimer:timer forMode:NSRunLoopCommonModes];
    });
}

#endif
