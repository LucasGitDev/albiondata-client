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
  (cd "$DESKTOP" && golangci-lint run)
  (cd "$ROOT/libs/collector" && golangci-lint run)
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
