#!/usr/bin/env bash
# The window code reads and writes a pointer event's `buttons` through the named constants.
#
# WindowEvent.PRIMARY_HELD, SECONDARY_HELD, MIDDLE_HELD and SECONDARY_BUTTON (and DXC_* in the
# C headers) are the one encoding. A bare number against `buttons` is how the scene came to
# test bit 2 while the platforms set `1 shl 16`. This fails on `buttons and 2`,
# `buttons & 1`, `buttons |= 4` and the like, in Kotlin and in C.
set -uo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"

pattern='buttons[[:space:]]*(and|or|&|\||&=|\|=)[[:space:]]*~?[(]?[0-9]'
hits="$(grep -rEn "$pattern" "$root" \
    --include='*.kt' --include='*.m' --include='*.c' --include='*.h' --include='*.java' \
    --exclude-dir=build --exclude-dir=.kotlin --exclude-dir=scripts || true)"
if [[ -n "$hits" ]]; then
    echo "$hits"
    echo "FAIL: a pointer event's buttons is tested with a bare number; use the WindowEvent constants (DXC_* in C)" >&2
    exit 1
fi
echo "buttons are read through the named constants: ok"
