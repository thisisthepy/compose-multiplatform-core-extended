#!/usr/bin/env bash
# The corner radius table matches the corner macOS actually draws.
#
# AppKit reports no window corner radius through a public API, so both macOS windows take
# it from MACOS_CORNER_RADII in common/src/org/thisisthepy/compose/window/MacosCornerRadius.kt,
# keyed by title bar style and release. This opens a window in each style (no toolbar, and
# an empty unified toolbar, built the way MacosWindow.kt's applyChrome builds them), takes
# the window's own image with the shadow off through the public CGWindowListCreateImage,
# and measures how much of the bottom corners is transparent.
#
# macOS draws a continuous corner (the curve eases into the straight edge), not a quarter
# circle, so the transparent area is not that of a circle of the same radius. The probe
# therefore draws a reference: a borderless window whose layer has the table's radius with
# the continuous corner curve, captured the same way. The drawn radius is the table's
# scaled by the square root of the two areas. It fails when that is more than 1pt from the
# table's, which is how a new macOS that draws a different corner is caught.
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
#import <QuartzCore/QuartzCore.h>
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

// The bottom corners of a window's own image: for each of the two corners a BOX by BOX
// square of transparency (0 opaque, 1 clear), in points at scale 1.
#define BOX 48
struct corners { float clear[2][BOX][BOX]; double scale; };

static int capture_corners(NSWindow *window, capture_fn capture, double settle, struct corners *out) {
    [window makeKeyAndOrderFront:nil];
    [NSApp activateIgnoringOtherApps:YES];
    pump(settle);

    // kCGWindowListOptionIncludingWindow, kCGWindowImageBoundsIgnoreFraming |
    // kCGWindowImageNominalResolution, so one pixel is one point on any display.
    CGImageRef image = capture(CGRectNull, 1 << 3, (uint32_t)window.windowNumber, (1 << 0) | (1 << 4));
    if (image == NULL) {
        [window close];
        return 1;
    }
    size_t width = CGImageGetWidth(image), height = CGImageGetHeight(image);
    out->scale = width / window.frame.size.width;
    uint8_t *rgba = calloc(width * height * 4, 1);
    CGColorSpaceRef space = CGColorSpaceCreateDeviceRGB();
    CGContextRef context = CGBitmapContextCreate(rgba, width, height, 8, width * 4, space,
                                                 (CGBitmapInfo)kCGImageAlphaPremultipliedLast);
    CGContextDrawImage(context, CGRectMake(0, 0, width, height), image);
    for (int side = 0; side < 2; side++) {
        for (int y = 0; y < BOX; y++) {
            for (int i = 0; i < BOX; i++) {
                size_t x = side ? width - 1 - i : (size_t)i;
                size_t row = height - 1 - y;
                out->clear[side][y][i] = 1.0f - rgba[(row * width + x) * 4 + 3] / 255.0f;
            }
        }
    }
    CGContextRelease(context);
    CGColorSpaceRelease(space);
    CGImageRelease(image);
    free(rgba);
    [window close];
    return 0;
}

static double area_of(const struct corners *c) {
    double area = 0;
    for (int side = 0; side < 2; side++)
        for (int y = 0; y < BOX; y++)
            for (int i = 0; i < BOX; i++) area += c->clear[side][y][i];
    return area / 2;
}

// A borderless window whose layer is cut to [radius], with the continuous corner curve
// or a plain quarter circle.
static int reference(double radius, int continuous, capture_fn capture, struct corners *out) {
    NSWindow *window = [[NSWindow alloc] initWithContentRect:NSMakeRect(200, 200, 480, 360)
                                                   styleMask:NSWindowStyleMaskBorderless
                                                     backing:NSBackingStoreBuffered
                                                       defer:NO];
    window.releasedWhenClosed = NO;
    window.hasShadow = NO;
    window.opaque = NO;
    window.backgroundColor = NSColor.clearColor;
    NSView *content = [[NSView alloc] initWithFrame:NSMakeRect(0, 0, 480, 360)];
    content.wantsLayer = YES;
    content.layer.backgroundColor = NSColor.redColor.CGColor;
    content.layer.cornerRadius = radius;
    content.layer.cornerCurve = continuous ? kCACornerCurveContinuous : kCACornerCurveCircular;
    content.layer.masksToBounds = YES;
    window.contentView = content;
    return capture_corners(window, capture, 0.4, out);
}

// The drawn radius is the reference radius whose corner matches the window's pixel for
// pixel (least squares over both bottom corners), tried in quarter points around the table's
// value with both corner curves. Comparing whole pixel maps rather than total transparent
// area matters: the area of a continuous corner depends on how far its curve eases into the
// edge, and an area ratio against one fixed reference read macOS 26 corners 0.9pt large
// (16.91 for 16, 26.96 for 26) while macOS 14 and 15 read within 0.1pt. The area check
// assumed the window's curve and the layer's continuous curve have the same shape at every
// radius; the pixel fit does not assume it, and prints which curve fits.
static int measure(int toolbar, double table, capture_fn capture, double *radius) {
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
    const char *name = toolbar ? "toolbar" : "simple";

    static struct corners drawn, ref;
    if (capture_corners(window, capture, 1.5, &drawn) != 0) return 1;
    // The ring diagnostics: the outermost row and column against the rest.
    double edge = 0;
    for (int side = 0; side < 2; side++)
        for (int k = BOX / 2; k < BOX; k++) edge += drawn.clear[side][0][k] + drawn.clear[side][k][0];
    printf("%s scale %.1f area %.1fpt2 straight-edge clear %.3f (mean of the outer ring away from the corner)\n",
           name, drawn.scale, area_of(&drawn), edge / (2 * BOX));

    double best_error = INFINITY, best_radius = 0;
    int best_curve = 0;
    for (int continuous = 0; continuous < 2; continuous++) {
        for (double r = fmax(1, table - 4); r <= table + 4 + 1e-9; r += 0.25) {
            if (reference(r, continuous, capture, &ref) != 0) return 1;
            double error = 0;
            for (int side = 0; side < 2; side++)
                for (int y = 0; y < BOX; y++)
                    for (int i = 0; i < BOX; i++) {
                        double d = drawn.clear[side][y][i] - ref.clear[side][y][i];
                        error += d * d;
                    }
            if (error < best_error) {
                best_error = error;
                best_radius = r;
                best_curve = continuous;
            }
        }
    }
    *radius = best_radius;
    printf("%s drawn radius %.2fpt, %s curve, residual %.2f (table %.1fpt)\n", name, best_radius,
           best_curve ? "continuous" : "circular", best_error, table);
    return 0;
}

int main(int argc, char **argv) {
    @autoreleasepool {
        if (argc != 3) {
            puts("ERROR usage: probe <simple radius> <toolbar radius>");
            return 2;
        }
        double want_simple = atof(argv[1]), want_toolbar = atof(argv[2]);
        [NSApplication sharedApplication];
        [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
        [NSApp finishLaunching];
        capture_fn capture = (capture_fn)dlsym(RTLD_DEFAULT, "CGWindowListCreateImage");
        if (capture == NULL) {
            puts("ERROR CGWindowListCreateImage is not available");
            return 2;
        }
        double simple = 0, toolbar = 0;
        if (measure(0, want_simple, capture, &simple) != 0 ||
            measure(1, want_toolbar, capture, &toolbar) != 0) {
            puts("ERROR the window server returned no image of the window");
            return 2;
        }
        printf("measured %.2f %.2f\n", simple, toolbar);
        return 0;
    }
}
PROBE

cc -fobjc-arc -Wno-deprecated-declarations "$work/probe.m" -framework AppKit \
    -framework CoreGraphics -framework QuartzCore -o "$work/probe" || { echo "FAIL: the probe did not compile"; exit 1; }
os="$(sw_vers -productVersion | cut -d. -f1)"

# The last row whose release is not newer than this one, as macosCornerRadius picks it.
row="$(sed -n 's/^ *MacosCornerRadiusRow(fromMajor = \([0-9]*\), simple = \([0-9.]*\), toolbar = \([0-9.]*\)),$/\1 \2 \3/p' "$table" |
    awk -v os="$os" '$1 <= os { r = $0 } END { print r }')"
[[ -n "$row" ]] || { echo "FAIL: MacosCornerRadius.kt has no row for macOS $os"; exit 1; }
read -r from want_simple want_toolbar <<< "$row"

output="$("$work/probe" "$want_simple" "$want_toolbar")"
probe_status=$?
echo "$output"
[[ $probe_status -eq 0 ]] || { echo "FAIL: the probe could not capture the window ($probe_status)"; exit 1; }
read -r simple toolbar <<< "$(sed -n 's/^measured //p' <<< "$output")"
echo "macOS $os, table row from macOS $from: simple $want_simple toolbar $want_toolbar"

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
