#!/usr/bin/env bash
# Quality gate — run before marking any task Done.
# Pass: all steps exit 0. Fail: first failing step aborts.

set -eo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

FRONTEND="$ROOT/frontend"
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
go build $(go list ./... | grep -v '/frontend/')

echo "=== Go: vet ==="
go vet $(go list ./... | grep -v '/frontend/')

echo "=== Go: test ==="
go test $(go list ./... | grep -v '/frontend/')

# Test each go.work sub-module that is not the root module.
if [ -f "$ROOT/go.work" ]; then
  while IFS= read -r moddir; do
    [ "$moddir" = "." ] && continue
    echo "=== Go: test sub-module $moddir ==="
    (cd "$ROOT/$moddir" && go test ./...)
  done < <(go work edit -json | grep '"DiskPath"' | awk -F'"' '{print $4}')
fi

if command -v golangci-lint &>/dev/null; then
  echo "=== Go: lint ==="
  golangci-lint run
else
  echo "=== Go: lint (skipped — golangci-lint not installed) ==="
fi

echo "=== Wails: native build (smoke test) ==="
if command -v wails &>/dev/null; then
  wails build
else
  echo "    wails CLI not found — skipping smoke test"
fi

echo ""
echo "All checks passed."
