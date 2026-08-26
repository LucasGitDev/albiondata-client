.PHONY: check run fmt validate-fmt build-windows build-linux build-darwin generate doctor setup setup-android gomobile-android

# ── Quality gate ─────────────────────────────────────────────────────────────

check:
	scripts/check.sh

# ── Dev environment ──────────────────────────────────────────────────────────

## doctor: verify all required tools are installed and at minimum versions
doctor:
	scripts/doctor.sh

## doctor-android: verify desktop + Android/mobile tools
doctor-android:
	scripts/doctor.sh --android

## setup: install missing desktop dev tools (idempotent)
setup:
	scripts/setup.sh

## setup-android: install desktop + Android SDK + gomobile (idempotent)
setup-android:
	scripts/setup.sh --android

# ── Run ──────────────────────────────────────────────────────────────────────

run:
	scripts/run.sh

# ── Format ───────────────────────────────────────────────────────────────────

fmt:
	scripts/fmt.sh

validate-fmt:
	scripts/validate-fmt.sh

# ── Builds ───────────────────────────────────────────────────────────────────

build-windows:
	scripts/build-windows.sh

build-linux:
	scripts/build-linux.sh

build-darwin:
	scripts/build-darwin.sh

# ── Code generation ──────────────────────────────────────────────────────────

generate:
	wails generate module
	@echo "Bindings regenerated. Commit frontend/wailsjs/ if app.go changed."

## dev-setup: install Android dev environment (idempotent, macOS/Linux only)
dev-setup:
	scripts/setup-android-dev.sh

# ── Mobile builds ────────────────────────────────────────────────────────────

## gomobile-android: build collector.aar for Android and place it in apps/mobile/android/app/libs/
## Requires: gomobile, ANDROID_HOME or ANDROID_SDK_ROOT (run `make setup-android` first)
gomobile-android:
	scripts/gomobile-android.sh

# ── Help ─────────────────────────────────────────────────────────────────────

help:
	@echo ""
	@echo "  make doctor          Check dev environment (desktop)"
	@echo "  make doctor-android  Check dev environment (desktop + Android)"
	@echo "  make setup           Install missing desktop tools"
	@echo "  make setup-android   Install missing desktop + Android tools"
	@echo "  make dev-setup       Install Android dev environment"
	@echo "  make check           Run quality gate (lint, typecheck, build)"
	@echo "  make run             Run app in dev mode"
	@echo "  make fmt             Format Go + frontend code"
	@echo "  make generate        Regenerate Wails bindings"
	@echo "  make gomobile-android Build collector.aar for Android"
	@echo ""
	@echo "  scripts/run.command  macOS Finder-launchable alias for run.sh (double-click to run app)"
	@echo ""
