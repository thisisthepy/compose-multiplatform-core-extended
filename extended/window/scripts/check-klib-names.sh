#!/usr/bin/env bash
# Every window module that compiles a klib must give it a globally unique name.
#
# A klib's unique_name defaults to the Amper module name, the directory name, and the
# Kotlin/Native linker rejects two libraries with the same unique_name. A consumer with a
# module called `linux`, `macos` or `common` then cannot link ours. This checks that each
# module with a Kotlin/Native platform passes `-module-name` with its Maven coordinates,
# for both main and test code.
#
# With `--built <dir>` it also reads the manifests Amper wrote under <dir>/build and fails
# if any window klib still carries a bare directory name.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
status=0

while IFS= read -r yaml; do
  if ! grep -Eq 'macos|linux|ios|mingw|native' <(sed -n '/^product:/,/^[a-z]/p' "$yaml"); then
    continue # JVM only: no klib
  fi
  artifact="$(sed -n 's/^ *artifactId: *//p' "$yaml" | head -1)"
  for want in "org.thisisthepy.compose.window:$artifact" "org.thisisthepy.compose.window:$artifact-test"; do
    if ! grep -Eq -- "^ *- *$want *\$" "$yaml"; then
      echo "${yaml#$root/}: missing '-module-name $want' in kotlin.freeCompilerArgs" >&2
      status=1
    fi
  done
done < <(find "$root" -name module.yaml -not -path '*/build/*' | sort)

if [[ "${1:-}" == "--built" ]]; then
  found=0
  while IFS= read -r manifest; do
    found=1
    name="$(sed -n 's/^unique_name=//p' "$manifest")"
    case "$name" in
      org.thisisthepy.compose.window:*) echo "ok: $name" ;;
      *) case "$manifest" in
           */_common_*|*/_macos_*|*/_linux_*)
             echo "${manifest#$root/}: unique_name=$name is not globally unique" >&2
             status=1 ;;
         esac ;;
    esac
  done < <(find "$2/build/tasks" -path '*compile*' -name manifest 2>/dev/null)
  if [[ $found == 0 ]]; then
    echo "no klib manifest under $2/build/tasks; build first" >&2
    status=1
  fi
fi
exit $status
