#!/usr/bin/env bash
# doctor.sh — checks dev environment health.
# Usage: scripts/doctor.sh [--android]
# Exit 0 = all required tools present. Exit 1 = something missing.

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/versions.env"

# ── Colour helpers ──────────────────────────────────────────────────────────

if [ -t 1 ]; then
  GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; BOLD='\033[1m'; RESET='\033[0m'
else
  GREEN=''; YELLOW=''; RED=''; BOLD=''; RESET=''
fi

ok()   { echo -e "  ${GREEN}✓${RESET} $1"; }
warn() { echo -e "  ${YELLOW}⚠${RESET} $1"; WARNINGS=$((WARNINGS+1)); }
fail() { echo -e "  ${RED}✗${RESET} $1"; FAILURES=$((FAILURES+1)); }

FAILURES=0
WARNINGS=0

# ── Version compare: ver_gte A B → true if A >= B ───────────────────────────

ver_gte() {
  printf '%s\n%s\n' "$2" "$1" | sort -V -C
}

# ── Check: command exists ────────────────────────────────────────────────────

# ver_of <cmd> <version-args...> → prints first semver found in output
ver_of() {
  local cmd="$1"; shift
  "$cmd" "$@" 2>&1 | grep -oE '[0-9]+\.[0-9]+(\.[0-9]+)?' | head -1
}

need() {
  local cmd="$1"; local min="${2:-}"; local label="${3:-$cmd}"; local install_hint="${4:-}"; local ver_args="${5:---version}"
  if ! command -v "$cmd" &>/dev/null; then
    if [ -n "$install_hint" ]; then
      fail "$label not found. Install: $install_hint"
    else
      fail "$label not found"
    fi
    return
  fi
  if [ -z "$min" ]; then
    ok "$label found"
    return
  fi
  local ver
  # shellcheck disable=SC2086
  ver="$(ver_of "$cmd" $ver_args)"
  if ver_gte "$ver" "$min"; then
    ok "$label $ver (>= $min required)"
  else
    fail "$label $ver is too old — need >= $min"
  fi
}

# ── Desktop checks ───────────────────────────────────────────────────────────

echo -e "\n${BOLD}Desktop tools${RESET}"

need go     "$GO_MIN"    "Go"        "https://go.dev/dl/"                                            "version"
need node   "$NODE_MIN"  "Node.js"   "https://nodejs.org/ or: mise install node"                     "--version"
need npm    ""           "npm"
need wails  "$WAILS_MIN" "Wails CLI" "go install github.com/wailsapp/wails/v2/cmd/wails@latest"      "version"
need gh     ""           "GitHub CLI" "https://cli.github.com/"
need git    ""           "git"

# golangci-lint: version string format is different
if command -v golangci-lint &>/dev/null; then
  gl_ver=$(golangci-lint version 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  if ver_gte "$gl_ver" "$GOLANGCI_LINT_VERSION"; then
    ok "golangci-lint $gl_ver (>= $GOLANGCI_LINT_VERSION required)"
  else
    warn "golangci-lint $gl_ver is old — need >= $GOLANGCI_LINT_VERSION. Run: make setup"
  fi
else
  warn "golangci-lint not found — lint step in make check will be skipped"
fi

# backlog
if command -v backlog &>/dev/null; then
  ok "backlog CLI found"
else
  warn "backlog CLI not found — task management unavailable. Install: https://backlog.md"
fi

# go.work sanity
if [ -f "$ROOT/go.work" ]; then
  if go work edit -json &>/dev/null; then
    ok "go.work valid"
  else
    fail "go.work is malformed — run: go work sync"
  fi
fi

# frontend node_modules
if [ -d "$ROOT/frontend" ] && [ ! -d "$ROOT/frontend/node_modules" ]; then
  warn "frontend/node_modules missing — run: make setup"
fi

# ── Android checks (opt-in via --android) ────────────────────────────────────

CHECK_ANDROID=false
for arg in "$@"; do [ "$arg" = "--android" ] && CHECK_ANDROID=true; done

if $CHECK_ANDROID; then
  echo -e "\n${BOLD}Android / Mobile tools${RESET}"

  # Java
  if command -v java &>/dev/null; then
    java_ver=$(java --version 2>&1 | grep -oE '[0-9]+' | head -1)
    if ver_gte "$java_ver" "$JAVA_MIN"; then
      ok "Java $java_ver (>= $JAVA_MIN required)"
    else
      fail "Java $java_ver too old — need >= $JAVA_MIN. Install: SDKMAN or brew install openjdk@17"
    fi
  else
    fail "Java not found — need JDK $JAVA_MIN+. Install: brew install openjdk@17"
  fi

  # Android SDK
  if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then
    ok "ANDROID_HOME=$ANDROID_HOME"
    if [ -f "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ] || \
       ls "$ANDROID_HOME/cmdline-tools"/*/bin/sdkmanager &>/dev/null 2>&1; then
      ok "sdkmanager found"
    else
      warn "sdkmanager not found in ANDROID_HOME — run: make setup-android"
    fi
  else
    fail "ANDROID_HOME not set or missing. Run: make setup-android"
  fi

  # adb
  need adb "" "adb (Android Debug Bridge)" "Install Android SDK platform-tools, then add to PATH"

  # gomobile
  if command -v gomobile &>/dev/null; then
    ok "gomobile found"
  else
    fail "gomobile not found — run: go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init"
  fi

  # JAVA_HOME
  if [ -n "${JAVA_HOME:-}" ]; then
    ok "JAVA_HOME=$JAVA_HOME"
  else
    warn "JAVA_HOME not set — Android builds may fail. Export JAVA_HOME in your shell profile."
  fi
fi

# ── Summary ──────────────────────────────────────────────────────────────────

echo ""
if [ "$FAILURES" -eq 0 ] && [ "$WARNINGS" -eq 0 ]; then
  echo -e "${GREEN}${BOLD}All checks passed.${RESET} Ready to develop."
elif [ "$FAILURES" -eq 0 ]; then
  echo -e "${YELLOW}${BOLD}$WARNINGS warning(s).${RESET} Dev works, but some optional tools missing."
  echo "  Run: make setup"
else
  echo -e "${RED}${BOLD}$FAILURES failure(s), $WARNINGS warning(s).${RESET} Fix failures before proceeding."
  echo "  Run: make setup"
  exit 1
fi
