#!/usr/bin/env bash
# Tests that buildSrc/repos.gradle puts COMPOSE_MAVEN_MIRROR before Maven Central, with a made-up
# project that applies it and prints the repositories it added. Nothing is resolved or built.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
work="$(mktemp -d "${RUNNER_TEMP:-$here}/maven-mirror.XXXXXX")"
trap 'rm -rf "$work"' EXIT
failures=0
fail() { echo "  FAIL: $1" >&2; failures=$((failures + 1)); }

# repos.gradle wants ext.supportRootFolder, the checkout's root, as the build's settings set it.
echo "rootProject.name = 'maven-mirror-test'" > "$work/settings.gradle"
cat > "$work/build.gradle" <<GRADLE
ext.supportRootFolder = new File("$root")
apply from: "$root/buildSrc/repos.gradle"
repos.addMavenRepositories(repositories)
tasks.register("printRepositories") {
    def lines = repositories.collect { it instanceof MavenArtifactRepository ? it.url.toString() : it.name }
    doLast { lines.each { println("repo: " + it) } }
}
GRADLE

repositories_with() { # <mirror value, or - for unset>: the repositories, one per line, in order
    local -a env_args=(env -u COMPOSE_MAVEN_MIRROR)
    [[ "$1" == - ]] || env_args=(env "COMPOSE_MAVEN_MIRROR=$1")
    "${env_args[@]}" "$root/gradlew" -p "$work" -q --no-daemon --offline printRepositories |
        sed -n 's/^repo: //p'
}

index_of() { # <line> <text>: the 1-based line of the first line that starts with <line>, or 0
    awk -v want="$1" 'index($0, want) == 1 { print NR; found = 1; exit } END { if (!found) print 0 }' <<< "$2"
}

echo "maven mirror"

central="https://repo.maven.apache.org/maven2"

unset_list="$(repositories_with -)"
[[ "$(index_of "$central" "$unset_list")" -gt 0 ]] || fail "Maven Central is not in the list:
$unset_list"
grep -q 'mirror.example' <<< "$unset_list" && fail "a mirror is added with the variable unset"

empty_list="$(repositories_with "")"
[[ "$empty_list" == "$unset_list" ]] || fail "an empty variable changes the list:
$empty_list"

one="https://mirror.example/maven2"
one_list="$(repositories_with "$one")"
mirror_at="$(index_of "$one" "$one_list")"
central_at="$(index_of "$central" "$one_list")"
[[ $mirror_at -gt 0 ]] || fail "the mirror is not in the list:
$one_list"
[[ $mirror_at -gt 0 && $mirror_at -lt $central_at ]] || fail "the mirror is not before Maven Central:
$one_list"
# skiko from the local repository stays first: a mirror's skiko root names no mingwX64.
[[ "$(head -1 <<< "$one_list")" == "$(head -1 <<< "$unset_list")" ]] || fail "the mirror is put before the local skiko:
$one_list"

# Several, space or comma separated, in the order given. A URL has a colon, so not ':'.
two="https://mirror.example/a, https://mirror.example/b"
two_list="$(repositories_with "$two")"
a_at="$(index_of "https://mirror.example/a" "$two_list")"
b_at="$(index_of "https://mirror.example/b" "$two_list")"
central_at="$(index_of "$central" "$two_list")"
[[ $a_at -gt 0 && $a_at -lt $b_at && $b_at -lt $central_at ]] || fail "two mirrors are not both before Maven Central, in order:
$two_list"

if [[ $failures -gt 0 ]]; then
    echo "$failures failed" >&2
    exit 1
fi
echo "ok"
