#!/usr/bin/env bash
# Installs the Android SDK into $ANDROID_HOME, which CI keeps in its cache.
set -euo pipefail
: "${ANDROID_HOME:?ANDROID_HOME must be set}"

CMDLINE_TOOLS_ZIP="commandlinetools-linux-16111833_latest.zip"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

if ! command -v unzip > /dev/null || ! command -v curl > /dev/null; then
  apt-get update -qq
  apt-get install -y -qq --no-install-recommends unzip curl > /dev/null
fi

if [ ! -x "$SDKMANAGER" ]; then
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  curl -fsSL -o /tmp/cmdline-tools.zip "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
  unzip -q /tmp/cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm /tmp/cmdline-tools.zip
fi

(yes || true) | "$SDKMANAGER" --licenses > /dev/null
"$SDKMANAGER" "platform-tools" > /dev/null
