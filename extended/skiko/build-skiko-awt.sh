#!/usr/bin/env bash
# Builds skiko's JVM library (skiko-awt) with the AWT switch, and publishes it to the local
# Maven repository as org.thisisthepy.compose.skiko:skiko-awt at this fork's version.
#
# The switch is awt/0001-awt-switch.patch: `Setup.init` sets a Swing look and feel when the
# run-time property `skiko.rendering.laf.global` is true, and a GraalVM image analysis walks
# that branch whatever the property says, which keeps Swing, `UIManager` and the Java toolkit
# in the image. With the patch the branch is behind `AwtSwitch.available`, a constant when the
# image is built with `-Dcompose.awt=false`. Without the property nothing changes.
#
# Only the Kotlin half is built: the natives come from JetBrains' skiko-awt-runtime-* jars at
# 0.144.6, which this does not touch, and the published metadata names them at that version.
# The package names are skiko's own; only the coordinate is this fork's.
#
# Why this fork's group. Everything this fork ships goes out under org.thisisthepy, the
# namespace it can publish to, on one version line (gradle.properties), with the upstream
# version in the POM's description. Compose still asks for JetBrains' org.jetbrains.skiko:skiko-awt,
# so the Gradle module metadata declares that coordinate as a capability of this one: Gradle
# then sees the two as one library and keeps one, and the capability's version (0.144.6.1,
# above JetBrains' 0.144.6) makes this one the higher, which is the one a build that resolves
# the conflict by version keeps (the fork's Gradle plugin does).
#
# Usage: build-skiko-awt.sh <work-dir> [--clean]
#
# Needs: git, python3, a JDK 17 or 21 in JAVA_HOME.
set -euo pipefail

UPSTREAM="https://github.com/JetBrains/skiko.git"
REVISION="9a5b398bb2044fff7e7a84fbfd6f4b803e4427c0"
# The skiko version the sources are, the coordinate and version this build publishes as (the
# fork's one version line, gradle.properties), and the version of JetBrains' coordinate it
# declares as a capability.
UPSTREAM_VERSION="0.144.6"
PUBLISHED_GROUP="org.thisisthepy.compose.skiko"
PUBLISHED_AS="1.11.1-ext.1"
CAPABILITY_VERSION="0.144.6.1"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

die() {
    echo "error: $1" >&2
    shift
    for line in "$@"; do echo "       $line" >&2; done
    exit 1
}

[[ $# -ge 1 ]] || die "no work directory given" "usage: build-skiko-awt.sh <work-dir> [--clean]"
WORK="$1"
shift
[[ "${1:-}" == "--clean" ]] && { rm -rf "$WORK"; shift; }
[[ -z "${1:-}" ]] || die "unknown argument '$1'" "usage: build-skiko-awt.sh <work-dir> [--clean]"
[[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]] ||
    die "JAVA_HOME does not name a JDK" "skiko's Gradle build needs a JDK 17 or 21."

mkdir -p "$WORK"
checkout="$WORK/skiko"
[[ -d "$checkout/.git" ]] || git clone --quiet --no-checkout "$UPSTREAM" "$checkout"
git -C "$checkout" cat-file -e "$REVISION^{commit}" 2>/dev/null || git -C "$checkout" fetch --quiet origin "$REVISION"
git -C "$checkout" -c advice.detachedHead=false checkout --quiet --force "$REVISION"
git -C "$checkout" clean --quiet -fdx -e skiko/build -e skiko/dependencies -e .gradle
for patch in "$HERE"/awt/*.patch; do
    git -C "$checkout" apply --whitespace=nowarn "$patch" ||
        die "$(basename "$patch") does not apply to skiko $REVISION"
done

# Built as the upstream version, because skiko writes its own version into the dependencies on
# the runtime jars, and those exist only at the upstream version. Built into a repository of
# its own, so that nothing is ever left at JetBrains' coordinates in the local one.
staging="$WORK/staging"
rm -rf "$staging"
(
    cd "$checkout/skiko"
    ./gradlew --no-daemon --quiet publishAwtPublicationToMavenLocal \
        -Pdeploy.version="$UPSTREAM_VERSION" -Pdeploy.release=true \
        "-Dmaven.repo.local=$staging"
)

from="$staging/org/jetbrains/skiko/skiko-awt/$UPSTREAM_VERSION"
[[ -d "$from" ]] || die "no $from after the build"
base="$HOME/.m2/repository/$(tr . / <<< "$PUBLISHED_GROUP")/skiko-awt"
rm -rf "${base:?}/$PUBLISHED_AS"
mkdir -p "$base/$PUBLISHED_AS"
python3 "$HERE/republish-skiko-awt.py" "$from" "$base/$PUBLISHED_AS" \
    "$UPSTREAM_VERSION" "$PUBLISHED_GROUP" "$PUBLISHED_AS" "$CAPABILITY_VERSION"
rm -rf "$staging"

jar="$base/$PUBLISHED_AS/skiko-awt-$PUBLISHED_AS.jar"
[[ -f "$jar" ]] || die "no $jar after the build"
echo "published $jar"
