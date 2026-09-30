#!/usr/bin/env bash
# Installs the Android SDK pieces this project needs into $ANDROID_HOME
# (default /home/user/android-sdk) and writes local.properties. Used in cloud
# sessions, which start from a clean container; harmless to re-run.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-/home/user/android-sdk}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS_DIR="$ANDROID_HOME/cmdline-tools"
SDKMANAGER="$TOOLS_DIR/latest/bin/sdkmanager"

if [ ! -x "$SDKMANAGER" ]; then
    zip=$(curl -sS --max-time 30 https://developer.android.com/studio \
        | grep -o 'commandlinetools-linux-[0-9]*_latest.zip' | head -1)
    [ -n "$zip" ] || { echo "could not find command-line tools zip name" >&2; exit 1; }
    tmp=$(mktemp -d)
    curl -sS -o "$tmp/cli.zip" "https://dl.google.com/android/repository/$zip"
    mkdir -p "$TOOLS_DIR"
    unzip -q -o "$tmp/cli.zip" -d "$tmp/unzipped"
    rm -rf "$TOOLS_DIR/latest"
    mv "$tmp/unzipped/cmdline-tools" "$TOOLS_DIR/latest"
    rm -rf "$tmp"
fi

yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" \
    "platform-tools" "platforms;android-36" "build-tools;36.0.0" >/dev/null

echo "sdk.dir=$ANDROID_HOME" > "$REPO_ROOT/local.properties"
echo "Android SDK ready at $ANDROID_HOME (local.properties written)"
