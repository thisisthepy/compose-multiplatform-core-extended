#!/usr/bin/env bash
# Builds skiko's JVM library (skiko-awt) with the AWT switch, and publishes it to the local
# Maven repository under the version Compose 1.11 asks for.
#
# The switch is awt/0001-awt-switch.patch: `Setup.init` sets a Swing look and feel when the
# run-time property `skiko.rendering.laf.global` is true, and a GraalVM image analysis walks
# that branch whatever the property says, which keeps Swing, `UIManager` and the Java toolkit
# in the image. With the patch the branch is behind `AwtSwitch.available`, a constant when the
# image is built with `-Dcompose.awt=false`. Without the property nothing changes.
#
# Only the Kotlin half is built: the natives come from JetBrains' skiko-awt-runtime-* jars,
# which this does not touch. A consumer reads the local repository before Maven Central, so
# its skiko-awt at this version is the one built here.
#
# Usage: build-skiko-awt.sh <work-dir> [--clean]
#
# Needs: git, a JDK 17 or 21 in JAVA_HOME.
set -euo pipefail

UPSTREAM="https://github.com/JetBrains/skiko.git"
REVISION="9a5b398bb2044fff7e7a84fbfd6f4b803e4427c0"
PUBLISHED_AS="0.144.6"

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

(
    cd "$checkout/skiko"
    ./gradlew --no-daemon --quiet publishAwtPublicationToMavenLocal \
        -Pdeploy.version="$PUBLISHED_AS" -Pdeploy.release=true
)

jar="$HOME/.m2/repository/org/jetbrains/skiko/skiko-awt/$PUBLISHED_AS/skiko-awt-$PUBLISHED_AS.jar"
[[ -f "$jar" ]] || die "no $jar after the build"
echo "published $jar"
