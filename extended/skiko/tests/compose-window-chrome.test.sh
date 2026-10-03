#!/usr/bin/env bash
# Checks the parts of a Compose window's chrome on Windows that need no Windows.
#
# 1. The decisions in windows-chrome/compose_window_chrome.h: what a point in the caption
#    band is, how far a maximised window's band moves down, and when a step of a live resize
#    waits for its frame and when that wait is over. They are arithmetic, so any C compiler
#    can check them.
# 2. That every native method Compose declares (org.jetbrains.skiko.compose.WindowsWindowChrome)
#    is defined by composeWindowChrome.cc under its JNI name, and nothing there is left
#    unreached. A missing one is not a build failure: a native image links the gap to a stop,
#    and a JVM throws when the window opens, and either way the window silently keeps the
#    system caption.
# 3. That the window procedure is wired the way the decisions assume: the drag is bracketed by
#    WM_ENTERSIZEMOVE and WM_EXITSIZEMOVE, the size message asks before it waits, and the
#    swap reports each frame it presents.
#
# Usage: compose-window-chrome.test.sh   (from anywhere; needs cc or clang)
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
root="$(cd "$here/../.." && pwd)"
header="$here/windows-chrome/compose_window_chrome.h"
source_file="$here/windows-chrome/composeWindowChrome.cc"
patch_file="$here/0002-compose-window-chrome-present-hook.patch"
kotlin_file="$root/compose/ui/ui/src/desktopMain/kotlin/org/jetbrains/skiko/compose/WindowsWindowChrome.desktop.kt"
red=0
fail() { echo "fail: $*"; red=1; }

for file in "$header" "$source_file" "$patch_file" "$kotlin_file"; do
    [[ -f "$file" ]] || { echo "missing $file"; exit 1; }
done

# 2. Native methods and their definitions.
declared="$(grep -oE 'external fun [A-Za-z0-9_]+' "$kotlin_file" | awk '{print $3}' | sort)"
defined="$(grep -oE 'Java_org_jetbrains_skiko_compose_WindowsWindowChrome_[A-Za-z0-9_]+' "$source_file" |
    sed 's/^Java_org_jetbrains_skiko_compose_WindowsWindowChrome_//' | sort -u)"
[[ -n "$declared" ]] || fail "no native methods found in $kotlin_file"
for name in $declared; do
    grep -qx "$name" <<< "$defined" || fail "Compose declares $name but composeWindowChrome.cc does not define it"
done
for name in $defined; do
    grep -qx "$name" <<< "$declared" || fail "composeWindowChrome.cc defines $name, which Compose never declares"
done

# 3. Wiring.
handling() {
    awk -v label="$1" '
        $0 ~ "case " label ":" { inside = 1 }
        inside { print }
        inside && /^        return|^        break;/ { exit }
    ' "$source_file"
}
grep -q "compose_resize_expect" <<< "$(handling WM_SIZE)" || fail "the size message does not ask whether to wait"
grep -q "composeWaitForFrame" <<< "$(handling WM_SIZE)" || fail "the size message never waits for its frame"
grep -q "dragging = 1" <<< "$(handling WM_ENTERSIZEMOVE)" || fail "WM_ENTERSIZEMOVE does not start the drag"
grep -q "dragging = 0" <<< "$(handling WM_EXITSIZEMOVE)" || fail "WM_EXITSIZEMOVE does not end the drag"
grep -q "compose_caption_hit" <<< "$(handling WM_NCHITTEST)" || fail "the hit test does not use the band's decisions"
grep -q "QS_SENDMESSAGE" "$source_file" ||
    fail "the wait does not pump sent messages, so the thread that draws could block on it"
grep -q "^+.*composeWindowChromePresented" "$patch_file" || fail "the swap does not report the frames it presents"

# 1. The decisions.
compiler="${CC:-}"
if [[ -z "$compiler" ]]; then
    for candidate in cc clang gcc; do
        if command -v "$candidate" >/dev/null 2>&1; then compiler="$candidate"; break; fi
    done
fi
if [[ -z "$compiler" ]]; then
    echo "skip: no C compiler, the decisions were not checked"
    exit "$red"
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cat > "$work/probe.c" <<'PROBE'
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "compose_window_chrome.h"

int main(void) {
    // Hit test: border 8, band 32, buttons 138, at 100%.
    assert(compose_caption_hit(0, 500, 0, 8, 32, 138) == COMPOSE_CAPTION_TOP);
    assert(compose_caption_hit(7, 500, 0, 8, 32, 138) == COMPOSE_CAPTION_TOP);
    assert(compose_caption_hit(8, 500, 0, 8, 32, 138) == COMPOSE_CAPTION_DRAG);
    assert(compose_caption_hit(31, 500, 0, 8, 32, 138) == COMPOSE_CAPTION_DRAG);
    assert(compose_caption_hit(32, 500, 0, 8, 32, 138) == COMPOSE_CAPTION_CLIENT);
    // The buttons take ordinary clicks, and the strip left of them drags.
    assert(compose_caption_hit(16, 0, 0, 8, 32, 138) == COMPOSE_CAPTION_CLIENT);
    assert(compose_caption_hit(16, 137, 0, 8, 32, 138) == COMPOSE_CAPTION_CLIENT);
    assert(compose_caption_hit(16, 138, 0, 8, 32, 138) == COMPOSE_CAPTION_DRAG);
    // Above the buttons, the top border still resizes.
    assert(compose_caption_hit(2, 10, 0, 8, 32, 138) == COMPOSE_CAPTION_TOP);
    // Maximised: no border to resize from, the band starts at the monitor's edge.
    assert(compose_caption_hit(0, 500, 1, 8, 32, 138) == COMPOSE_CAPTION_DRAG);
    assert(compose_caption_hit(2, 10, 1, 8, 32, 138) == COMPOSE_CAPTION_CLIENT);
    assert(compose_caption_top_inset(1, 8) == 8);
    assert(compose_caption_top_inset(0, 8) == 0);

    // Scaling as MulDiv rounds: 100%, 125%, 150%, 200%, and no answer read as 100%.
    assert(compose_scale(32, 96) == 32);
    assert(compose_scale(32, 120) == 40);
    assert(compose_scale(138, 120) == 173);
    assert(compose_scale(32, 144) == 48);
    assert(compose_scale(46, 192) == 92);
    assert(compose_scale(32, 0) == 32);

    struct compose_resize_sync sync;
    int32_t w = 0, h = 0;
    memset(&sync, 0, sizeof sync);
    compose_resize_note_client(&sync, 800, 600);

    // Nothing has presented yet: nothing to wait for, inside a drag or not.
    sync.dragging = 1;
    assert(compose_resize_expect(&sync, 900, 600, &w, &h) == 0);
    compose_resize_note_present(&sync, 800, 600);

    // Outside a drag the frame loop takes the size; nothing waits.
    sync.dragging = 0;
    assert(compose_resize_expect(&sync, 900, 600, &w, &h) == 0);

    // Inside a drag a new size waits for a frame of the same size.
    sync.dragging = 1;
    assert(compose_resize_expect(&sync, 900, 640, &w, &h) == 1);
    assert(w == 900 && h == 640);

    // A move is not a resize.
    assert(compose_resize_expect(&sync, 800, 600, &w, &h) == 0);

    // The wait ends on a frame presented after it began, at that size, and on no other.
    uint64_t start = sync.presents;
    assert(!compose_resize_satisfied(&sync, start, 900, 640));
    compose_resize_note_present(&sync, 800, 600);
    assert(!compose_resize_satisfied(&sync, start, 900, 640));
    compose_resize_note_present(&sync, 900, 640);
    assert(compose_resize_satisfied(&sync, start, 900, 640));

    // Back to a size shown before: the old frame does not count, a new one does.
    compose_resize_note_client(&sync, 900, 640);
    assert(compose_resize_expect(&sync, 800, 600, &w, &h) == 1);
    start = sync.presents;
    assert(!compose_resize_satisfied(&sync, start, 800, 600));
    compose_resize_note_present(&sync, 800, 600);
    assert(compose_resize_satisfied(&sync, start, 800, 600));

    // A surface smaller than the client area (a menu bar above it) changes by what the
    // client area changed by.
    memset(&sync, 0, sizeof sync);
    sync.dragging = 1;
    compose_resize_note_client(&sync, 800, 600);
    compose_resize_note_present(&sync, 800, 580);
    assert(compose_resize_expect(&sync, 820, 650, &w, &h) == 1);
    assert(w == 820 && h == 630);

    // A size that leaves the surface nothing is not waited for.
    assert(compose_resize_expect(&sync, 0, 10, &w, &h) == 0);

    puts("ok");
    return 0;
}
PROBE
if ! "$compiler" -std=c99 -Wall -Werror -I"$here/windows-chrome" "$work/probe.c" -o "$work/probe"; then
    fail "the decisions header does not compile on its own"
elif ! "$work/probe"; then
    fail "the decisions gave a wrong answer (see the assertion above)"
fi

if (( red )); then
    exit 1
fi
echo "ok: caption band and live resize decisions, native method names, window procedure wiring"
