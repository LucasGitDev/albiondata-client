#!/usr/bin/env bash
# setup-android-dev.sh — Android development environment setup
# Sets up: Android SDK (API 33+), JDK 17, gomobile, adb
# Supports: macOS (brew), Linux (apt/sdkman)
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

ok()   { echo -e "${GREEN}[ok]${NC} $*"; }
warn() { echo -e "${YELLOW}[warn]${NC} $*"; }
fail() { echo -e "${RED}[fail]${NC} $*" >&2; exit 1; }
step() { echo -e "\n==> $*"; }

OS="$(uname -s)"

# ---------------------------------------------------------------------------
# JDK 17
# ---------------------------------------------------------------------------
step "JDK 17"
if java -version 2>&1 | grep -q '"17'; then
  ok "JDK 17 already installed: $(java -version 2>&1 | head -1)"
else
  warn "JDK 17 not active. Installing..."
  case "$OS" in
    Darwin)
      if ! command -v brew &>/dev/null; then
        fail "Homebrew not found. Install it from https://brew.sh then re-run."
      fi
      brew install --cask temurin@17
      JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || echo '')"
      ;;
    Linux)
      if command -v apt-get &>/dev/null; then
        sudo apt-get update -q
        sudo apt-get install -y openjdk-17-jdk
        JAVA_HOME="/usr/lib/jvm/java-17-openjdk-$(dpkg --print-architecture)"
      elif command -v sdk &>/dev/null; then
        sdk install java 17-tem
        JAVA_HOME="$(sdk home java 17-tem)"
      else
        fail "Cannot install JDK 17: no apt-get or sdkman found."
      fi
      ;;
    *)
      fail "Unsupported OS: $OS. Install JDK 17 manually."
      ;;
  esac
fi

# Ensure JAVA_HOME is set
if [[ -z "${JAVA_HOME:-}" ]]; then
  case "$OS" in
    Darwin)
      JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || echo '')"
      ;;
    Linux)
      JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(which java)")")")"
      ;;
  esac
fi

if [[ -z "${JAVA_HOME:-}" ]] || ! java -version 2>&1 | grep -q '"17'; then
  fail "JDK 17 not active after install. Set JAVA_HOME manually."
fi
ok "JAVA_HOME=${JAVA_HOME}"
export JAVA_HOME

# ---------------------------------------------------------------------------
# Android SDK
# ---------------------------------------------------------------------------
step "Android SDK (API 33+)"

ANDROID_HOME="${ANDROID_HOME:-}"
if [[ -z "$ANDROID_HOME" ]]; then
  case "$OS" in
    Darwin)
      ANDROID_HOME="$HOME/Library/Android/sdk"
      ;;
    Linux)
      ANDROID_HOME="$HOME/android-sdk"
      ;;
  esac
fi

CMDLINE_TOOLS_URL=""
case "$OS" in
  Darwin)
    CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip"
    ;;
  Linux)
    CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    ;;
  *)
    fail "Unsupported OS: $OS for Android SDK install."
    ;;
esac

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

if [[ -x "$SDKMANAGER" ]]; then
  ok "Android SDK cmdline-tools already installed at $ANDROID_HOME"
else
  warn "Android cmdline-tools not found. Installing to $ANDROID_HOME..."
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  TMP_ZIP="$(mktemp).zip"
  curl -fsSL "$CMDLINE_TOOLS_URL" -o "$TMP_ZIP"
  unzip -q "$TMP_ZIP" -d "$ANDROID_HOME/cmdline-tools"
  rm "$TMP_ZIP"
  # sdkmanager requires the directory to be named "latest"
  if [[ -d "$ANDROID_HOME/cmdline-tools/cmdline-tools" ]]; then
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  fi
  ok "Cmdline-tools installed."
fi

export ANDROID_HOME
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# Accept licenses and install platform + build-tools
yes | "$SDKMANAGER" --licenses &>/dev/null || true
"$SDKMANAGER" "platform-tools" "platforms;android-33" "build-tools;33.0.2"
ok "Android SDK API 33 installed."

# ---------------------------------------------------------------------------
# adb
# ---------------------------------------------------------------------------
step "adb"
ADB="$ANDROID_HOME/platform-tools/adb"
if [[ -x "$ADB" ]]; then
  ok "adb found: $ADB"
  ADB_DEVICES="$("$ADB" devices 2>/dev/null | tail -n +2 | grep -v '^$' || true)"
  if [[ -n "$ADB_DEVICES" ]]; then
    ok "Devices/emulators detected:"
    echo "$ADB_DEVICES"
  else
    warn "No devices/emulators currently connected (this is fine for CI setup)."
  fi
else
  fail "adb not found at $ADB after platform-tools install."
fi

# ---------------------------------------------------------------------------
# gomobile
# ---------------------------------------------------------------------------
step "gomobile"
if command -v gomobile &>/dev/null; then
  ok "gomobile already installed: $(which gomobile)"
else
  warn "gomobile not found. Installing..."
  go install golang.org/x/mobile/cmd/gomobile@latest
  ok "gomobile installed."
fi

# gomobile init
echo "Running 'gomobile init' (this may take a few minutes)..."
ANDROID_HOME="$ANDROID_HOME" gomobile init
ok "gomobile init completed."

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""
echo "============================================"
echo " Android dev environment ready"
echo "============================================"
echo " JAVA_HOME    = $JAVA_HOME"
echo " ANDROID_HOME = $ANDROID_HOME"
echo " gomobile     = $(which gomobile)"
echo " adb          = $ADB"
echo ""
echo "Add to your shell profile (.zshrc / .bashrc):"
echo "  export JAVA_HOME=\"$JAVA_HOME\""
echo "  export ANDROID_HOME=\"$ANDROID_HOME\""
echo "  export PATH=\"\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH\""
echo "============================================"
