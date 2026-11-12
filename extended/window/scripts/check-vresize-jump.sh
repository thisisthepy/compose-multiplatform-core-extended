#!/usr/bin/env bash
# Judges the log of a vertical resize probe: fails if the fixed-position mark moved.
#
# Both macOS windows lay a magenta square at a fixed place counted from the top of every
# frame (64 points down) and, while the bottom and then the top edge is dragged with mouse
# events, a second thread captures the window's own image and prints where the square is:
#
#     compose-rust: vresize sample i=.. found=1 marker_top_pt=64.00 ...
#
# DXC_VRESIZE_PROBE=1 turns the probe on in the Kotlin/Native window; the native image's
# window prints the same lines. Content that stays where it was put keeps marker_top_pt
# constant through the drag. This reads a saved log (stderr of the run) and fails when the
# largest and smallest marker_top_pt differ by more than the tolerance, 1 point by default.
# Samples where the mark was not found (found=0) say nothing about where it is and are
# skipped, and fewer than 10 found samples is a failure, because a probe that barely saw the
# mark has not shown that it stood still.
#
# Usage: scripts/check-vresize-jump.sh <log> [tolerance-in-points]
# Exit: 0 held still, 1 moved or too little to judge, 2 no such log.
set -uo pipefail

log="${1:-}"
tolerance="${2:-1.0}"
minimum=10

if [[ -z "$log" || ! -f "$log" ]]; then
    echo "check-vresize-jump: no log at '${log}'" >&2
    exit 2
fi

awk -v tolerance="$tolerance" -v minimum="$minimum" '
    /^compose-rust: vresize sample / {
        found = 0; top = ""; frame = ""
        for (i = 1; i <= NF; i++) {
            if ($i ~ /^found=/) found = substr($i, 7) + 0
            if ($i ~ /^marker_top_pt=/) top = substr($i, 15) + 0
            if ($i ~ /^i=/) index_ = substr($i, 3)
        }
        if (found != 1 || top == "") next
        n++
        if (n == 1 || top < low) { low = top; low_at = index_ }
        if (n == 1 || top > high) { high = top; high_at = index_ }
    }
    END {
        if (n < minimum) {
            printf "FAIL: %d samples found the mark, at least %d are needed to judge\n", n, minimum
            exit 1
        }
        range = high - low
        printf "marker_top_pt: %d samples, low %.2f (sample %s), high %.2f (sample %s), range %.2f, tolerance %.2f\n",
            n, low, low_at, high, high_at, range, tolerance
        if (range > tolerance + 1e-9) {
            printf "FAIL: the mark moved %.2f points\n", range
            exit 1
        }
        print "ok: the mark held still"
    }
' "$log"
