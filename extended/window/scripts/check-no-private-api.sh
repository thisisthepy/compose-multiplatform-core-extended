#!/usr/bin/env bash
# The window code calls no private AppKit API.
#
# A private selector or key (an `_`-prefixed name such as `_cornerRadius`, reached through
# key value coding, performSelector or a selector built from a string) is rejected by Mac
# App Store review, and an update can rename or remove it without notice. This fails if any
# window source passes such a name to valueForKey, setValue:forKey, performSelector,
# respondsToSelector, NSSelectorFromString, sel_registerName or @selector, or names
# `_cornerRadius` at all. Scripts and workflows are not window code and are not scanned.
set -uo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"

calls='valueForKey(Path)?|setValue[^"]*forKey(Path)?|performSelector[A-Za-z]*|respondsToSelector|NSSelectorFromString|sel_registerName|sel_getUid'
pattern="(($calls)[[:space:]]*[:(][^\"]{0,80}@?\"_)|(@selector\\([[:space:]]*_)|_cornerRadius"

hits="$(grep -rEn "$pattern" "$root" \
    --include='*.kt' --include='*.m' --include='*.c' --include='*.h' --include='*.java' \
    --exclude-dir=build --exclude-dir=.kotlin --exclude-dir=scripts || true)"
if [[ -n "$hits" ]]; then
    echo "$hits"
    echo "FAIL: the window code names a private selector or key; use a public API, or a table checked in CI" >&2
    exit 1
fi
echo "no private selector or key in the window code: ok"
