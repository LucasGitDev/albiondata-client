package main

import (
	"context"

	alog "github.com/ao-data/albiondata-client/log"
	"github.com/wailsapp/wails/v2/pkg/runtime"
)

// App is the Wails application backend. All exported methods become frontend bindings.
type App struct {
	ctx context.Context
}

func NewApp() *App {
	return &App{}
}

func (a *App) startup(ctx context.Context) {
	a.ctx = ctx
	alog.AddHook(alog.NewWailsHook(ctx, nil))
	runtime.LogInfo(ctx, "Albion Data Client started")
}

func (a *App) shutdown(_ context.Context) {}

// StartCapture starts packet capture in the given mode ("public" or "private").
// Full implementation in TASK-6.
func (a *App) StartCapture(mode string) error {
	return nil
}

// StopCapture stops a running capture session.
// Full implementation in TASK-6.
func (a *App) StopCapture() {}

// CaptureStatus returns "running", "stopped", or "error".
// Full implementation in TASK-6.
func (a *App) CaptureStatus() string {
	return "stopped"
}

// NotifyUpdateAvailable emits a Wails event when a new version is available.
// Auto-update via syscall.Exec is disabled in the Wails build (that logic lives in
// albiondata-client.go which is gated //go:build cli). Updates are user-manual:
// the user downloads and installs the new binary themselves.
// This stub exists so the UI (TASK-8) can wire an "update available" banner without
// requiring another backend change when update checking is added later.
func (a *App) NotifyUpdateAvailable(version string) {
	if a.ctx == nil {
		return
	}
	runtime.EventsEmit(a.ctx, "update:available", version)
}
