#!/usr/bin/env bash
# The Windows caption has one geometry in two languages.
#
# windows-shared/c/win32_caption.h answers the system's hit test for the caption strip (which
# point is the maximise button, where the strip ends), and common's Win32Chrome is what the
# scene lays the three buttons out with. A strip or a button of another size on one side is a
# Snap Layouts flyout over the wrong button. This fails when the numbers differ.
set -uo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
header="$root/windows-shared/c/win32_caption.h"
kotlin="$root/common/src/org/thisisthepy/compose/window/Win32Chrome.kt"

c_value() { sed -nE "s/^#define $1 ([0-9]+)$/\1/p" "$header"; }
kotlin_value() { sed -nE "s/^ *const val $1 = ([0-9]+)f?$/\1/p" "$kotlin"; }

status=0
compare() {
    local c k
    c="$(c_value "$1")"
    k="$(kotlin_value "$2")"
    if [[ -z "$c" || -z "$k" || "$c" != "$k" ]]; then
        echo "FAIL: $1 is '${c}' in win32_caption.h and $2 is '${k}' in Win32Chrome.kt" >&2
        status=1
    fi
}
compare DXC_CAPTION_HEIGHT_DIP CAPTION_HEIGHT
compare DXC_CAPTION_BUTTON_WIDTH_DIP CAPTION_BUTTON_WIDTH
compare DXC_CAPTION_BUTTON_COUNT CAPTION_BUTTONS
[[ $status -eq 0 ]] && echo "the Windows caption metrics agree in C and Kotlin: ok"
exit $status
