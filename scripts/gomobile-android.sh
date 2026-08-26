#!/usr/bin/env bash
# gomobile-android.sh — build collector.aar for Android
#
# Prerequisites (install via `make setup-android`):
#   - Go toolchain
#   - Android SDK with NDK r23+
#   - gomobile (go install golang.org/x/mobile/cmd/gomobile@latest)
#   - ANDROID_HOME or ANDROID_SDK_ROOT set
#
# Output: apps/mobile/android/app/libs/collector.aar
#
# SDK 34 compatibility: golang.org/x/mobile post-20231127 includes the
# ALooper fix required for API level 34 (Android 14). Use gomobile installed
# via `make setup-android` which pins a compatible version.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$REPO_ROOT/apps/mobile/android/app/libs"
OUT_FILE="$OUT_DIR/collector.aar"
PKG="github.com/ao-data/albiondata-client/libs/collector"

echo "==> Building collector.aar for Android"
echo "    package : $PKG"
echo "    output  : $OUT_FILE"

# Verify prerequisites
if ! command -v gomobile &>/dev/null; then
  echo ""
  echo "ERROR: gomobile not found. Install it with:"
  echo "  go install golang.org/x/mobile/cmd/gomobile@latest"
  echo "  gomobile init"
  echo ""
  echo "Or run: make setup-android"
  exit 1
fi

if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
  echo ""
  echo "ERROR: ANDROID_HOME or ANDROID_SDK_ROOT must be set."
  echo "  export ANDROID_HOME=\$HOME/Library/Android/sdk   # macOS"
  echo "  export ANDROID_HOME=\$HOME/Android/Sdk           # Linux"
  echo ""
  exit 1
fi

mkdir -p "$OUT_DIR"

# Change to libs/collector so gomobile can resolve imports via go.work
cd "$REPO_ROOT/libs/collector"

gomobile bind \
  -target=android \
  -androidapi=21 \
  -o "$OUT_FILE" \
  "$PKG"

echo ""
echo "==> collector.aar generated at: $OUT_FILE"
echo "    Size: $(du -sh "$OUT_FILE" | cut -f1)"
