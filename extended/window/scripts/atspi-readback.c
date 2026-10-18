/*
 * Reads a window's accessibility tree back the way a screen reader does: through libatspi, from
 * the registry on the accessibility bus. Both X11 windows' tests run it against a window they
 * have published, and the plugin's checks run it against a running application.
 *
 * Usage:
 *   atspi-readback <frame name> [--expect role:name]... [--text name=value]...
 *                  [--set name=value]... [--press name]... [--timeout seconds]
 *
 * Finds the frame called <frame name> among the desktop's applications, walks everything under
 * it and requires, for each --expect, an object with that role and that name. Roles are named
 * as libatspi names them (entry, push button, label, check box, password text, ...).
 * --text requires the object of that name to hold that text through the Text interface;
 * --set replaces it through EditableText; --press does its first action. The walk is tried
 * again until it succeeds or the timeout (default 20 seconds) runs out, because a window may
 * publish its tree after it joins. Prints every object it found; exits 0 when every
 * requirement holds and 1 otherwise.
 *
 * Build: cc -o atspi-readback atspi-readback.c $(pkg-config --cflags --libs atspi-2 gobject-2.0)
 */
#include <atspi/atspi.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define MAX_FOUND 512
#define MAX_REQUIREMENTS 64

struct found {
    AtspiAccessible *object;
    char *role;
    char *name;
    int depth;
};

static struct found found[MAX_FOUND];
static int found_count;

static const char *expects[MAX_REQUIREMENTS];
static int expect_count;
static const char *texts[MAX_REQUIREMENTS];
static int text_count;
static const char *sets[MAX_REQUIREMENTS];
static int set_count;
static const char *presses[MAX_REQUIREMENTS];
static int press_count;

static double now_seconds(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (double)t.tv_sec + (double)t.tv_nsec / 1e9;
}

static void forget(void) {
    for (int i = 0; i < found_count; i++) {
        g_object_unref(found[i].object);
        g_free(found[i].role);
        g_free(found[i].name);
    }
    found_count = 0;
}

static void walk(AtspiAccessible *object, int depth) {
    if (found_count >= MAX_FOUND || depth > 64) return;
    GError *error = NULL;
    struct found *entry = &found[found_count++];
    entry->object = g_object_ref(object);
    entry->depth = depth;
    AtspiRole role = atspi_accessible_get_role(object, &error);
    if (error != NULL) { g_clear_error(&error); role = ATSPI_ROLE_INVALID; }
    entry->role = atspi_role_get_name(role);
    if (entry->role == NULL) entry->role = g_strdup("unknown");
    entry->name = atspi_accessible_get_name(object, &error);
    if (error != NULL) { g_clear_error(&error); entry->name = g_strdup(""); }
    gint children = atspi_accessible_get_child_count(object, &error);
    if (error != NULL) { g_clear_error(&error); return; }
    for (gint index = 0; index < children; index++) {
        AtspiAccessible *child = atspi_accessible_get_child_at_index(object, index, &error);
        if (error != NULL) { g_clear_error(&error); continue; }
        if (child == NULL) continue;
        walk(child, depth + 1);
        g_object_unref(child);
    }
}

/* The frame of that name, from any application on the desktop. A new reference, or NULL. */
static AtspiAccessible *find_frame(const char *frame_name) {
    AtspiAccessible *desktop = atspi_get_desktop(0);
    if (desktop == NULL) return NULL;
    atspi_accessible_clear_cache(desktop);
    GError *error = NULL;
    AtspiAccessible *result = NULL;
    gint apps = atspi_accessible_get_child_count(desktop, &error);
    if (error != NULL) { g_clear_error(&error); apps = 0; }
    for (gint a = 0; a < apps && result == NULL; a++) {
        AtspiAccessible *app = atspi_accessible_get_child_at_index(desktop, a, &error);
        if (error != NULL) { g_clear_error(&error); continue; }
        if (app == NULL) continue;
        // Asked again every time rather than remembered, because what this window says
        // changes between one try and the next.
        atspi_accessible_set_cache_mask(app, ATSPI_CACHE_NONE);
        gint frames = atspi_accessible_get_child_count(app, &error);
        if (error != NULL) { g_clear_error(&error); frames = 0; }
        for (gint f = 0; f < frames && result == NULL; f++) {
            AtspiAccessible *frame = atspi_accessible_get_child_at_index(app, f, &error);
            if (error != NULL) { g_clear_error(&error); continue; }
            if (frame == NULL) continue;
            gchar *name = atspi_accessible_get_name(frame, &error);
            if (error != NULL) { g_clear_error(&error); name = NULL; }
            if (name != NULL && strcmp(name, frame_name) == 0) result = g_object_ref(frame);
            g_free(name);
            g_object_unref(frame);
        }
        g_object_unref(app);
    }
    g_object_unref(desktop);
    return result;
}

static struct found *named(const char *name) {
    for (int i = 0; i < found_count; i++) {
        if (strcmp(found[i].name, name) == 0) return &found[i];
    }
    return NULL;
}

/* Splits "left<separator>right" into two new strings. */
static int split(const char *pair, char separator, char **left, char **right) {
    const char *at = strchr(pair, separator);
    if (at == NULL) return 0;
    *left = g_strndup(pair, (gsize)(at - pair));
    *right = g_strdup(at + 1);
    return 1;
}

/* Whether everything --expect asks for is there. Prints what is missing when [report]. */
static int expectations_met(int report) {
    int met = 1;
    for (int i = 0; i < expect_count; i++) {
        char *role = NULL, *name = NULL;
        if (!split(expects[i], ':', &role, &name)) {
            fprintf(stderr, "atspi-readback: --expect %s is not role:name\n", expects[i]);
            exit(2);
        }
        int there = 0;
        for (int j = 0; j < found_count; j++) {
            if (strcmp(found[j].role, role) == 0 && strcmp(found[j].name, name) == 0) there = 1;
        }
        if (!there) {
            met = 0;
            if (report) fprintf(stderr, "atspi-readback: FAIL: no %s called \"%s\"\n", role, name);
        }
        g_free(role);
        g_free(name);
    }
    return met;
}

static int check_texts(void) {
    int ok = 1;
    for (int i = 0; i < text_count; i++) {
        char *name = NULL, *value = NULL;
        if (!split(texts[i], '=', &name, &value)) exit(2);
        struct found *object = named(name);
        AtspiText *text = object != NULL ? atspi_accessible_get_text_iface(object->object) : NULL;
        if (text == NULL) {
            fprintf(stderr, "atspi-readback: FAIL: \"%s\" has no Text interface\n", name);
            ok = 0;
        } else {
            GError *error = NULL;
            gchar *held = atspi_text_get_text(text, 0, -1, &error);
            if (error != NULL || held == NULL || strcmp(held, value) != 0) {
                fprintf(stderr, "atspi-readback: FAIL: \"%s\" holds \"%s\", not \"%s\"\n", name,
                        held != NULL ? held : "(error)", value);
                ok = 0;
            } else {
                printf("atspi-readback: \"%s\" holds \"%s\"\n", name, held);
            }
            g_clear_error(&error);
            g_free(held);
            g_object_unref(text);
        }
        g_free(name);
        g_free(value);
    }
    return ok;
}

static int do_sets(void) {
    int ok = 1;
    for (int i = 0; i < set_count; i++) {
        char *name = NULL, *value = NULL;
        if (!split(sets[i], '=', &name, &value)) exit(2);
        struct found *object = named(name);
        AtspiEditableText *editable = object != NULL ? atspi_accessible_get_editable_text_iface(object->object) : NULL;
        GError *error = NULL;
        gboolean done = editable != NULL && atspi_editable_text_set_text_contents(editable, value, &error);
        if (!done || error != NULL) {
            fprintf(stderr, "atspi-readback: FAIL: could not set \"%s\" to \"%s\"%s%s\n", name, value,
                    error != NULL ? ": " : "", error != NULL ? error->message : "");
            ok = 0;
        } else {
            printf("atspi-readback: set \"%s\" to \"%s\"\n", name, value);
        }
        g_clear_error(&error);
        if (editable != NULL) g_object_unref(editable);
        g_free(name);
        g_free(value);
    }
    return ok;
}

static int do_presses(void) {
    int ok = 1;
    for (int i = 0; i < press_count; i++) {
        struct found *object = named(presses[i]);
        AtspiAction *action = object != NULL ? atspi_accessible_get_action_iface(object->object) : NULL;
        GError *error = NULL;
        gboolean done = action != NULL && atspi_action_do_action(action, 0, &error);
        if (!done || error != NULL) {
            fprintf(stderr, "atspi-readback: FAIL: could not press \"%s\"%s%s\n", presses[i],
                    error != NULL ? ": " : "", error != NULL ? error->message : "");
            ok = 0;
        } else {
            printf("atspi-readback: pressed \"%s\"\n", presses[i]);
        }
        g_clear_error(&error);
        if (action != NULL) g_object_unref(action);
    }
    return ok;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: atspi-readback <frame name> [--expect role:name]... [--text name=value]... "
                        "[--set name=value]... [--press name]... [--timeout seconds]\n");
        return 2;
    }
    const char *frame_name = argv[1];
    double timeout = 20.0;
    for (int i = 2; i < argc; i++) {
        if (i + 1 >= argc) { fprintf(stderr, "atspi-readback: %s needs a value\n", argv[i]); return 2; }
        const char *option = argv[i];
        const char *value = argv[++i];
        if (strcmp(option, "--expect") == 0 && expect_count < MAX_REQUIREMENTS) expects[expect_count++] = value;
        else if (strcmp(option, "--text") == 0 && text_count < MAX_REQUIREMENTS) texts[text_count++] = value;
        else if (strcmp(option, "--set") == 0 && set_count < MAX_REQUIREMENTS) sets[set_count++] = value;
        else if (strcmp(option, "--press") == 0 && press_count < MAX_REQUIREMENTS) presses[press_count++] = value;
        else if (strcmp(option, "--timeout") == 0) timeout = atof(value);
        else { fprintf(stderr, "atspi-readback: unknown option %s\n", option); return 2; }
    }
    if (atspi_init() > 1) {
        fprintf(stderr, "atspi-readback: FAIL: no accessibility bus\n");
        return 1;
    }
    double deadline = now_seconds() + timeout;
    AtspiAccessible *frame = NULL;
    int met = 0;
    while (1) {
        frame = find_frame(frame_name);
        if (frame != NULL) {
            forget();
            walk(frame, 0);
            met = expectations_met(0);
        }
        if (met || now_seconds() >= deadline) break;
        if (frame != NULL) { g_object_unref(frame); frame = NULL; }
        usleep(200000);
    }
    if (frame == NULL) {
        fprintf(stderr, "atspi-readback: FAIL: no frame called \"%s\" on the desktop\n", frame_name);
        return 1;
    }
    for (int i = 0; i < found_count; i++) {
        printf("atspi-readback: %*s%s \"%s\"\n", found[i].depth * 2, "", found[i].role, found[i].name);
    }
    int ok = expectations_met(1);
    ok = check_texts() && ok;
    ok = do_sets() && ok;
    ok = do_presses() && ok;
    forget();
    g_object_unref(frame);
    printf("atspi-readback: %s\n", ok ? "every requirement holds" : "FAIL");
    return ok ? 0 : 1;
}
