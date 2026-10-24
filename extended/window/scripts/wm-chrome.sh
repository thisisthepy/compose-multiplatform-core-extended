#!/usr/bin/env bash
# Usage: wm-chrome.sh <window name> <modern|system> [caption height]
#
# Drives the chrome of an X11 window the way a person does, through openbox, and checks what
# the window manager did with it. Both X11 windows' tests run this same script against a window
# whose listener does what a scene does with a press: in the caption it calls startMove, within
# EDGE pixels of an edge it calls startResize for that edge.
#
#   modern: the window has no frame of the manager's (_NET_FRAME_EXTENTS all zero), a drag of
#           the caption moves it by as much as the pointer moved and leaves its size, a drag at
#           each of the eight edges and corners changes the size from that side only, and a
#           double click in the caption maximises it and a second one restores it.
#   system: the window has the manager's frame (a title bar above it).
#
# Each step prints a line; any check that fails prints FAIL and the script exits 1 at the end.
# Needs DISPLAY (CI runs it under Xvfb), openbox, xdotool and xprop.
set -euo pipefail

name="$1"
mode="$2"
caption="${3:-46}"
edge=4          # how far inside an edge the press is made
pull=40         # how far each edge is pulled outward
tolerance=6
failures=0

fail() {
    echo "wm-chrome: FAIL: $*" >&2
    failures=$((failures + 1))
}

started_wm=""
if ! xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null | grep -q 'window id'; then
    openbox --sm-disable >/dev/null 2>&1 &
    started_wm=$!
fi
stop_wm() { [[ -n "$started_wm" ]] && { openbox --exit >/dev/null 2>&1 || kill "$started_wm" 2>/dev/null || true; }; }
trap stop_wm EXIT

for _ in $(seq 1 100); do
    xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null | grep -q 'window id' && break
    sleep 0.1
done

window=""
for _ in $(seq 1 100); do
    window="$(xdotool search --onlyvisible --name "$name" 2>/dev/null | head -1 || true)"
    if [[ -n "$window" ]] && xprop -id "$window" _NET_FRAME_EXTENTS 2>/dev/null | grep -q '='; then
        break
    fi
    sleep 0.1
done
[[ -n "$window" ]] || { echo "wm-chrome: no window named '$name'" >&2; exit 1; }
sleep 0.5

geometry() {
    eval "$(xdotool getwindowgeometry --shell "$window")"
    echo "$X $Y $WIDTH $HEIGHT"
}

near() { # near <actual> <expected>
    local difference=$(($1 - $2))
    ((difference >= -tolerance && difference <= tolerance))
}

extents="$(xprop -id "$window" _NET_FRAME_EXTENTS | sed 's/.*= //')"
echo "wm-chrome: frame extents of '$name' are $extents"
if [[ "$mode" == "system" ]]; then
    top="$(echo "$extents" | cut -d, -f3 | tr -d ' ')"
    ((top > 0)) || fail "a System window has no title bar of the manager's (extents $extents)"
    if xprop -id "$window" _MOTIF_WM_HINTS 2>/dev/null | grep -q '= 0x2, 0x0, 0x0'; then
        fail "a System window asked for no decorations"
    fi
    exit $((failures > 0))
fi

[[ "$extents" == "0, 0, 0, 0" ]] || fail "a Modern window has a frame of the manager's (extents $extents)"
xprop -id "$window" _MOTIF_WM_HINTS | grep -q '= 0x2, 0x0, 0x0' ||
    fail "a Modern window did not ask for no decorations: $(xprop -id "$window" _MOTIF_WM_HINTS)"

# Somewhere with room on every side, so each edge can be pulled outward.
xdotool windowmove --sync "$window" 300 200 || xdotool windowmove "$window" 300 200
sleep 0.5

# A drag of the caption, in steps, by (200, 150).
read -r x0 y0 w0 h0 <<<"$(geometry)"
xdotool mousemove --window "$window" 60 $((caption / 2))
sleep 0.2
xdotool mousedown 1
sleep 0.3
for _ in $(seq 1 10); do
    xdotool mousemove_relative -- 20 15
    sleep 0.05
done
sleep 0.3
xdotool mouseup 1
sleep 0.5
read -r x1 y1 w1 h1 <<<"$(geometry)"
echo "wm-chrome: caption drag moved '$name' from $x0,$y0 to $x1,$y1 (size ${w0}x$h0 to ${w1}x$h1)"
near "$((x1 - x0))" 200 && near "$((y1 - y0))" 150 || fail "the caption drag moved the window by $((x1 - x0)),$((y1 - y0)), not 200,150"
[[ "$w1" == "$w0" && "$h1" == "$h0" ]] || fail "the caption drag changed the size"

xdotool windowmove --sync "$window" 300 200 || xdotool windowmove "$window" 300 200
sleep 0.5

# One edge or corner: press within $edge pixels of it, pull it $pull pixels outward.
# Arguments: the edge's name, where to press as fractions (0, 1 or half) of the width and
# height, and which way outward is in x and in y (-1, 0, 1).
pull_edge() {
    local label="$1" fx="$2" fy="$3" dx="$4" dy="$5"
    read -r bx by bw bh <<<"$(geometry)"
    local px py
    case "$fx" in 0) px=$edge ;; 1) px=$((bw - 1 - edge)) ;; *) px=$((bw / 2)) ;; esac
    case "$fy" in 0) py=$edge ;; 1) py=$((bh - 1 - edge)) ;; *) py=$((bh / 2)) ;; esac
    xdotool mousemove --window "$window" "$px" "$py"
    sleep 0.2
    xdotool mousedown 1
    sleep 0.3
    for _ in $(seq 1 8); do
        xdotool mousemove_relative -- $((dx * pull / 8)) $((dy * pull / 8))
        sleep 0.05
    done
    sleep 0.3
    xdotool mouseup 1
    sleep 0.6
    read -r ax ay aw ah <<<"$(geometry)"
    echo "wm-chrome: $label edge: $bx,$by ${bw}x$bh to $ax,$ay ${aw}x$ah"
    local wantW=$((bw + (dx != 0 ? pull : 0))) wantH=$((bh + (dy != 0 ? pull : 0)))
    local wantX=$((bx - (dx < 0 ? pull : 0))) wantY=$((by - (dy < 0 ? pull : 0)))
    near "$aw" "$wantW" || fail "$label: width $aw, wanted $wantW"
    near "$ah" "$wantH" || fail "$label: height $ah, wanted $wantH"
    near "$ax" "$wantX" || fail "$label: x $ax, wanted $wantX (the opposite edge must stay)"
    near "$ay" "$wantY" || fail "$label: y $ay, wanted $wantY (the opposite edge must stay)"
    xdotool windowmove --sync "$window" 300 200 || xdotool windowmove "$window" 300 200
    sleep 0.3
}

pull_edge left 0 half -1 0
pull_edge right 1 half 1 0
pull_edge top half 0 0 -1
pull_edge bottom half 1 0 1
pull_edge top-left 0 0 -1 -1
pull_edge top-right 1 0 1 -1
pull_edge bottom-left 0 1 -1 1
pull_edge bottom-right 1 1 1 1

# A double click in the caption maximises, and another restores.
maximised() { xprop -id "$window" _NET_WM_STATE | grep -q '_NET_WM_STATE_MAXIMIZED_VERT' && xprop -id "$window" _NET_WM_STATE | grep -q '_NET_WM_STATE_MAXIMIZED_HORZ'; }
read -r x0 y0 w0 h0 <<<"$(geometry)"
xdotool mousemove --window "$window" 60 $((caption / 2))
sleep 0.2
xdotool click --repeat 2 --delay 120 1
sleep 0.8
read -r x1 y1 w1 h1 <<<"$(geometry)"
echo "wm-chrome: double click: ${w0}x$h0 to ${w1}x$h1, state $(xprop -id "$window" _NET_WM_STATE)"
maximised || fail "a double click in the caption did not maximise the window"
xdotool mousemove --window "$window" 60 $((caption / 2))
sleep 0.2
xdotool click --repeat 2 --delay 120 1
sleep 0.8
read -r x2 y2 w2 h2 <<<"$(geometry)"
echo "wm-chrome: second double click: ${w1}x$h1 to ${w2}x$h2"
maximised && fail "a second double click did not restore the window"
near "$w2" "$w0" && near "$h2" "$h0" || fail "the restored size ${w2}x$h2 is not the size before, ${w0}x$h0"

if ((failures > 0)); then
    echo "wm-chrome: $failures check(s) failed" >&2
    exit 1
fi
echo "wm-chrome: every check passed for '$name'"
