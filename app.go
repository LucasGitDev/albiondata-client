package main

import (
	"context"
	"fmt"
	"sync"

	"github.com/ao-data/albiondata-client/client"
	alog "github.com/ao-data/albiondata-client/log"
	"github.com/wailsapp/wails/v2/pkg/runtime"
)

// App is the Wails application backend. All exported methods become frontend bindings.
type App struct {
	ctx          context.Context
	captureMu    sync.Mutex
	captureState string // "stopped" | "starting" | "running" | "error"
	configOnce   sync.Once
}

func NewApp() *App {
	return &App{captureState: "stopped"}
}

func (a *App) startup(ctx context.Context) {
	a.ctx = ctx
	alog.AddHook(alog.NewWailsHook(ctx, nil))
	runtime.LogInfo(ctx, "Albion Data Client started")
}

func (a *App) shutdown(_ context.Context) {}

// initConfig calls ConfigGlobal.SetupFlags() exactly once.
// SetupFlags registers flag.* vars and calls flag.Parse() — calling it twice panics.
// After calling it we override fields the UI controls so CLI defaults don't win.
func (a *App) initConfig() {
	a.configOnce.Do(func() {
		client.ConfigGlobal.SetupFlags()
		// Wails build never uses the WS server; force it off regardless of config.yaml.
		client.ConfigGlobal.EnableWebsockets = false
	})
}

// StartCapture starts packet capture in the given mode ("public" or "private").
func (a *App) StartCapture(mode string) error {
	if mode != "public" && mode != "private" {
		return fmt.Errorf("invalid mode %q: must be \"public\" or \"private\"", mode)
	}

	a.captureMu.Lock()
	state := a.captureState
	a.captureMu.Unlock()

	if state == "running" || state == "starting" {
		return fmt.Errorf("capture already %s", state)
	}

	a.initConfig()

	switch mode {
	case "public":
		// PublicIngestBaseUrls default set by SetupFlags: "https+pow://albion-online-data.com"
		// Don't override — use whatever SetupFlags defaulted or the user configured.
	case "private":
		// TASK-9 will inject the real auth token and endpoint here.
		// For MVP: use placeholder URL; upload will fail gracefully (no token).
		if client.ConfigGlobal.PrivateIngestBaseUrls == "" {
			client.ConfigGlobal.PrivateIngestBaseUrls = "https://albion-online-data.com"
		}
	}

	a.emitCaptureStatus("starting")

	go func() {
		err := client.NewClient(version).Run()
		if err != nil {
			alog.Errorf("Capture error: %v", err)
			a.emitCaptureStatus("error")
			return
		}
		// Run() returns only when the watcher exits normally.
		a.emitCaptureStatus("stopped")
	}()

	a.emitCaptureStatus("running")
	return nil
}

// StopCapture signals capture to stop.
// NOTE: client.Run() launches an albionProcessWatcher whose quit channel is not
// exposed outside the client package. As a result, StopCapture() cannot cleanly
// terminate the capture goroutine without modifying client/. For now it logs a
// warning and marks state stopped in the UI; the goroutine continues running until
// the app is closed. Adding a proper stop mechanism to client/ is a future task.
func (a *App) StopCapture() {
	alog.Warn("StopCapture: no stop mechanism in client.Run(); goroutine continues until app exit")
	a.emitCaptureStatus("stopped")
}

// CaptureStatus returns the current capture state: "stopped", "starting", "running", or "error".
func (a *App) CaptureStatus() string {
	a.captureMu.Lock()
	defer a.captureMu.Unlock()
	return a.captureState
}

// emitCaptureStatus sets captureState and emits a "capture:status" Wails event.
func (a *App) emitCaptureStatus(state string) {
	a.captureMu.Lock()
	a.captureState = state
	a.captureMu.Unlock()
	if a.ctx != nil {
		runtime.EventsEmit(a.ctx, "capture:status", state)
	}
}

// NotifyUpdateAvailable emits a Wails event when a new version is available.
// Auto-update via syscall.Exec is disabled in the Wails build (that logic lives in
// albiondata-client.go which is gated //go:build cli). Updates are user-manual.
func (a *App) NotifyUpdateAvailable(ver string) {
	if a.ctx == nil {
		return
	}
	runtime.EventsEmit(a.ctx, "update:available", ver)
}
