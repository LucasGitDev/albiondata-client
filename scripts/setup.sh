#!/usr/bin/env bash
# setup.sh — idempotent dev environment bootstrap.
# Usage: scripts/setup.sh [--android]
# Safe to run multiple times. Skips already-installed tools.

set -eo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/versions.env"

OS="$(uname -s)"  # Linux | Darwin

# ── Colour helpers ──────────────────────────────────────────────────────────

if [ -t 1 ]; then
  GREEN='\033[0;32m'; BLUE='\033[0;34m'; BOLD='\033[1m'; RESET='\033[0m'
else
  GREEN=''; BLUE=''; BOLD=''; RESET=''
fi

step() { echo -e "\n${BLUE}▶${RESET} ${BOLD}$1${RESET}"; }
done_() { echo -e "  ${GREEN}✓${RESET} $1"; }
skip() { echo -e "  · $1 (already ok)"; }

# ── Go tools ─────────────────────────────────────────────────────────────────

step "Go tools"

# golangci-lint
if command -v golangci-lint &>/dev/null; then
  gl_ver=$(golangci-lint version 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  if printf '%s\n%s\n' "$GOLANGCI_LINT_VERSION" "$gl_ver" | sort -V -C; then
    skip "golangci-lint $gl_ver"
  else
    echo "  Upgrading golangci-lint to $GOLANGCI_LINT_VERSION..."
    curl -sSfL https://raw.githubusercontent.com/golangci/golangci-lint/master/install.sh \
      | sh -s -- -b "$(go env GOPATH)/bin" "v${GOLANGCI_LINT_VERSION}"
    done_ "golangci-lint $GOLANGCI_LINT_VERSION installed"
  fi
else
  echo "  Installing golangci-lint $GOLANGCI_LINT_VERSION..."
  curl -sSfL https://raw.githubusercontent.com/golangci/golangci-lint/master/install.sh \
    | sh -s -- -b "$(go env GOPATH)/bin" "v${GOLANGCI_LINT_VERSION}"
  done_ "golangci-lint installed → $(go env GOPATH)/bin"
fi

# wails
if command -v wails &>/dev/null; then
  skip "wails found"
else
  step "Wails CLI"
  go install "github.com/wailsapp/wails/v2/cmd/wails@latest"
  done_ "wails installed"
fi

# ── Frontend ─────────────────────────────────────────────────────────────────

if [ -d "$ROOT/frontend" ]; then
  step "Frontend dependencies"
  cd "$ROOT/frontend"
  if [ -d node_modules ] && [ package-lock.json -nt node_modules ]; then
    npm ci --silent
    done_ "npm ci (refreshed)"
  elif [ ! -d node_modules ]; then
    npm ci --silent
    done_ "npm ci"
  else
    skip "node_modules up to date"
  fi
  cd "$ROOT"
fi

# ── Android (opt-in) ─────────────────────────────────────────────────────────

SETUP_ANDROID=false
for arg in "$@"; do [ "$arg" = "--android" ] && SETUP_ANDROID=true; done

if $SETUP_ANDROID; then
  step "Android environment"

  # Detect ANDROID_HOME default
  if [ -z "${ANDROID_HOME:-}" ]; then
    if [ "$OS" = "Darwin" ]; then
      ANDROID_HOME="$HOME/Library/Android/sdk"
    else
      ANDROID_HOME="$HOME/Android/Sdk"
    fi
    echo "  ANDROID_HOME not set — defaulting to $ANDROID_HOME"
    echo "  Add to your shell profile: export ANDROID_HOME=\"$ANDROID_HOME\""
    echo "                              export PATH=\"\$PATH:\$ANDROID_HOME/platform-tools\""
  fi
  export ANDROID_HOME

  # Java check — must be pre-installed
  if ! command -v java &>/dev/null; then
    echo ""
    echo "  Java not found. Install JDK $JAVA_MIN first:"
    if [ "$OS" = "Darwin" ]; then
      echo "    brew install openjdk@17"
      echo "    sudo ln -sfn /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-17.jdk"
    else
      echo "    sudo apt-get install -y openjdk-17-jdk  # Debian/Ubuntu"
      echo "    sudo dnf install -y java-17-openjdk-devel  # Fedora"
    fi
    echo "  Then re-run: make setup-android"
    exit 1
  fi
  skip "java found"

  # Android SDK cmdline-tools
  if [ ! -d "$ANDROID_HOME/cmdline-tools" ]; then
    echo "  Android cmdline-tools not found."
    echo "  Install via Android Studio SDK Manager, or:"
    echo "    1. Download from https://developer.android.com/studio#command-tools"
    echo "    2. Unzip to $ANDROID_HOME/cmdline-tools/latest/"
    echo ""
    echo "  After install, re-run: make setup-android"
    exit 1
  fi

  # sdkmanager: install platform-tools and target SDK
  SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
  if [ ! -f "$SDKMANAGER" ]; then
    SDKMANAGER=$(ls "$ANDROID_HOME/cmdline-tools"/*/bin/sdkmanager 2>/dev/null | head -1)
  fi

  if [ -n "$SDKMANAGER" ] && [ -f "$SDKMANAGER" ]; then
    echo "  Installing Android platform-tools and SDK API $ANDROID_SDK_MIN_API..."
    yes | "$SDKMANAGER" --licenses &>/dev/null || true
    "$SDKMANAGER" "platform-tools" "platforms;android-$ANDROID_SDK_MIN_API" \
      "build-tools;$ANDROID_SDK_MIN_API.0.0" &>/dev/null
    done_ "Android SDK API $ANDROID_SDK_MIN_API + platform-tools"
  else
    skip "sdkmanager not found — skipping SDK install"
  fi

  # gomobile
  if command -v gomobile &>/dev/null; then
    skip "gomobile found"
  else
    echo "  Installing gomobile..."
    go install golang.org/x/mobile/cmd/gomobile@latest
    go install golang.org/x/mobile/cmd/gobind@latest
    gomobile init
    done_ "gomobile installed and initialized"
  fi
fi

# ── Done ─────────────────────────────────────────────────────────────────────

echo ""
echo -e "${GREEN}${BOLD}Setup complete.${RESET} Run: make doctor"
