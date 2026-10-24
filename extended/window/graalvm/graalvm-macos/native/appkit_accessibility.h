// What the window tells a reader who cannot see it, for the GraalVM window.
//
// Included by appkit_window.m after its event queue, because a reader's request (press,
// set the value, take focus) is written to that queue like any other event and read where
// the scene lives. Kept in its own file so the bridge reads as one piece.
//
// A snapshot rather than a question. Accessibility is asked for on the thread AppKit
// answers on, at moments nobody chose, and the tree it describes lives where the scene
// does: answering by asking across would block whichever thread asked, and one of them is
// the thread the frame is drawn from. The scene pushes what it has whenever it changes,
// and this answers from that.
//
// What each control is to AppKit (its role, subrole, the value it reports and whether the
// reader may set it) is decided in Kotlin, in the common module, by the same rule the
// Kotlin/Native window applies, and arrives here as strings and flags. This file only
// builds and keeps the elements.

#ifndef DXC_APPKIT_ACCESSIBILITY_H
#define DXC_APPKIT_ACCESSIBILITY_H

#import <AppKit/AppKit.h>
#import <objc/runtime.h>
#include <stddef.h>
#include <dlfcn.h>
#include <unistd.h>
#include <stdint.h>

#define DXC_AX_NAME_BYTES 32
#define DXC_AX_TEXT_BYTES 256

// The record one element crosses as. Laid out by hand so the Kotlin side writes it at fixed
// offsets (AccessibilityRecords.kt); the asserts below keep the two in step.
struct dxc_ax_element {
    // The scene's id for it, or a negative key for one that has none: such an element is
    // described but cannot be asked to do anything.
    int32_t key;
    // The index in this list of the element it is inside, or -1 for one directly in the view.
    int32_t parent;
    // AccessibleState and AccessibleAction bits, as the common module defines them.
    int32_t states;
    int32_t actions;
    // DXC_AX_* below.
    int32_t flags;
    // Window points from the top left of the view.
    float x;
    float y;
    float width;
    float height;
    int32_t reserved;
    double number;
    float range_min;
    float range_max;
    char role[DXC_AX_NAME_BYTES];
    char subrole[DXC_AX_NAME_BYTES];
    char label[DXC_AX_TEXT_BYTES];
    char value[DXC_AX_TEXT_BYTES];
    char hint[DXC_AX_TEXT_BYTES];
};

_Static_assert(offsetof(struct dxc_ax_element, flags) == 16, "flags offset");
_Static_assert(offsetof(struct dxc_ax_element, x) == 20, "x offset");
_Static_assert(offsetof(struct dxc_ax_element, number) == 40, "number offset");
_Static_assert(offsetof(struct dxc_ax_element, range_min) == 48, "range offset");
_Static_assert(offsetof(struct dxc_ax_element, role) == 56, "role offset");
_Static_assert(offsetof(struct dxc_ax_element, subrole) == 88, "subrole offset");
_Static_assert(offsetof(struct dxc_ax_element, label) == 120, "label offset");
_Static_assert(offsetof(struct dxc_ax_element, value) == 376, "value offset");
_Static_assert(offsetof(struct dxc_ax_element, hint) == 632, "hint offset");
_Static_assert(sizeof(struct dxc_ax_element) == 888, "record size");

// What `flags` says.
enum {
    DXC_AX_HAS_VALUE = 1 << 0,
    // The value is `number` rather than the text in `value`.
    DXC_AX_VALUE_IS_NUMBER = 1 << 1,
    DXC_AX_HAS_MIN = 1 << 2,
    DXC_AX_HAS_MAX = 1 << 3,
    DXC_AX_VALUE_SETTABLE = 1 << 4,
    DXC_AX_FOCUS_SETTABLE = 1 << 5,
    DXC_AX_HAS_HINT = 1 << 6,
};

// The bits of AccessibleState and AccessibleAction this file reads.
enum {
    DXC_STATE_ENABLED = 1 << 0,
    DXC_STATE_FOCUSED = 1 << 2,
    DXC_STATE_SELECTED = 1 << 6,
};
enum {
    DXC_ACTION_PRESS = 1 << 0,
    DXC_ACTION_FOCUS = 1 << 1,
    DXC_ACTION_SET_VALUE = 1 << 2,
    DXC_ACTION_INCREMENT = 1 << 3,
    DXC_ACTION_DECREMENT = 1 << 4,
    DXC_ACTION_SCROLL_FORWARD = 1 << 5,
    DXC_ACTION_SCROLL_BACKWARD = 1 << 6,
    DXC_ACTION_SHOW_MENU = 1 << 7,
};

// The event a reader's request is written down as: `key_code` is the element's id and
// `code_point` the AccessibleAction. The new value of a SET_VALUE is kept apart, since it
// can be longer than an event's text, and read with dxc_native_take_accessibility_argument.
#define DXC_EVENT_ACCESSIBILITY_ACTION 18

static void dxc_push_event(struct dxc_event event);

// Whether anything has asked a window of this process what is in it.
static atomic_int dxc_ax_asked;

// The new values readers set, oldest first, one per SET_VALUE event still to be read.
static NSMutableArray<NSString *> *dxc_ax_arguments;

// The text a C string of at most `capacity` bytes holds, or nil for an empty one.
static NSString *dxc_ax_string(const char *bytes, size_t capacity) {
    size_t length = strnlen(bytes, capacity);
    if (length == 0) return nil;
    return [[NSString alloc] initWithBytes:bytes length:length encoding:NSUTF8StringEncoding];
}

static BOOL dxc_ax_request(int32_t key, int32_t action, NSString *argument) {
    if (key < 0) return NO;
    if (action == DXC_ACTION_SET_VALUE) {
        @synchronized ([NSApplication class]) {
            if (dxc_ax_arguments == nil) dxc_ax_arguments = [NSMutableArray array];
            [dxc_ax_arguments addObject:argument ?: @""];
        }
    }
    struct dxc_event record;
    memset(&record, 0, sizeof record);
    record.kind = DXC_EVENT_ACCESSIBILITY_ACTION;
    record.key_code = key;
    record.code_point = action;
    dxc_push_event(record);
    return YES;
}

@interface DxcAxElement : NSAccessibilityElement
@property (nonatomic) int32_t key;
@property (nonatomic) int32_t states;
@property (nonatomic) int32_t actions;
@property (nonatomic) int32_t flags;
@property (nonatomic) NSRect local;
@property (nonatomic, copy) NSString *axRole;
@property (nonatomic, copy) NSString *axSubrole;
@property (nonatomic, copy) NSString *axLabel;
@property (nonatomic, copy) NSString *axHint;
@property (nonatomic, strong) id axValue;
@property (nonatomic, strong) NSNumber *axMin;
@property (nonatomic, strong) NSNumber *axMax;
@property (nonatomic, strong) NSArray *kids;
@property (nonatomic, weak) id up;
@property (nonatomic, weak) NSView *host;
@end

@implementation DxcAxElement
- (BOOL)isAccessibilityElement { return YES; }
- (NSAccessibilityRole)accessibilityRole { return self.axRole ?: NSAccessibilityGroupRole; }
- (NSAccessibilitySubrole)accessibilitySubrole { return self.axSubrole; }
- (NSString *)accessibilityLabel { return self.axLabel ?: @""; }
- (NSString *)accessibilityHelp { return self.axHint; }
- (id)accessibilityValue { return self.axValue; }
- (id)accessibilityMinValue { return self.axMin; }
- (id)accessibilityMaxValue { return self.axMax; }
- (BOOL)isAccessibilityEnabled { return (self.states & DXC_STATE_ENABLED) != 0; }
- (BOOL)isAccessibilityFocused { return (self.states & DXC_STATE_FOCUSED) != 0; }
- (BOOL)isAccessibilitySelected { return (self.states & DXC_STATE_SELECTED) != 0; }
- (id)accessibilityParent { return self.up; }
- (NSArray *)accessibilityChildren { return self.kids ?: @[]; }
- (NSArray *)accessibilityChildrenInNavigationOrder { return self.accessibilityChildren; }

// Turned into the screen's coordinates when asked rather than when pushed, so a window that
// moved answers with where its controls are now.
- (NSRect)accessibilityFrame {
    NSView *view = self.host;
    if (view == nil || view.window == nil) return self.local;
    NSRect inWindow = [view convertRect:self.local toView:nil];
    return [view.window convertRectToScreen:inWindow];
}

- (BOOL)accessibilityPerformPress {
    return (self.actions & DXC_ACTION_PRESS) != 0 && dxc_ax_request(self.key, DXC_ACTION_PRESS, nil);
}
- (BOOL)accessibilityPerformIncrement {
    return (self.actions & DXC_ACTION_INCREMENT) != 0 && dxc_ax_request(self.key, DXC_ACTION_INCREMENT, nil);
}
- (BOOL)accessibilityPerformDecrement {
    return (self.actions & DXC_ACTION_DECREMENT) != 0 && dxc_ax_request(self.key, DXC_ACTION_DECREMENT, nil);
}
- (BOOL)accessibilityPerformShowMenu {
    return (self.actions & DXC_ACTION_SHOW_MENU) != 0 && dxc_ax_request(self.key, DXC_ACTION_SHOW_MENU, nil);
}

// A reader typed into the field or moved the slider: the scene decides what that does.
- (void)setAccessibilityValue:(id)value {
    if ((self.flags & DXC_AX_VALUE_SETTABLE) == 0) return;
    NSString *text = [value isKindOfClass:[NSString class]] ? value : [value description];
    dxc_ax_request(self.key, DXC_ACTION_SET_VALUE, text);
}

// Only taking focus is passed on; losing it follows from that.
- (void)setAccessibilityFocused:(BOOL)focused {
    if (focused && (self.flags & DXC_AX_FOCUS_SETTABLE) != 0) {
        dxc_ax_request(self.key, DXC_ACTION_FOCUS, nil);
    }
}

// Only the requests the scene said this control answers, so VoiceOver does not list a press
// on a label or let a reader type into text.
- (BOOL)isAccessibilitySelectorAllowed:(SEL)selector {
    if (selector == @selector(accessibilityPerformPress)) return (self.actions & DXC_ACTION_PRESS) != 0;
    if (selector == @selector(accessibilityPerformIncrement)) return (self.actions & DXC_ACTION_INCREMENT) != 0;
    if (selector == @selector(accessibilityPerformDecrement)) return (self.actions & DXC_ACTION_DECREMENT) != 0;
    if (selector == @selector(accessibilityPerformShowMenu)) return (self.actions & DXC_ACTION_SHOW_MENU) != 0;
    if (selector == @selector(setAccessibilityValue:)) return (self.flags & DXC_AX_VALUE_SETTABLE) != 0;
    if (selector == @selector(setAccessibilityFocused:)) return (self.flags & DXC_AX_FOCUS_SETTABLE) != 0;
    return [super isAccessibilitySelectorAllowed:selector];
}

// AppKit's element protocol has no page scroll, so a scrollable element offers it as custom
// actions, which VoiceOver lists in its actions menu. The names are the common module's.
- (NSArray<NSAccessibilityCustomAction *> *)accessibilityCustomActions {
    NSMutableArray<NSAccessibilityCustomAction *> *found = [NSMutableArray array];
    int32_t key = self.key;
    if ((self.actions & DXC_ACTION_SCROLL_FORWARD) != 0) {
        [found addObject:[[NSAccessibilityCustomAction alloc] initWithName:@"Scroll forward" handler:^BOOL {
            return dxc_ax_request(key, DXC_ACTION_SCROLL_FORWARD, nil);
        }]];
    }
    if ((self.actions & DXC_ACTION_SCROLL_BACKWARD) != 0) {
        [found addObject:[[NSAccessibilityCustomAction alloc] initWithName:@"Scroll backward" handler:^BOOL {
            return dxc_ax_request(key, DXC_ACTION_SCROLL_BACKWARD, nil);
        }]];
    }
    return found;
}
@end

// What a view keeps for its reader: its elements by key, and the ones directly in it.
@interface DxcAxTree : NSObject
@property (nonatomic, strong) NSMutableDictionary<NSNumber *, DxcAxElement *> *byKey;
@property (nonatomic, strong) NSArray<DxcAxElement *> *roots;
@property (nonatomic, strong) NSArray<DxcAxElement *> *all;
@end
@implementation DxcAxTree
@end

static char dxc_ax_tree_tag;

static DxcAxTree *dxc_ax_tree(NSView *view, BOOL make) {
    DxcAxTree *tree = objc_getAssociatedObject(view, &dxc_ax_tree_tag);
    if (tree == nil && make) {
        tree = [[DxcAxTree alloc] init];
        tree.byKey = [NSMutableDictionary dictionary];
        tree.roots = @[];
        tree.all = @[];
        objc_setAssociatedObject(view, &dxc_ax_tree_tag, tree, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    }
    return tree;
}

/** The elements directly in [view], for its accessibilityChildren. Notes that a reader asked. */
static NSArray *dxc_ax_children(NSView *view) {
    atomic_store(&dxc_ax_asked, 1);
    return dxc_ax_tree(view, NO).roots ?: @[];
}

/** Whether a reader is listening: one has asked, or VoiceOver is running. */
int32_t dxc_native_accessibility_active(void) {
    if (atomic_load(&dxc_ax_asked)) return 1;
    return NSWorkspace.sharedWorkspace.voiceOverEnabled ? 1 : 0;
}

/**
 * The new value of the oldest SET_VALUE still to be read, as UTF-8 into [out], and how many
 * bytes it is. -1 when there is none. When it does not fit in [capacity] nothing is taken
 * and the answer is the size it needs, so the caller asks again with room for it.
 */
int32_t dxc_native_take_accessibility_argument(char *out, int32_t capacity) {
    @synchronized ([NSApplication class]) {
        if (dxc_ax_arguments.count == 0) return -1;
        NSString *argument = dxc_ax_arguments.firstObject;
        const char *utf8 = argument.UTF8String ?: "";
        size_t length = strlen(utf8);
        if (length > (size_t)capacity) return (int32_t)length;
        memcpy(out, utf8, length);
        [dxc_ax_arguments removeObjectAtIndex:0];
        return (int32_t)length;
    }
}

/**
 * Replaces what [view_pointer] tells a reader with [count] records.
 *
 * Called on the thread the scene lives on, which on this window is the one AppKit answers
 * on, so the tree is rebuilt in place: a reader either sees the tree before this call or the
 * one after it. An element keeps its object for as long as its key lasts, which is what lets
 * VoiceOver's cursor stay on a control while the screen changes around it.
 */
void dxc_native_set_accessibility(const struct dxc_ax_element *elements, int32_t count, void *view_pointer) {
    size_t bytes = (size_t)(count > 0 ? count : 0) * sizeof(struct dxc_ax_element);
    struct dxc_ax_element *copy = bytes > 0 ? malloc(bytes) : NULL;
    if (copy != NULL) memcpy(copy, elements, bytes);
    if (copy == NULL) count = 0;
    dxc_on_main(^{
        NSView *view = (__bridge NSView *)view_pointer;
        DxcAxTree *tree = dxc_ax_tree(view, YES);
        NSMutableDictionary<NSNumber *, DxcAxElement *> *kept = [NSMutableDictionary dictionaryWithCapacity:count];
        NSMutableArray<DxcAxElement *> *all = [NSMutableArray arrayWithCapacity:count];
        NSMutableArray<DxcAxElement *> *focusMoved = [NSMutableArray array];
        NSMutableArray<DxcAxElement *> *valueChanged = [NSMutableArray array];
        BOOL structureChanged = count != (int32_t)tree.all.count;
        for (int32_t index = 0; index < count; index++) {
            const struct dxc_ax_element *record = &copy[index];
            NSNumber *key = @(record->key);
            DxcAxElement *element = tree.byKey[key];
            BOOL existed = element != nil;
            if (!existed) {
                element = [[DxcAxElement alloc] init];
                structureChanged = YES;
            }
            BOOL wasFocused = existed && (element.states & DXC_STATE_FOCUSED) != 0;
            id oldValue = element.axValue;
            element.key = record->key;
            element.states = record->states;
            element.actions = record->actions;
            element.flags = record->flags;
            element.local = NSMakeRect(record->x, record->y, record->width, record->height);
            element.axRole = dxc_ax_string(record->role, sizeof record->role) ?: NSAccessibilityGroupRole;
            element.axSubrole = dxc_ax_string(record->subrole, sizeof record->subrole);
            element.axLabel = dxc_ax_string(record->label, sizeof record->label) ?: @"";
            element.axHint = (record->flags & DXC_AX_HAS_HINT) ? dxc_ax_string(record->hint, sizeof record->hint) : nil;
            if ((record->flags & DXC_AX_HAS_VALUE) == 0) {
                element.axValue = nil;
            } else if (record->flags & DXC_AX_VALUE_IS_NUMBER) {
                element.axValue = @(record->number);
            } else {
                element.axValue = dxc_ax_string(record->value, sizeof record->value) ?: @"";
            }
            element.axMin = (record->flags & DXC_AX_HAS_MIN) ? @(record->range_min) : nil;
            element.axMax = (record->flags & DXC_AX_HAS_MAX) ? @(record->range_max) : nil;
            element.host = view;
            element.kids = @[];
            kept[key] = element;
            [all addObject:element];
            if ((element.states & DXC_STATE_FOCUSED) != 0 && !wasFocused) [focusMoved addObject:element];
            if (existed && !(oldValue == element.axValue || [oldValue isEqual:element.axValue])) {
                [valueChanged addObject:element];
            }
        }
        NSMutableArray<DxcAxElement *> *roots = [NSMutableArray array];
        NSMutableArray<NSMutableArray<DxcAxElement *> *> *kids = [NSMutableArray arrayWithCapacity:count];
        for (int32_t index = 0; index < count; index++) [kids addObject:[NSMutableArray array]];
        for (int32_t index = 0; index < count; index++) {
            DxcAxElement *element = all[index];
            int32_t parent = copy[index].parent;
            if (parent >= 0 && parent < count && parent != index) {
                element.up = all[parent];
                [kids[parent] addObject:element];
            } else {
                element.up = view;
                [roots addObject:element];
            }
        }
        for (int32_t index = 0; index < count; index++) all[index].kids = kids[index];
        tree.byKey = kept;
        tree.all = all;
        tree.roots = roots;
        if (structureChanged) NSAccessibilityPostNotification(view, NSAccessibilityLayoutChangedNotification);
        for (DxcAxElement *element in valueChanged) {
            NSAccessibilityPostNotification(element, NSAccessibilityValueChangedNotification);
        }
        for (DxcAxElement *element in focusMoved) {
            NSAccessibilityPostNotification(element, NSAccessibilityFocusedUIElementChangedNotification);
        }
        free(copy);
    });
}

// One element and everything under it, as a line each in the format the common module reads
// (formatMacosAxNode): depth, role, subrole, title, label, value and the frame, tab separated.
static NSString *dxc_ax_field(id value) {
    NSString *text = @"";
    if ([value isKindOfClass:[NSString class]]) text = value;
    else if ([value isKindOfClass:[NSNumber class]]) text = [value stringValue];
    else if ([value isKindOfClass:[NSAttributedString class]]) text = [value string];
    text = [text stringByReplacingOccurrencesOfString:@"\t" withString:@" "];
    text = [text stringByReplacingOccurrencesOfString:@"\n" withString:@" "];
    return [text stringByReplacingOccurrencesOfString:@"\r" withString:@" "];
}

static void dxc_ax_walk(id element, int depth, NSMutableString *out, int *budget) {
    if (element == nil || *budget <= 0 || depth > 24) return;
    if (![element conformsToProtocol:@protocol(NSAccessibility)]) return;
    (*budget)--;
    id<NSAccessibility> node = element;
    NSRect frame = [element respondsToSelector:@selector(accessibilityFrame)] ? node.accessibilityFrame : NSZeroRect;
    [out appendFormat:@"%d\t%@\t%@\t%@\t%@\t%@\t%g\t%g\t%g\t%g\n", depth,
        dxc_ax_field(node.accessibilityRole), dxc_ax_field(node.accessibilitySubrole),
        dxc_ax_field(node.accessibilityTitle), dxc_ax_field(node.accessibilityLabel),
        dxc_ax_field(node.accessibilityValue),
        frame.origin.x, frame.origin.y, frame.size.width, frame.size.height];
    for (id child in node.accessibilityChildren ?: @[]) dxc_ax_walk(child, depth + 1, out, budget);
}

/**
 * Writes down every accessibility element of this application's visible windows, walking them
 * in process through the same objects AppKit's accessibility server answers clients from, as
 * UTF-8 into [out]: a `#macos` line with the major release, then one line per element. Returns
 * the length, or the size it needs when it does not fit in [capacity], in which case nothing is
 * written. For checks on a machine where no outside client is trusted for accessibility.
 */
int32_t dxc_native_accessibility_snapshot(char *out, int32_t capacity) {
    __block int32_t result = 0;
    dxc_on_main(^{
    @autoreleasepool {
        NSMutableString *text = [NSMutableString string];
        [text appendFormat:@"#macos\t%ld\n", (long)NSProcessInfo.processInfo.operatingSystemVersion.majorVersion];
        int budget = 4000;
        for (NSWindow *window in NSApp.windows) {
            if (!window.visible) continue;
            dxc_ax_walk(window, 0, text, &budget);
        }
        const char *utf8 = text.UTF8String ?: "";
        size_t length = strlen(utf8);
        if (length > (size_t)capacity) {
            result = (int32_t)length;
            return;
        }
        memcpy(out, utf8, length);
        result = (int32_t)length;
    }
    });
    return result;
}

/**
 * Asks this application for its windows through the system's accessibility API, the way an
 * outside client such as VoiceOver does, and answers with the AXError it got (0 for success).
 * [trusted] is set to whether the system trusts this process for accessibility, and [windows]
 * to the number of windows it was told of, or -1.
 *
 * The API is looked up at run time rather than linked, so an application that never asks
 * links nothing more for it. Asked from a worker thread while this one keeps AppKit's run loop
 * turning, because the answer comes from this thread. A machine that has not trusted the
 * process (a CI runner) answers with an error; this says which, so a check can report it
 * rather than guess.
 */
int32_t dxc_native_ax_api_probe(int32_t *trusted, int32_t *windows) {
    *trusted = -1;
    *windows = -1;
    void *services = dlopen("/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices", RTLD_LAZY);
    if (services == NULL) return -1;
    typedef Boolean (*is_trusted_t)(void);
    typedef CFTypeRef (*create_app_t)(pid_t);
    typedef int32_t (*copy_attribute_t)(CFTypeRef, CFStringRef, CFTypeRef *);
    typedef int32_t (*set_timeout_t)(CFTypeRef, float);
    is_trusted_t is_trusted = (is_trusted_t)dlsym(services, "AXIsProcessTrusted");
    create_app_t create_app = (create_app_t)dlsym(services, "AXUIElementCreateApplication");
    copy_attribute_t copy_attribute = (copy_attribute_t)dlsym(services, "AXUIElementCopyAttributeValue");
    set_timeout_t set_timeout = (set_timeout_t)dlsym(services, "AXUIElementSetMessagingTimeout");
    if (is_trusted == NULL || create_app == NULL || copy_attribute == NULL) return -1;
    *trusted = is_trusted() ? 1 : 0;
    __block int32_t error = -1;
    __block CFIndex count = -1;
    __block atomic_int done = 0;
    pid_t pid = getpid();
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        CFTypeRef app = create_app(pid);
        if (app != NULL) {
            if (set_timeout != NULL) set_timeout(app, 3.0f);
            CFTypeRef list = NULL;
            error = copy_attribute(app, CFSTR("AXWindows"), &list);
            if (error == 0 && list != NULL && CFGetTypeID(list) == CFArrayGetTypeID()) {
                count = CFArrayGetCount((CFArrayRef)list);
            }
            if (list != NULL) CFRelease(list);
            CFRelease(app);
        }
        atomic_store(&done, 1);
    });
    for (int turns = 0; turns < 120 && !atomic_load(&done); turns++) {
        if ([NSThread isMainThread]) {
            [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.05]];
        } else {
            usleep(50000);
        }
    }
    *windows = (int32_t)count;
    return atomic_load(&done) ? error : -2;
}

#endif
