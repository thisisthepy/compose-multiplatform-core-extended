/*
 * A drag from another X client onto the GraalVM X11 window, played against x11_window.c
 * itself: XdndEnter, XdndPosition and XdndDrop from a second connection, the files served
 * as text/uri-list when the window asks for the selection, and the paths read back through
 * the same entry points the renderer uses.
 *
 * Needs a display (CI runs it under Xvfb). Exit status 0 means every check passed.
 */
#include "../../c/x11_window.c"

static int failures;

#define CHECK(condition, message) \
    do { if (!(condition)) { fprintf(stderr, "FAIL: %s\n", message); failures++; } } while (0)

static Display *src;
static Window src_win;
static Window target;
static Atom a_enter, a_position, a_status, a_drop, a_finished, a_selection, a_copy, a_list;
static Atom a_uri, a_plain, a_html, a_text;
static const char *served = "";
static Atom served_type;
static int status_seen, status_accepted, finished_seen, finished_accepted;
static int heard_entered, heard_dropped, heard_exited;
static float entered_x, entered_y;

static void send(Atom type, long l0, long l1, long l2, long l3, long l4) {
    XEvent e;
    memset(&e, 0, sizeof e);
    e.xclient.type = ClientMessage;
    e.xclient.window = target;
    e.xclient.message_type = type;
    e.xclient.format = 32;
    e.xclient.data.l[0] = l0;
    e.xclient.data.l[1] = l1;
    e.xclient.data.l[2] = l2;
    e.xclient.data.l[3] = l3;
    e.xclient.data.l[4] = l4;
    XSendEvent(src, target, False, NoEventMask, &e);
    XFlush(src);
}

static void serve(void) {
    while (XPending(src) > 0) {
        XEvent e;
        XNextEvent(src, &e);
        if (e.type == ClientMessage && e.xclient.message_type == a_status) {
            status_seen = 1;
            status_accepted = (e.xclient.data.l[1] & 1) != 0;
        } else if (e.type == ClientMessage && e.xclient.message_type == a_finished) {
            finished_seen = 1;
            finished_accepted = (e.xclient.data.l[1] & 1) != 0;
        } else if (e.type == SelectionRequest) {
            XSelectionRequestEvent *r = &e.xselectionrequest;
            XChangeProperty(src, r->requestor, r->property, served_type, 8, PropModeReplace,
                            (const unsigned char *)served, (int)strlen(served));
            XEvent reply;
            memset(&reply, 0, sizeof reply);
            reply.xselection.type = SelectionNotify;
            reply.xselection.requestor = r->requestor;
            reply.xselection.selection = r->selection;
            reply.xselection.target = r->target;
            reply.xselection.property = r->property;
            reply.xselection.time = r->time;
            XSendEvent(src, r->requestor, False, NoEventMask, &reply);
            XFlush(src);
        }
    }
}

static void drain_events(void) {
    struct dxc_event record;
    while (dxc_native_poll_event(&record)) {
        if (record.kind == DXC_EVENT_FILES_ENTERED) {
            heard_entered++;
            entered_x = record.x;
            entered_y = record.y;
        }
        if (record.kind == DXC_EVENT_FILES_DROPPED) heard_dropped++;
        if (record.kind == DXC_EVENT_FILES_EXITED) heard_exited++;
    }
}

static int settle(int *flag) {
    for (int i = 0; i < 300 && !*flag; i++) {
        dxc_native_pump(0.01);
        serve();
        drain_events();
    }
    return *flag;
}

static void reset(void) {
    status_seen = status_accepted = finished_seen = finished_accepted = 0;
    heard_entered = heard_dropped = heard_exited = 0;
}

static long position_at(int x, int y) {
    int rx, ry;
    Window child;
    XTranslateCoordinates(src, target, DefaultRootWindow(src), x, y, &rx, &ry, &child);
    return ((long)rx << 16) | (long)(ry & 0xFFFF);
}

int main(void) {
    struct dxc_native_window w;
    if (dxc_native_window_open("xdnd", 320, 240, &w) != 0) {
        fprintf(stderr, "FAIL: the window did not open\n");
        return 1;
    }
    target = dxc_window;
    src = XOpenDisplay(NULL);
    src_win = XCreateSimpleWindow(src, DefaultRootWindow(src), 0, 0, 10, 10, 0, 0, 0);
    a_enter = XInternAtom(src, "XdndEnter", False);
    a_position = XInternAtom(src, "XdndPosition", False);
    a_status = XInternAtom(src, "XdndStatus", False);
    a_drop = XInternAtom(src, "XdndDrop", False);
    a_finished = XInternAtom(src, "XdndFinished", False);
    a_selection = XInternAtom(src, "XdndSelection", False);
    a_copy = XInternAtom(src, "XdndActionCopy", False);
    a_list = XInternAtom(src, "XdndTypeList", False);
    a_uri = XInternAtom(src, "text/uri-list", False);
    a_plain = XInternAtom(src, "text/plain", False);
    a_html = XInternAtom(src, "text/html", False);
    a_text = XInternAtom(src, "UTF8_STRING", False);
    for (int i = 0; i < 20; i++) { dxc_native_pump(0.01); drain_events(); }
    reset();

    /* One to three types are in the message itself. */
    served = "file:///tmp/a%20b.txt\r\nfile://localhost/tmp/%C3%BC.txt\r\n# a comment\r\n";
    served_type = a_uri;
    XSetSelectionOwner(src, a_selection, src_win, CurrentTime);
    send(a_enter, (long)src_win, 5L << 24, (long)a_uri, 0, 0);
    send(a_position, (long)src_win, 0, position_at(10, 20), 0, (long)a_copy);
    CHECK(settle(&status_seen), "no XdndStatus for the position");
    CHECK(status_accepted, "a drag offering text/uri-list was not accepted");
    CHECK(heard_entered > 0, "no FILES_ENTERED event");
    CHECK(entered_x == 10.0f && entered_y == 20.0f, "the entered position is not the pointer's");
    send(a_drop, (long)src_win, 0, 0, 0, 0);
    CHECK(settle(&finished_seen), "no XdndFinished for the drop");
    CHECK(finished_accepted, "the drop of files was reported as not accepted");
    CHECK(heard_dropped == 1, "no single FILES_DROPPED event");
    char paths[1024];
    int32_t length = dxc_native_dropped_paths(paths, sizeof paths);
    const char expect[] = "/tmp/a b.txt\0/tmp/\xC3\xBC.txt";
    CHECK(length == (int32_t)sizeof expect - 1 && memcmp(paths, expect, (size_t)length) == 0,
          "the dropped paths are not the two files, NUL separated and decoded");

    /* More than three types: they are in the XdndTypeList property of the source. */
    reset();
    served = "file:///srv/data/c.txt\r\n";
    {
        long types[4] = { (long)a_plain, (long)a_html, (long)a_text, (long)a_uri };
        XChangeProperty(src, src_win, a_list, XA_ATOM, 32, PropModeReplace,
                        (const unsigned char *)types, 4);
        XFlush(src);
    }
    XSetSelectionOwner(src, a_selection, src_win, CurrentTime);
    send(a_enter, (long)src_win, (5L << 24) | 1, (long)a_plain, (long)a_html, (long)a_text);
    send(a_position, (long)src_win, 0, position_at(30, 40), 0, (long)a_copy);
    CHECK(settle(&status_seen), "no XdndStatus for the long type list");
    CHECK(status_accepted, "text/uri-list in a long type list was not found");
    send(a_drop, (long)src_win, 0, 0, 0, 0);
    CHECK(settle(&finished_seen), "no XdndFinished for the second drop");
    length = dxc_native_dropped_paths(paths, sizeof paths);
    CHECK(length == 15 && memcmp(paths, "/srv/data/c.txt", 15) == 0,
          "the second drop's path is wrong");

    /* A drag of text only is refused. */
    reset();
    send(a_enter, (long)src_win, 5L << 24, (long)a_plain, 0, 0);
    send(a_position, (long)src_win, 0, position_at(10, 20), 0, (long)a_copy);
    CHECK(settle(&status_seen), "no XdndStatus for the text drag");
    CHECK(!status_accepted, "a drag of text was accepted as a drag of files");
    CHECK(heard_entered == 0, "a drag of text produced FILES_ENTERED");

    XCloseDisplay(src);
    if (failures == 0) printf("xdnd drop test: all checks passed\n");
    return failures == 0 ? 0 : 1;
}
