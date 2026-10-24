#!/usr/bin/env bash
# Usage: wm-drag.sh <window name>
#
# Resizes a window the way a person does, through a window manager that holds each size for
# the client's _NET_WM_SYNC_REQUEST counter: starts openbox on the display (it honours the
# sync protocol during an interactive resize), waits until it manages the window, then drags
# with Alt and the right button, openbox's own binding for resizing, in steps, and stops the
# window manager again. Both X11 windows' tests run this same script, so both are driven
# identically. Needs DISPLAY (CI runs it under Xvfb), openbox and xdotool.
set -euo pipefail

name="$1"
steps="${WM_DRAG_STEPS:-40}"

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
    # Managed once the window manager has put its frame extents on it.
    if [[ -n "$window" ]] && xprop -id "$window" _NET_FRAME_EXTENTS 2>/dev/null | grep -q '='; then
        break
    fi
    sleep 0.1
done
[[ -n "$window" ]] || { echo "wm-drag: no window named '$name'" >&2; exit 1; }
sleep 0.5

eval "$(xdotool getwindowgeometry --shell "$window")"
# Inside the window, near its bottom right corner, which is the corner openbox resizes from.
xdotool mousemove --window "$window" $((WIDTH - 12)) $((HEIGHT - 12))
sleep 0.2
xdotool keydown alt
xdotool mousedown 3
for _ in $(seq 1 "$steps"); do
    xdotool mousemove_relative -- 4 3
    sleep 0.03
done
sleep 0.3
xdotool mouseup 3
xdotool keyup alt
sleep 0.5
eval "$(xdotool getwindowgeometry --shell "$window")"
echo "wm-drag: dragged '$name' through $steps steps to ${WIDTH}x${HEIGHT}"
