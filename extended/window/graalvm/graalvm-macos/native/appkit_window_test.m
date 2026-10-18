// The GraalVM window's chrome and accessibility, checked on a real window.
//
// Compiled together with appkit_window.m (included below, so the test reads the same event
// queue and records the renderer does) and run on a macOS runner by the module's workflow:
//
//   cc -fobjc-arc -o appkit_window_test appkit_window_test.m \
//      -framework AppKit -framework Metal -framework QuartzCore -framework CoreVideo \
//      -framework Carbon -framework ApplicationServices
//
// Three windows are opened, one per title bar (Normal, Simple, System), with the values
// `MacosTitleBar.of` gives for each, and each is checked for what that style promises: the
// three system buttons kept, the content under the bar, no title drawn, and the Normal bar
// taller than the Simple one with its buttons further in where the release does that. Then
// records are pushed as the Kotlin side writes them and the window is asked as a reader asks.
//
// The reader is asked twice. In process, through the view's accessibility children, which is
// what AppKit's accessibility server answers every client from. And through the system's AX
// API, as VoiceOver would, which needs this process to be trusted for accessibility; a runner
// that has not granted that answers with an error, which is printed with its number rather
// than taken as a failure of the window.

#include "appkit_window.m"

#import <ApplicationServices/ApplicationServices.h>

static int failures;

#define CHECK(condition, ...)                                   \
    do {                                                        \
        if (!(condition)) {                                     \
            fprintf(stderr, "FAIL %s:%d: ", __FILE__, __LINE__); \
            fprintf(stderr, __VA_ARGS__);                       \
            fprintf(stderr, "\n");                              \
            failures++;                                         \
        }                                                       \
    } while (0)

struct style {
    const char *name;
    int32_t system_chrome;
    int32_t full_size_content;
    int32_t transparent_title_bar;
    int32_t title_hidden;
    int32_t unified_toolbar;
};

// The values MacosTitleBar.Normal, .Simple and .System hold.
static const struct style styles[] = {
    {"Normal", 0, 1, 1, 1, 1},
    {"Simple", 0, 1, 1, 1, 0},
    {"System", 1, 0, 0, 0, 0},
};

struct measured {
    float bar_height;
    float close_min_x;
    float zoom_max_x;
};

static void spin(double seconds) {
    [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:seconds]];
}

static NSWindow *open_styled(const struct style *style, struct dxc_native_window *out) {
    dxc_native_window_configure(1, 0, 0, style->system_chrome, 0);
    dxc_native_window_chrome(style->full_size_content, style->transparent_title_bar,
                             style->title_hidden, style->unified_toolbar);
    int32_t status = dxc_native_window_open(style->name, 480, 320, out);
    CHECK(status == 0, "%s: the window did not open (status %d)", style->name, status);
    if (status != 0) return nil;
    spin(0.2);
    return (__bridge NSWindow *)out->window;
}

static struct measured check_chrome(const struct style *style, NSWindow *window, void *view) {
    struct measured result = {0, -1, -1};
    CHECK([window standardWindowButton:NSWindowCloseButton] != nil, "%s: no close button", style->name);
    CHECK([window standardWindowButton:NSWindowMiniaturizeButton] != nil, "%s: no minimise button", style->name);
    CHECK([window standardWindowButton:NSWindowZoomButton] != nil, "%s: no zoom button", style->name);
    BOOL under = (window.styleMask & NSWindowStyleMaskFullSizeContentView) != 0;
    CHECK(under == (style->full_size_content != 0), "%s: content under the bar is %d", style->name, under);
    BOOL hidden = window.titleVisibility == NSWindowTitleHidden;
    CHECK(hidden == (style->title_hidden != 0), "%s: title hidden is %d", style->name, hidden);
    CHECK(window.titlebarAppearsTransparent == (style->transparent_title_bar != 0),
          "%s: transparent bar is %d", style->name, window.titlebarAppearsTransparent);
    CHECK((window.toolbar != nil) == (style->unified_toolbar != 0), "%s: toolbar is %p", style->name,
          (__bridge void *)window.toolbar);
    CHECK(strcmp(window.title.UTF8String, style->name) == 0, "%s: the title is carried, got %s",
          style->name, window.title.UTF8String);
    float out[6];
    dxc_native_window_title_bar(view, out);
    result.bar_height = out[0] - out[1];
    result.close_min_x = out[2];
    result.zoom_max_x = out[3];
    CHECK(out[4] == (style->unified_toolbar ? 1 : 0), "%s: toolbar reported as %.0f", style->name, out[4]);
    printf("%s: bar %.2f pt, close at %.2f, zoom ends at %.2f, macOS %.0f\n",
           style->name, result.bar_height, out[2], out[3], out[5]);
    return result;
}

static struct dxc_ax_element record(int32_t key, int32_t parent, const char *role, const char *label,
                                    int32_t actions, int32_t flags, float y) {
    struct dxc_ax_element element;
    memset(&element, 0, sizeof element);
    element.key = key;
    element.parent = parent;
    element.states = DXC_STATE_ENABLED;
    element.actions = actions;
    element.flags = flags;
    element.x = 10;
    element.y = y;
    element.width = 120;
    element.height = 24;
    strncpy(element.role, role, sizeof element.role - 1);
    strncpy(element.label, label, sizeof element.label - 1);
    return element;
}

static void check_accessibility(NSWindow *window, void *view_pointer) {
    NSView *view = (__bridge NSView *)view_pointer;
    struct dxc_ax_element elements[3];
    elements[0] = record(1, -1, "AXGroup", "Form", 0, 0, 0);
    elements[1] = record(2, 0, "AXTextField", "Name", DXC_ACTION_SET_VALUE | DXC_ACTION_FOCUS,
                         DXC_AX_HAS_VALUE | DXC_AX_VALUE_SETTABLE | DXC_AX_FOCUS_SETTABLE, 40);
    strncpy(elements[1].value, "Ada", sizeof elements[1].value - 1);
    elements[2] = record(3, 0, "AXButton", "Send", DXC_ACTION_PRESS, 0, 80);
    dxc_native_set_accessibility(elements, 3, view_pointer);
    NSArray *roots = view.accessibilityChildren;
    CHECK(dxc_native_accessibility_active() == 1, "a reader asked, so one is listening");
    CHECK(roots.count == 1, "one element directly in the view, got %lu", (unsigned long)roots.count);
    if (roots.count != 1) return;
    NSAccessibilityElement *form = roots[0];
    CHECK([form.accessibilityLabel isEqualToString:@"Form"], "the group is labelled Form");
    NSArray *inside = form.accessibilityChildren;
    CHECK(inside.count == 2, "the field and the button are in the form, got %lu", (unsigned long)inside.count);
    if (inside.count != 2) return;
    NSAccessibilityElement *field = inside[0];
    NSAccessibilityElement *button = inside[1];
    CHECK([field.accessibilityRole isEqualToString:NSAccessibilityTextFieldRole], "the field is a text field");
    CHECK([field.accessibilityLabel isEqualToString:@"Name"], "the field is labelled Name");
    CHECK([field.accessibilityValue isEqual:@"Ada"], "the field's value is its text");
    CHECK([button.accessibilityRole isEqualToString:NSAccessibilityButtonRole], "the button is a button");
    CHECK([button.accessibilityLabel isEqualToString:@"Send"], "the button is labelled Send");
    CHECK(button.accessibilityParent == form, "the button says it is in the form");
    CHECK(form.accessibilityParent == view, "the form says it is in the view");
    NSRect frame = button.accessibilityFrame;
    CHECK(frame.size.width == 120 && frame.size.height == 24, "the frame is in points: %.0fx%.0f",
          frame.size.width, frame.size.height);
    CHECK(![field isAccessibilitySelectorAllowed:@selector(accessibilityPerformPress)], "a field offers no press");
    CHECK([button isAccessibilitySelectorAllowed:@selector(accessibilityPerformPress)], "a button offers a press");

    struct dxc_event event;
    while (dxc_native_poll_event(&event)) {}
    CHECK([button accessibilityPerformPress], "the press was taken");
    [field setAccessibilityValue:@"Grace"];
    [field setAccessibilityFocused:YES];
    int32_t seen = 0;
    while (dxc_native_poll_event(&event)) {
        if (event.kind != DXC_EVENT_ACCESSIBILITY_ACTION) continue;
        if (seen == 0) CHECK(event.key_code == 3 && event.code_point == DXC_ACTION_PRESS, "first the press on 3");
        if (seen == 1) {
            CHECK(event.key_code == 2 && event.code_point == DXC_ACTION_SET_VALUE, "then the new value of 2");
            char argument[64] = {0};
            int32_t length = dxc_native_take_accessibility_argument(argument, sizeof argument - 1);
            CHECK(length == 5 && strcmp(argument, "Grace") == 0, "the value is Grace, got %d '%s'", length, argument);
        }
        if (seen == 2) CHECK(event.key_code == 2 && event.code_point == DXC_ACTION_FOCUS, "then focus to 2");
        seen++;
    }
    CHECK(seen == 3, "three requests reached the queue, got %d", seen);

    // The same element object after a push that keeps its key.
    strncpy(elements[2].label, "Sent", sizeof elements[2].label - 1);
    dxc_native_set_accessibility(elements, 3, view_pointer);
    NSAccessibilityElement *again = ((NSAccessibilityElement *)view.accessibilityChildren[0]).accessibilityChildren[1];
    CHECK(again == button, "the button kept its object");
    CHECK([again.accessibilityLabel isEqualToString:@"Sent"], "and took its new label");

    // The system's AX API, asked from another thread as a client would.
    __block AXError error = kAXErrorSuccess;
    __block CFIndex count = -1;
    __block BOOL done = NO;
    pid_t pid = getpid();
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        AXUIElementRef app = AXUIElementCreateApplication(pid);
        AXUIElementSetMessagingTimeout(app, 3.0);
        CFTypeRef windows = NULL;
        error = AXUIElementCopyAttributeValue(app, kAXWindowsAttribute, &windows);
        if (error == kAXErrorSuccess && windows != NULL) {
            count = CFArrayGetCount(windows);
            CFRelease(windows);
        }
        CFRelease(app);
        done = YES;
    });
    for (int turns = 0; turns < 100 && !done; turns++) spin(0.05);
    printf("AX API from this process: trusted=%d, AXWindows error=%d, windows=%ld\n",
           AXIsProcessTrusted(), (int)error, (long)count);
}

int main(void) {
    @autoreleasepool {
        [NSApplication sharedApplication];
        struct measured bars[3];
        NSWindow *windows[3];
        struct dxc_native_window opened[3];
        for (int index = 0; index < 3; index++) {
            windows[index] = open_styled(&styles[index], &opened[index]);
            if (windows[index] == nil) return 1;
            bars[index] = check_chrome(&styles[index], windows[index], opened[index].view);
        }
        CHECK(bars[0].bar_height > bars[1].bar_height,
              "the Normal bar is taller than the Simple one: %.2f against %.2f",
              bars[0].bar_height, bars[1].bar_height);
        NSInteger major = NSProcessInfo.processInfo.operatingSystemVersion.majorVersion;
        if (major >= 26) {
            CHECK(bars[0].close_min_x > bars[1].close_min_x,
                  "macOS %ld sets the Normal buttons further in: %.2f against %.2f",
                  (long)major, bars[0].close_min_x, bars[1].close_min_x);
        }
        check_accessibility(windows[0], opened[0].view);
        for (int index = 0; index < 3; index++) [windows[index] orderOut:nil];
    }
    if (failures > 0) {
        fprintf(stderr, "%d check(s) failed\n", failures);
        return 1;
    }
    printf("appkit window chrome and accessibility: ok\n");
    return 0;
}
