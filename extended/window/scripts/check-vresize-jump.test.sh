#!/usr/bin/env bash
# Tests for check-vresize-jump.sh: it passes a log where the fixed-position mark stays put
# and fails one where it jumps, has too few samples, or has no probe output at all.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
check="$here/check-vresize-jump.sh"
work="$here/../build/vresize-jump-test"
rm -rf "$work"
mkdir -p "$work"
failures=0

sample() { # index marker_top_pt found
    printf 'compose-rust: vresize sample i=%s t_ms=1.00 image=800x600 scale=2.00 found=%s marker_top_pt=%s marker_h_pt=16.00 bottom_gap_pt=32.00 shown_frame=3 shown_height_px=1200 image_height_px=1200 last_drawn_frame=3 last_drawn_height_px=1200 control_top_pt=64.00\n' "$1" "${3:-1}" "$2"
}

expect() { # name expected_status log
    "$check" "$3" >"$work/out.txt" 2>&1
    local status=$?
    if [[ "$status" != "$2" ]]; then
        echo "FAIL $1: expected exit $2, got $status"
        sed 's/^/    /' "$work/out.txt"
        failures=$((failures + 1))
    else
        echo "ok   $1"
    fi
}

# A mark that stays at 64 points, with the noise of a rounded pixel.
{ for i in $(seq 1 30); do sample "$i" "$(awk -v i="$i" 'BEGIN{printf "%.2f", 64 + (i % 2) * 0.5}')"; done; } >"$work/steady.log"
expect "a mark that holds still passes" 0 "$work/steady.log"

# The same, with the bytes of a real log around it.
{ echo "compose-rust: vresize begin window_frame=0,0,800x600"; cat "$work/steady.log"; echo "compose-rust: vresize done"; } >"$work/framed.log"
expect "begin and done lines are ignored" 0 "$work/framed.log"

# A jump of 15 points halfway through.
{ for i in $(seq 1 15); do sample "$i" 64.00; done; for i in $(seq 16 30); do sample "$i" 79.00; done; } >"$work/jump.log"
expect "a mark that moves 15 points fails" 1 "$work/jump.log"

# Exactly at the tolerance passes, just over it fails.
{ for i in $(seq 1 10); do sample "$i" 64.00; done; sample 11 65.00; for i in $(seq 12 20); do sample "$i" 64.00; done; } >"$work/edge.log"
expect "a step of exactly 1 point passes" 0 "$work/edge.log"
{ for i in $(seq 1 10); do sample "$i" 64.00; done; sample 11 65.10; for i in $(seq 12 20); do sample "$i" 64.00; done; } >"$work/over.log"
expect "a step of 1.1 points fails" 1 "$work/over.log"

# Samples where the mark was not found say nothing about where it is.
{ cat "$work/steady.log"; for i in $(seq 31 40); do sample "$i" -1.00 0; done; } >"$work/lost.log"
expect "samples without the mark are not counted" 0 "$work/lost.log"

# A probe that found the mark only a few times proves nothing.
{ for i in $(seq 1 3); do sample "$i" 64.00; done; } >"$work/few.log"
expect "too few samples fails" 1 "$work/few.log"

echo "no probe output here" >"$work/none.log"
expect "a log with no probe output fails" 1 "$work/none.log"

expect "a missing log fails" 2 "$work/absent.log"

exit "$failures"
