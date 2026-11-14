#!/usr/bin/env bash
# The design systems publish on this fork's one version line and are built against the fork's
# Compose at that same version. Amper reads neither from gradle.properties, so the version is
# written twice in this project: `version` in publish.module-template.yaml and `compose-fork` in
# libs.versions.toml. This fails when either differs from jetbrains.publication.version.COMPOSE
# in the root gradle.properties, which is the version a release raises everywhere.
#
# Usage: extended/design-systems/scripts/check-version.sh [<gradle.properties>]
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
properties="${1:-$here/../../gradle.properties}"
[[ -f "$properties" ]] || { echo "error: no $properties" >&2; exit 1; }

line="$(sed -n 's/^jetbrains\.publication\.version\.COMPOSE=//p' "$properties")"
published="$(sed -n 's/^    version: *//p' "$here/publish.module-template.yaml")"
compose="$(sed -n 's/^compose-fork *= *"\(.*\)"$/\1/p' "$here/libs.versions.toml")"
status=0
[[ -n "$line" ]] || { echo "error: $properties names no jetbrains.publication.version.COMPOSE" >&2; exit 1; }
if [[ "$published" != "$line" ]]; then
    echo "error: publish.module-template.yaml publishes '$published', the version line is $line" >&2
    status=1
fi
if [[ "$compose" != "$line" ]]; then
    echo "error: libs.versions.toml builds against the fork's Compose '$compose', the version line is $line" >&2
    status=1
fi
[[ $status -ne 0 ]] || echo "design systems: $line, against the fork's Compose $line"
exit $status
