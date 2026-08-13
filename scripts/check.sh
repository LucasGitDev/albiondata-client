#!/usr/bin/env bash
# Quality gate — run before marking any task Done.
# Pass: all steps exit 0. Fail: first failing step aborts.

set -eo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "=== Go: build ==="
go build $(go list ./... | grep -v '/frontend/')

echo "=== Go: vet ==="
go vet $(go list ./... | grep -v '/frontend/')

echo "=== Go: test ==="
go test $(go list ./... | grep -v '/frontend/')

if command -v golangci-lint &>/dev/null; then
  echo "=== Go: lint ==="
  golangci-lint run
else
  echo "=== Go: lint (skipped — golangci-lint not installed) ==="
fi

FRONTEND="$ROOT/frontend"
if [ -d "$FRONTEND" ]; then
  echo "=== Frontend: install ==="
  cd "$FRONTEND"
  npm ci --silent

  echo "=== Frontend: lint ==="
  npm run lint --if-present

  echo "=== Frontend: typecheck ==="
  npx tsc --noEmit

  echo "=== Frontend: build ==="
  npm run build

  cd "$ROOT"
fi

echo "=== Wails: native build (smoke test) ==="
if command -v wails &>/dev/null; then
  wails build
else
  echo "    wails CLI not found — skipping smoke test"
fi

echo ""
echo "All checks passed."
