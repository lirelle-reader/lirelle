#!/usr/bin/env bash
# Checks that a release of Lirelle builds again the same as F-Droid would build it (#26): the release's signed APK is
# downloaded, its commit is built unsigned in F-Droid's buildserver image, and apksigcopier compares the two (the very
# check F-Droid makes before it publishes our signed APK).
#
# usage: ci/check-reproducible.sh <tag>      a release (v*) or a development pre-release (dev-*)
# needs: docker, gh, uv (for apksigcopier), and apksigner from the Android SDK's build tools ($ANDROID_HOME, else
# ~/Android/Sdk); diffoscope, if present, shows what differs. KEEP=1 keeps the work folder.
set -euo pipefail

tag=${1:?usage: ci/check-reproducible.sh <tag>}
repo=lirelle-reader/lirelle
image=registry.gitlab.com/fdroid/fdroidserver:buildserver
root=$(cd "$(dirname "$0")/.." && pwd)
# Before anything is downloaded or built: apksigcopier calls apksigner, from the SDK's newest build tools.
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
tools=$(printf '%s\n' "$sdk"/build-tools/* | sort -V | tail -1)
if [[ ! -x $tools/apksigner ]]; then
    echo "apksigner not found under $sdk/build-tools: set ANDROID_HOME to an Android SDK with build tools" >&2
    exit 1
fi
PATH="$tools:$PATH"
# The versionCode counts main's commits: a shallow clone would build another one and fail for no reason.
if [[ $(git -C "$root" rev-parse --is-shallow-repository) == true ]]; then
    echo "$root is a shallow clone: git fetch --unshallow first" >&2
    exit 1
fi
work=$(mktemp -d)
if [[ -n ${KEEP:-} ]]; then
    echo "(the work stays in $work)"
else
    trap 'rm -rf "$work"' EXIT
fi

echo "== $tag: the release's APK"
gh release download "$tag" -R "$repo" -p '*.apk' -D "$work/release"
signed=$(ls "$work"/release/*.apk)

echo "== $tag: its commit, with main's history (the versionCode counts it)"
git -C "$root" fetch -q origin "refs/tags/$tag:refs/tags/$tag" 2> /dev/null || true
git clone -q --no-checkout "$root" "$work/src"
git -C "$work/src" checkout -q "$tag"

# As F-Droid's recipe will: Debian's JDK 25, installed in the image, and Gradle not allowed to download one.
echo "== $tag: built unsigned in $image, with Debian's JDK 25"
docker run --rm -v "$work/src:/build" -v "$work/gradle:/gradle" -w /build -e GRADLE_USER_HOME=/gradle \
    -e ANDROID_HOME=/opt/android-sdk "$image" bash -c \
    'apt-get update -qq && apt-get install -y -qq openjdk-25-jdk-headless > /dev/null &&
     git config --global --add safe.directory /build &&
     ./gradlew --no-daemon --no-build-cache --no-configuration-cache \
       -Porg.gradle.java.installations.auto-download=false \
       -Porg.gradle.java.installations.paths=/usr/lib/jvm/java-25-openjdk-amd64 :app:assembleRelease
     status=$?; chown -R '"$(id -u):$(id -g)"' /build /gradle; exit $status'
unsigned=$work/src/app/build/outputs/apk/release/app-release-unsigned.apk

echo "== comparison"
if uvx apksigcopier compare "$signed" --unsigned "$unsigned"; then
    echo "REPRODUCIBLE: $tag builds the same as its signed APK"
else
    echo "NOT REPRODUCIBLE: $tag differs from its signed APK" >&2
    if command -v diffoscope > /dev/null; then
        diffoscope --exclude-directory-metadata=yes "$signed" "$unsigned" || true
    else
        echo "(diffoscope shows what differs: uvx diffoscope, or the package)" >&2
    fi
    exit 1
fi
