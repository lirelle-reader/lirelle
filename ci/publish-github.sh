#!/usr/bin/env bash
# Publishes the signed APK built by release.yml (#1): attached to the release release-please has just made, or else as
# a development pre-release, of which the newest 10 are kept. Runs in CI only.
set -euo pipefail
: "${GH_TOKEN:?}" "${GITHUB_SHA:?}" "${RUNNER_TEMP:?}" "${ANDROID_HOME:?}"

APK=app/build/outputs/apk/release/app-release.apk
AAPT2=$(printf '%s\n' "$ANDROID_HOME"/build-tools/*/aapt2 | sort -V | tail -1)
NAME=$("$AAPT2" dump badging "$APK" | sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p")
FILE="$RUNNER_TEMP/lirelle-$NAME.apk"
cp "$APK" "$FILE"

if [[ ${RELEASE_CREATED:-} == true ]]; then
  gh release upload "${TAG_NAME:?}" "$FILE"
  echo "Attached lirelle-$NAME.apk to $TAG_NAME"
  exit 0
fi

LAST=$(git describe --tags --match 'v*' --abbrev=0 2> /dev/null || true)
CHANGES=$(git log --first-parent --format='- %s' -n 50 ${LAST:+"$LAST..HEAD"})
gh release create "dev-$NAME" "$FILE" --prerelease --target "$GITHUB_SHA" \
  --title "Lirelle $NAME (development build)" --notes "Development build of $GITHUB_SHA: not a stable release.

Changes since ${LAST:-the start}:
$CHANGES"
echo "Published dev-$NAME"

# Development pre-releases beyond the newest 10 go, with their tags.
gh release list --limit 200 --json tagName,isPrerelease,createdAt \
  --jq '[.[] | select(.isPrerelease and (.tagName | startswith("dev-")))] | sort_by(.createdAt) | reverse | .[10:] | .[].tagName' |
  while read -r old; do
    gh release delete "$old" --yes --cleanup-tag
    echo "Deleted $old"
  done
