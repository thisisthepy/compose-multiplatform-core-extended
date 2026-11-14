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
    (void)window;
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

    // The system's AX API, as a client outside the process asks. A runner that has not trusted
    // this process answers with an error, printed with its number.
    int32_t trusted = -1, count = -1;
    int32_t error = dxc_native_ax_api_probe(&trusted, &count);
    printf("AX API from this process: trusted=%d, AXWindows error=%d, windows=%d\n", trusted, error, count);
}

// The window's appearance against the application's: the theme's scheme wins, zero follows.
static void check_appearance(NSWindow *window) {
    NSAppearance *before = NSApp.appearance;
    NSArray *names = @[ NSAppearanceNameAqua, NSAppearanceNameDarkAqua ];
    NSApp.appearance = [NSAppearance appearanceNamed:NSAppearanceNameDarkAqua];
    dxc_native_set_appearance((__bridge void *)window, 1);
    NSAppearanceName seen = [window.contentView.effectiveAppearance bestMatchFromAppearancesWithNames:names];
    CHECK([seen isEqualToString:NSAppearanceNameAqua], "1 under DarkAqua gives Aqua, got %s", seen.UTF8String);
    NSApp.appearance = [NSAppearance appearanceNamed:NSAppearanceNameAqua];
    dxc_native_set_appearance((__bridge void *)window, 2);
    seen = [window.contentView.effectiveAppearance bestMatchFromAppearancesWithNames:names];
    CHECK([seen isEqualToString:NSAppearanceNameDarkAqua], "2 under Aqua gives DarkAqua, got %s", seen.UTF8String);
    dxc_native_set_appearance((__bridge void *)window, 0);
    CHECK(window.appearance == nil, "0 follows the system");
    seen = [window.contentView.effectiveAppearance bestMatchFromAppearancesWithNames:names];
    CHECK([seen isEqualToString:NSAppearanceNameAqua], "and then takes the application's Aqua, got %s", seen.UTF8String);
    dxc_native_set_appearance((__bridge void *)window, 2);
    dxc_native_set_appearance((__bridge void *)window, 3);
    CHECK(window.appearance == nil, "3 follows the system too");
    NSApp.appearance = before;
}

// A change of backing scale with no change of size: the layer had a scale and a drawable
// from another screen (forced here, as one screen cannot give two scales), and the view is
// told what AppKit tells it when the window moves to a screen of another density.
static void check_backing_change(NSWindow *window, void *view_pointer) {
    NSView *view = (__bridge NSView *)view_pointer;
    CAMetalLayer *layer = (CAMetalLayer *)view.layer;
    CGFloat scale = window.backingScaleFactor > 0 ? window.backingScaleFactor : 1;
    CGFloat stale = scale == 1 ? 2 : 1;
    layer.contentsScale = stale;
    layer.drawableSize = CGSizeMake(view.bounds.size.width * stale, view.bounds.size.height * stale);
    struct dxc_event event;
    while (dxc_native_poll_event(&event)) {}
    [view viewDidChangeBackingProperties];
    CHECK(layer.contentsScale == scale, "the layer takes the window's scale %.1f, has %.1f",
          scale, layer.contentsScale);
    CHECK(layer.drawableSize.width == view.bounds.size.width * scale &&
              layer.drawableSize.height == view.bounds.size.height * scale,
          "the drawable is the view's size at the new scale, got %.0fx%.0f",
          layer.drawableSize.width, layer.drawableSize.height);
    int resized = 0;
    while (dxc_native_poll_event(&event)) {
        if (event.kind == DXC_EVENT_RESIZE && event.x == (float)(view.bounds.size.width * scale)) resized++;
    }
    CHECK(resized == 1, "one resize in pixels tells the scene to draw again, got %d", resized);
    [view viewDidChangeBackingProperties];
    while (dxc_native_poll_event(&event)) {
        if (event.kind == DXC_EVENT_RESIZE) resized++;
    }
    CHECK(resized == 1, "a second notice with nothing changed asks for nothing");
}

// A press reaches the queue dated by NSEvent.timestamp, and the double-click interval is the
// system's. A press read after a slow frame is still dated when it was made.
static void check_event_time(NSWindow *window, void *view_pointer) {
    NSView *view = (__bridge NSView *)view_pointer;
    struct dxc_event event;
    while (dxc_native_poll_event(&event)) {}
    NSPoint middle = NSMakePoint(NSMidX(view.bounds), NSMidY(view.bounds));
    NSPoint at = [view convertPoint:middle toView:nil];
    NSEvent *down = [NSEvent mouseEventWithType:NSEventTypeLeftMouseDown location:at modifierFlags:0
                                      timestamp:1234.5 windowNumber:window.windowNumber context:nil
                                    eventNumber:0 clickCount:1 pressure:1];
    NSEvent *up = [NSEvent mouseEventWithType:NSEventTypeLeftMouseUp location:at modifierFlags:0
                                    timestamp:1234.625 windowNumber:window.windowNumber context:nil
                                  eventNumber:0 clickCount:1 pressure:0];
    [view mouseDown:down];
    [view mouseUp:up];
    int64_t pressed = -1, released = -1;
    while (dxc_native_poll_event(&event)) {
        if (event.kind == DXC_EVENT_POINTER_DOWN) pressed = event.time_millis;
        if (event.kind == DXC_EVENT_POINTER_UP) released = event.time_millis;
    }
    CHECK(pressed == 1234500, "the press is dated by its timestamp, got %lld", (long long)pressed);
    CHECK(released == 1234625, "the release is dated by its timestamp, got %lld", (long long)released);
    int64_t interval = dxc_native_double_click_millis();
    CHECK(interval == (int64_t)(NSEvent.doubleClickInterval * 1000.0) && interval > 0,
          "the double-click interval is the system's, got %lld", (long long)interval);
}

// F1 to F12 and the menu key reach the queue as their own key numbers, and Shift with F10,
// which asks for a context menu, keeps Shift in its modifiers. The renderer's key table then
// reads these numbers; a key the view dropped or renumbered would never reach it.
static void check_function_keys(NSWindow *window, void *view_pointer) {
    NSView *view = (__bridge NSView *)view_pointer;
    struct dxc_event event;
    while (dxc_native_poll_event(&event)) {}
    // kVK_F1 to kVK_F12, then kVK_ContextualMenu, with the characters AppKit gives them.
    static const unsigned short codes[] = {0x7A, 0x78, 0x63, 0x76, 0x60, 0x61, 0x62, 0x64,
                                           0x65, 0x6D, 0x67, 0x6F, 0x6E};
    const int count = (int)(sizeof codes / sizeof codes[0]);
    for (int index = 0; index < count; index++) {
        unichar character = index < 12 ? (unichar)(NSF1FunctionKey + index) : (unichar)NSMenuFunctionKey;
        NSString *text = [NSString stringWithCharacters:&character length:1];
        NSEventModifierFlags flags = NSEventModifierFlagFunction |
            (codes[index] == 0x6D ? NSEventModifierFlagShift : 0);
        for (int up = 0; up < 2; up++) {
            NSEvent *key = [NSEvent keyEventWithType:up ? NSEventTypeKeyUp : NSEventTypeKeyDown
                                            location:NSZeroPoint
                                       modifierFlags:flags
                                           timestamp:0
                                        windowNumber:window.windowNumber
                                             context:nil
                                          characters:text
                         charactersIgnoringModifiers:text
                                           isARepeat:NO
                                             keyCode:codes[index]];
            if (up) [view keyUp:key]; else [view keyDown:key];
        }
        int downs = 0, ups = 0;
        while (dxc_native_poll_event(&event)) {
            if (event.kind != DXC_EVENT_KEY_DOWN && event.kind != DXC_EVENT_KEY_UP) continue;
            CHECK(event.key_code == codes[index], "key 0x%x arrived as 0x%x", codes[index], event.key_code);
            if (codes[index] == 0x6D) {
                CHECK((event.modifiers & NSEventModifierFlagShift) != 0, "Shift is held with F10");
            }
            if (event.kind == DXC_EVENT_KEY_DOWN) downs++; else ups++;
        }
        CHECK(downs == 1 && ups == 1, "key 0x%x: %d press(es) and %d release(s)", codes[index], downs, ups);
    }
}

// The editing shortcuts, as AppKit offers them: to the view's key equivalents before the menu
// bar. Command A, C, X, V, Z and Shift Command Z are claimed and reach the queue as a press
// and a release with Command held, and type nothing; Command Q is left to the menu bar.
static void check_editing_shortcuts(NSWindow *window, void *view_pointer) {
    NSView *view = (__bridge NSView *)view_pointer;
    struct dxc_event event;
    while (dxc_native_poll_event(&event)) {}
    // kVK_ANSI_A, C, X, V, Z, then Z with Shift.
    static const unsigned short codes[] = {0x00, 0x08, 0x07, 0x09, 0x06, 0x06};
    static const char *letters[] = {"a", "c", "x", "v", "z", "z"};
    for (int index = 0; index < 6; index++) {
        NSEventModifierFlags flags = NSEventModifierFlagCommand | (index == 5 ? NSEventModifierFlagShift : 0);
        NSString *text = [NSString stringWithUTF8String:letters[index]];
        NSEvent *key = [NSEvent keyEventWithType:NSEventTypeKeyDown location:NSZeroPoint modifierFlags:flags
                                       timestamp:0 windowNumber:window.windowNumber context:nil
                                      characters:text charactersIgnoringModifiers:text isARepeat:NO
                                         keyCode:codes[index]];
        CHECK([view performKeyEquivalent:key], "Command key 0x%x is claimed before the menu bar", codes[index]);
        int downs = 0, ups = 0, texts = 0;
        while (dxc_native_poll_event(&event)) {
            if (event.kind == DXC_EVENT_TEXT_COMMIT) texts++;
            if (event.kind != DXC_EVENT_KEY_DOWN && event.kind != DXC_EVENT_KEY_UP) continue;
            CHECK(event.key_code == codes[index], "Command key 0x%x arrived as 0x%x", codes[index], event.key_code);
            CHECK((event.modifiers & NSEventModifierFlagCommand) != 0, "Command is held on key 0x%x", codes[index]);
            if (index == 5) CHECK((event.modifiers & NSEventModifierFlagShift) != 0, "Shift is held for redo");
            if (event.kind == DXC_EVENT_KEY_DOWN) downs++; else ups++;
        }
        CHECK(downs == 1 && ups == 1, "Command key 0x%x: %d press(es) and %d release(s)", codes[index], downs, ups);
        CHECK(texts == 0, "Command key 0x%x typed text", codes[index]);
    }
    NSEvent *quit = [NSEvent keyEventWithType:NSEventTypeKeyDown location:NSZeroPoint
                                modifierFlags:NSEventModifierFlagCommand timestamp:0
                                 windowNumber:window.windowNumber context:nil characters:@"q"
                  charactersIgnoringModifiers:@"q" isARepeat:NO keyCode:0x0C];
    CHECK(![view performKeyEquivalent:quit], "Command Q is left to the menu bar");
    while (dxc_native_poll_event(&event)) {}
}

static int menus_tracked;

// A right click puts up no menu of the window's own while no text menu is set, which is how
// AppKitWindowPlatform opens it: Compose's menu is the one put up, through
// dxc_native_context_menu. The press and the release both reach the queue as the secondary
// button. With a text menu set the window's menu does come up, which shows the count counts.
static void check_right_click_menus(NSWindow *window, void *view_pointer) {
    NSView *view = (__bridge NSView *)view_pointer;
    struct dxc_event event;
    while (dxc_native_poll_event(&event)) {}
    id observer = [NSNotificationCenter.defaultCenter
        addObserverForName:NSMenuDidBeginTrackingNotification object:nil queue:nil
                usingBlock:^(NSNotification *note) {
                    menus_tracked++;
                    NSMenu *menu = note.object;
                    dispatch_async(dispatch_get_main_queue(), ^{ [menu cancelTracking]; });
                }];
    NSPoint at = [view convertPoint:NSMakePoint(NSMidX(view.bounds), NSMidY(view.bounds)) toView:nil];
    NSEvent *down = [NSEvent mouseEventWithType:NSEventTypeRightMouseDown location:at modifierFlags:0
                                      timestamp:0 windowNumber:window.windowNumber context:nil
                                    eventNumber:0 clickCount:1 pressure:1];
    NSEvent *up = [NSEvent mouseEventWithType:NSEventTypeRightMouseUp location:at modifierFlags:0
                                    timestamp:0 windowNumber:window.windowNumber context:nil
                                  eventNumber:0 clickCount:1 pressure:0];

    dxc_native_set_text_menu(NULL);
    menus_tracked = 0;
    [view rightMouseDown:down];
    [view rightMouseUp:up];
    spin(0.1);
    CHECK(menus_tracked == 0, "a right click put up %d menu(s) of the window's own", menus_tracked);
    int pressed = 0, released = 0;
    while (dxc_native_poll_event(&event)) {
        if (event.kind == DXC_EVENT_POINTER_DOWN && (event.buttons & DXC_SECONDARY_BUTTON)) pressed++;
        if (event.kind == DXC_EVENT_POINTER_UP && (event.buttons & DXC_SECONDARY_BUTTON)) released++;
    }
    CHECK(pressed == 1 && released == 1, "the right click reached the queue: %d press(es), %d release(s)",
          pressed, released);

    dxc_native_set_text_menu("1\t1\t0\tCut\n2\t1\t0\tCopy");
    menus_tracked = 0;
    [view rightMouseDown:down];
    spin(0.1);
    CHECK(menus_tracked == 1, "with a text menu set, the window's menu came up %d time(s)", menus_tracked);
    dxc_native_set_text_menu(NULL);
    [NSNotificationCenter.defaultCenter removeObserver:observer];
    while (dxc_native_poll_event(&event)) {}
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
        // What a reader is told, walked in process: the three buttons of every window, and the
        // Kotlin side's checks (macosChromeFindings) read this same text.
        static char snapshot[1 << 20];
        int32_t length = dxc_native_accessibility_snapshot(snapshot, sizeof snapshot - 1);
        CHECK(length > 0 && length < (int32_t)sizeof snapshot, "the snapshot is %d bytes", length);
        if (length > 0 && length < (int32_t)sizeof snapshot) {
            snapshot[length] = 0;
            printf("%s", snapshot);
            CHECK(strstr(snapshot, "#macos\t") == snapshot, "the snapshot starts with the release");
            CHECK(strstr(snapshot, "AXCloseButton") != NULL, "a close button is in the snapshot");
            CHECK(strstr(snapshot, "AXMinimizeButton") != NULL, "a minimise button is in the snapshot");
        }
        check_accessibility(windows[0], opened[0].view);
        check_appearance(windows[0]);
        check_backing_change(windows[0], opened[0].view);
        check_event_time(windows[0], opened[0].view);
        check_function_keys(windows[0], opened[0].view);
        check_editing_shortcuts(windows[0], opened[0].view);
        check_right_click_menus(windows[0], opened[0].view);
        for (int index = 0; index < 3; index++) [windows[index] orderOut:nil];
    }
    if (failures > 0) {
        fprintf(stderr, "%d check(s) failed\n", failures);
        return 1;
    }
    printf("appkit window chrome and accessibility: ok\n");
    return 0;
}
