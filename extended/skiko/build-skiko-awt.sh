#!/usr/bin/env bash
# Builds skiko's JVM library (skiko-awt) with the AWT switch, and publishes it to the local
# Maven repository as 0.144.6.1: a version of its own, one above the 0.144.6 Compose 1.11 asks
# for.
#
# The switch is awt/0001-awt-switch.patch: `Setup.init` sets a Swing look and feel when the
# run-time property `skiko.rendering.laf.global` is true, and a GraalVM image analysis walks
# that branch whatever the property says, which keeps Swing, `UIManager` and the Java toolkit
# in the image. With the patch the branch is behind `AwtSwitch.available`, a constant when the
# image is built with `-Dcompose.awt=false`. Without the property nothing changes.
#
# Only the Kotlin half is built: the natives come from JetBrains' skiko-awt-runtime-* jars at
# 0.144.6, which this does not touch, and the published metadata names them at that version.
#
# Why a version of its own. Published as 0.144.6 it would sit at JetBrains' coordinates, and a
# resolver that already holds JetBrains' 0.144.6 (the Kotlin toolchain's cache does) never
# looks at the local repository for it: the build links, and the image carries an unpatched
# skiko-awt and keeps the Java toolkit. skiko stays in JetBrains' group, because every Compose
# module asks for it there, so the version is what tells the two apart. A suffix would not do:
# a tag sorts below the release in Gradle and above it in Maven. 0.144.6.1 sorts above 0.144.6
# in both, so a consumer that names it also wins over the 0.144.6 that Compose brings in.
#
# Usage: build-skiko-awt.sh <work-dir> [--clean]
#
# Needs: git, a JDK 17 or 21 in JAVA_HOME.
set -euo pipefail

UPSTREAM="https://github.com/JetBrains/skiko.git"
REVISION="9a5b398bb2044fff7e7a84fbfd6f4b803e4427c0"
# The skiko version the sources are, and the version this build publishes as.
UPSTREAM_VERSION="0.144.6"
PUBLISHED_AS="0.144.6.1"

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
base="$HOME/.m2/repository/org/jetbrains/skiko/skiko-awt"
rm -rf "${base:?}/$PUBLISHED_AS"
mkdir -p "$base/$PUBLISHED_AS"
for file in "$from"/*; do
    name="$(basename "$file")"
    target="$base/$PUBLISHED_AS/${name/skiko-awt-$UPSTREAM_VERSION/skiko-awt-$PUBLISHED_AS}"
    case "$name" in
        # Checksums of the files before renaming. Gradle and Maven check them when present.
        *.md5|*.sha1|*.sha256|*.sha512) ;;
        *.pom|*.module)
            # Its own name and version only. The first version in each file is its own; the
            # runtime dependencies that follow keep the upstream version.
            FROM="$UPSTREAM_VERSION" TO="$PUBLISHED_AS" perl -0777 -pe '
                s/(skiko-awt)-\Q$ENV{FROM}\E/$1-$ENV{TO}/g;
                s/(<version>)\Q$ENV{FROM}\E(<\/version>)/$1$ENV{TO}$2/;
                s/("version":\s*")\Q$ENV{FROM}\E(")/$1$ENV{TO}$2/;
            ' "$file" > "$target" ;;
        *) cp "$file" "$target" ;;
    esac
done
rm -rf "$staging"

jar="$base/$PUBLISHED_AS/skiko-awt-$PUBLISHED_AS.jar"
[[ -f "$jar" ]] || die "no $jar after the build"
echo "published $jar"
