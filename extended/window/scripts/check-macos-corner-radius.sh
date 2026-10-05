#!/usr/bin/env bash
# The corner radius table matches the corner macOS actually draws.
#
# AppKit reports no window corner radius through a public API, so both macOS windows take
# it from MACOS_CORNER_RADII in common/src/org/thisisthepy/compose/window/MacosCornerRadius.kt,
# keyed by title bar style and release. This opens a window in each style (no toolbar, and
# an empty unified toolbar, built the way MacosWindow.kt's applyChrome builds them), takes
# the window's own image with the shadow off through the public CGWindowListCreateImage,
# and measures the radius from how much of the bottom corners is transparent. It fails when
# the measured radius is more than 1pt from the table's, which is how a new macOS that
# draws a different corner is caught.
#
# Usage: scripts/check-macos-corner-radius.sh   (macOS only)
set -uo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
table="$root/common/src/org/thisisthepy/compose/window/MacosCornerRadius.kt"
tolerance=1.0

if [[ "$(uname -s)" != Darwin ]]; then
    echo "skipped: AppKit is only available on macOS"
    exit 0
fi

work="$root/build/corner-radius"
rm -rf "$work"
mkdir -p "$work"

cat > "$work/probe.m" <<'PROBE'
#import <AppKit/AppKit.h>
#import <dlfcn.h>
#import <math.h>

// CGWindowListCreateImage is public but marked obsolete in newer SDK headers, so it is
// looked up at run time rather than declared; it still captures the caller's own windows.
typedef CGImageRef (*capture_fn)(CGRect, uint32_t, uint32_t, uint32_t);

static void pump(double seconds) {
    NSDate *until = [NSDate dateWithTimeIntervalSinceNow:seconds];
    while ([until timeIntervalSinceNow] > 0) {
        NSEvent *event = [NSApp nextEventMatchingMask:NSEventMaskAny untilDate:until
                                               inMode:NSDefaultRunLoopMode dequeue:YES];
        if (event) [NSApp sendEvent:event];
    }
}

// The transparent area in a size by size box at one bottom corner, in pixels.
static double cut_area(const uint8_t *rgba, size_t width, size_t height, size_t box, int right) {
    double area = 0;
    for (size_t y = height - box; y < height; y++) {
        for (size_t i = 0; i < box; i++) {
            size_t x = right ? width - 1 - i : i;
            area += 1.0 - rgba[(y * width + x) * 4 + 3] / 255.0;
        }
    }
    return area;
}

// Transparent pixels in the bottom row, counted from one corner: the arc's reach.
static size_t bottom_run(const uint8_t *rgba, size_t width, size_t height) {
    size_t run = 0;
    while (run < width / 2 && rgba[((height - 1) * width + run) * 4 + 3] < 128) run++;
    return run;
}

static int measure(int toolbar, capture_fn capture, double *radius) {
    NSWindow *window = [[NSWindow alloc]
        initWithContentRect:NSMakeRect(200, 200, 480, 360)
                  styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskMiniaturizable |
                            NSWindowStyleMaskClosable | NSWindowStyleMaskResizable |
                            NSWindowStyleMaskFullSizeContentView
                    backing:NSBackingStoreBuffered
                      defer:NO];
    window.releasedWhenClosed = NO;
    window.hasShadow = NO;
    window.titlebarAppearsTransparent = YES;
    window.titleVisibility = NSWindowTitleHidden;
    window.backgroundColor = NSColor.redColor;
    if (toolbar) {
        NSToolbar *bar = [[NSToolbar alloc] initWithIdentifier:@"corner-radius"];
        bar.showsBaselineSeparator = NO;
        window.toolbar = bar;
        window.toolbarStyle = NSWindowToolbarStyleUnified;
    }
    NSView *content = [[NSView alloc] initWithFrame:NSMakeRect(0, 0, 480, 360)];
    content.wantsLayer = YES;
    content.layer.backgroundColor = NSColor.redColor.CGColor;
    window.contentView = content;
    [window makeKeyAndOrderFront:nil];
    [NSApp activateIgnoringOtherApps:YES];
    pump(1.5);

    // kCGWindowListOptionIncludingWindow, kCGWindowImageBoundsIgnoreFraming |
    // kCGWindowImageBestResolution.
    CGImageRef image = capture(CGRectNull, 1 << 3, (uint32_t)window.windowNumber, (1 << 0) | (1 << 3));
    if (image == NULL) {
        [window close];
        return 1;
    }
    size_t width = CGImageGetWidth(image), height = CGImageGetHeight(image);
    double scale = width / window.frame.size.width;
    uint8_t *rgba = calloc(width * height * 4, 1);
    CGColorSpaceRef space = CGColorSpaceCreateDeviceRGB();
    CGContextRef context = CGBitmapContextCreate(rgba, width, height, 8, width * 4, space,
                                                 kCGImageAlphaPremultipliedLast);
    CGContextDrawImage(context, CGRectMake(0, 0, width, height), image);
    // A circular corner of radius r leaves r^2 (1 - pi/4) of its box transparent.
    size_t box = (size_t)ceil(48 * scale);
    double area = (cut_area(rgba, width, height, box, 0) + cut_area(rgba, width, height, box, 1)) / 2;
    *radius = sqrt(area / (1 - M_PI / 4)) / scale;
    printf("%s scale %.1f image %zux%zu area %.1fpx bottom-run %zupx radius %.2fpt\n",
           toolbar ? "toolbar" : "simple", scale, width, height, area,
           bottom_run(rgba, width, height), *radius);
    CGContextRelease(context);
    CGColorSpaceRelease(space);
    CGImageRelease(image);
    free(rgba);
    [window close];
    return 0;
}

int main(void) {
    @autoreleasepool {
        [NSApplication sharedApplication];
        [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
        [NSApp finishLaunching];
        capture_fn capture = (capture_fn)dlsym(RTLD_DEFAULT, "CGWindowListCreateImage");
        if (capture == NULL) {
            puts("ERROR CGWindowListCreateImage is not available");
            return 2;
        }
        double simple = 0, toolbar = 0;
        if (measure(0, capture, &simple) != 0 || measure(1, capture, &toolbar) != 0) {
            puts("ERROR the window server returned no image of the window");
            return 2;
        }
        printf("os %ld\n", (long)NSProcessInfo.processInfo.operatingSystemVersion.majorVersion);
        printf("measured %.2f %.2f\n", simple, toolbar);
        return 0;
    }
}
PROBE

cc -fobjc-arc -Wno-deprecated-declarations "$work/probe.m" -framework AppKit \
    -framework CoreGraphics -o "$work/probe" || { echo "FAIL: the probe did not compile"; exit 1; }
output="$("$work/probe")"
probe_status=$?
echo "$output"
[[ $probe_status -eq 0 ]] || { echo "FAIL: the probe could not capture the window ($probe_status)"; exit 1; }

os="$(sed -n 's/^os //p' <<< "$output")"
read -r simple toolbar <<< "$(sed -n 's/^measured //p' <<< "$output")"

# The last row whose release is not newer than this one, as macosCornerRadius picks it.
row="$(sed -n 's/^ *MacosCornerRadiusRow(fromMajor = \([0-9]*\), simple = \([0-9.]*\), toolbar = \([0-9.]*\)),$/\1 \2 \3/p' "$table" |
    awk -v os="$os" '$1 <= os { r = $0 } END { print r }')"
[[ -n "$row" ]] || { echo "FAIL: MacosCornerRadius.kt has no row for macOS $os"; exit 1; }
read -r from want_simple want_toolbar <<< "$row"
echo "table (from macOS $from): simple $want_simple toolbar $want_toolbar"

status=0
for pair in "simple $simple $want_simple" "toolbar $toolbar $want_toolbar"; do
    read -r name got want <<< "$pair"
    if awk -v g="$got" -v w="$want" -v t="$tolerance" 'BEGIN { d = g - w; if (d < 0) d = -d; exit !(d <= t) }'; then
        echo "fr19_7 $name: drawn ${got}pt, table ${want}pt: ok"
    else
        echo "FAIL: fr19_7 macOS $os draws the $name window's corner at ${got}pt but the table says ${want}pt; add a row for macOS $os to MacosCornerRadius.kt"
        status=1
    fi
done
exit $status
