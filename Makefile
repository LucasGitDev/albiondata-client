check:
	scripts/check.sh

run:
	scripts/run.sh

fmt:
	scripts/fmt.sh

validate-fmt:
	scripts/validate-fmt.sh

build-windows:
	scripts/build-windows.sh

build-linux:
	scripts/build-linux.sh

build-darwin:
	scripts/build-darwin.sh

generate:
	wails generate module
	@echo "Bindings regenerated. Commit frontend/wailsjs/ if app.go changed."
