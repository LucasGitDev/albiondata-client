#!/usr/bin/env bash
# Quality gate — run before marking any task Done.
# Pass: all steps exit 0. Fail: first failing step aborts.

set -eo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

DESKTOP="$ROOT/apps/desktop"
FRONTEND="$DESKTOP/frontend"
if [ -d "$FRONTEND" ]; then
  cd "$FRONTEND"

  echo "=== Frontend: install ==="
  npm ci --silent

  # Build dist/ before Go steps — go:embed all:frontend/dist requires it.
  if [ ! -d "$FRONTEND/dist" ]; then
    echo "=== Frontend: bootstrap dist/ ==="
    npm run build --silent
  fi

  echo "=== Frontend: lint ==="
  npm run lint --if-present

  echo "=== Frontend: typecheck ==="
  npx tsc --noEmit

  echo "=== Frontend: build ==="
  npm run build

  cd "$ROOT"
fi

echo "=== Go: build ==="
go build ./apps/desktop/... ./libs/collector/...

echo "=== Go: vet ==="
go vet ./apps/desktop/... ./libs/collector/...

echo "=== Go: test ==="
go test ./apps/desktop/... ./libs/collector/...

if command -v golangci-lint &>/dev/null; then
  echo "=== Go: lint ==="
  # golangci-lint fails immediately (exit 3) when built with an older Go than the
  # module requires. Probe with --issues-exit-code=0 so we can distinguish a
  # version mismatch (skip gracefully) from real lint failures (hard error).
  _probe_out=$(cd "$DESKTOP" && golangci-lint run --issues-exit-code=0 2>&1 || true)
  if echo "$_probe_out" | grep -q "Go language version"; then
    echo "    golangci-lint skipped — binary built with older Go than project requires"
    echo "    Install golangci-lint built with Go $(go version | awk '{print $3}') to enable lint."
  else
    (cd "$DESKTOP" && golangci-lint run)
    (cd "$ROOT/libs/collector" && golangci-lint run)
  fi
else
  echo "=== Go: lint (skipped — golangci-lint not installed) ==="
fi

echo "=== Wails: native build (smoke test) ==="
if command -v wails &>/dev/null; then
  (cd "$DESKTOP" && GOWORK=off wails build)
else
  echo "    wails CLI not found — skipping smoke test"
fi

echo ""
echo "All checks passed."
