package main

import (
	"context"
	"fmt"
	"strings"
	"sync"

	"github.com/ao-data/albiondata-client/auth"
	"github.com/ao-data/albiondata-client/client"
	alog "github.com/ao-data/albiondata-client/log"
	"github.com/ao-data/albiondata-collector"
	"github.com/pkg/browser"
	"github.com/wailsapp/wails/v2/pkg/runtime"
)

// App is the Wails application backend. All exported methods become frontend bindings.
type App struct {
	ctx          context.Context
	captureMu    sync.Mutex
	captureState string // "stopped" | "starting" | "running" | "error"
	configOnce   sync.Once
	sessionMu    sync.Mutex
	session      *auth.Session
	// col is the standalone collector library instance used for capture lifecycle.
	col *collector.Collector
}

func NewApp() *App {
	return &App{captureState: "stopped"}
}

func (a *App) startup(ctx context.Context) {
	a.ctx = ctx
	alog.AddHook(alog.NewWailsHook(ctx, nil))
	// Initialise the standalone collector library backed by the desktop capture implementation.
	a.col = newCollector(version)
	// Restore saved session if present.
	if s, err := auth.LoadSession(); err == nil && s != nil {
		a.session = s
		alog.Infof("Restored session for %s", s.Email)
	}
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
		client.PrivateAuthToken = ""
		client.OnAuthExpired = nil
	case "private":
		if client.ConfigGlobal.PrivateIngestBaseUrls == "" {
			client.ConfigGlobal.PrivateIngestBaseUrls = "https://albion-online-data.com"
		}
		a.sessionMu.Lock()
		sess := a.session
		a.sessionMu.Unlock()
		if sess == nil {
			return fmt.Errorf("not logged in: login required for private capture")
		}
		token, err := auth.GetValidToken(a.ctx, sess)
		if err != nil {
			return fmt.Errorf("token refresh failed: %w", err)
		}
		client.PrivateAuthToken = token
		client.OnAuthExpired = func() {
			alog.Warn("Private ingest returned 401 — clearing session, re-login required")
			a.sessionMu.Lock()
			a.session = nil
			a.sessionMu.Unlock()
			if err := auth.DeleteSession(); err != nil {
				alog.Errorf("Failed to delete session after 401: %v", err)
			}
			if a.ctx != nil {
				runtime.EventsEmit(a.ctx, "auth:expired", nil)
			}
		}
	}

	// Propagate current config to the standalone collector library.
	a.col.SetIngestURL(client.ConfigGlobal.PublicIngestBaseUrls)
	a.col.SetPrivateIngestURL(client.ConfigGlobal.PrivateIngestBaseUrls)
	a.col.SetAuthToken(client.PrivateAuthToken)

	a.emitCaptureStatus("starting")

	errCh, startErr := a.col.Start()
	if startErr != nil {
		// Should not happen since we checked state above, but handle defensively.
		a.emitCaptureStatus("stopped")
		return startErr
	}
	go func() {
		defer func() {
			if r := recover(); r != nil {
				msg := fmt.Sprintf("%v", r)
				alog.Errorf("Capture panic: %v", msg)
				a.emitCaptureError(msg)
				a.emitCaptureStatus("error")
			}
		}()
		a.emitCaptureStatus("running")
		if err := <-errCh; err != nil {
			alog.Errorf("Capture error: %v", err)
			a.emitCaptureError(err.Error())
			a.emitCaptureStatus("error")
			return
		}
		a.emitCaptureStatus("stopped")
	}()

	return nil
}

// StopCapture delegates to the collector library's Stop method.
// The current desktop RunFunc (client.Run) does not expose a stop channel, so this
// is effectively a no-op on desktop. It exists so the frontend binding compiles and
// so mobile implementations (which can stop via context cancellation) work correctly.
func (a *App) StopCapture() {
	a.col.Stop()
	alog.Warn("StopCapture: no stop mechanism exposed by client.Run(); restart app to stop")
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

// emitCaptureError emits a "capture:error" event with an actionable message.
// If the error looks like a BPF/pcap permission denial, it substitutes platform guidance.
func (a *App) emitCaptureError(raw string) {
	if a.ctx == nil {
		return
	}
	msg := raw
	lower := strings.ToLower(raw)
	if (strings.Contains(lower, "bpf") || strings.Contains(lower, "pcap") || strings.Contains(lower, "npcap")) &&
		strings.Contains(lower, "permission") {
		msg = "Packet capture permission denied.\n" +
			"macOS: install ChmodBPF (via Wireshark) or run: sudo chmod 644 /dev/bpf*\n" +
			"Linux: sudo setcap cap_net_raw,cap_net_admin=eip <binary>, or run as root\n" +
			"Windows: install Npcap (https://npcap.com)"
	}
	runtime.EventsEmit(a.ctx, "capture:error", msg)
}

// IsLoggedIn returns true if a session is loaded (token may need refresh).
func (a *App) IsLoggedIn() bool {
	a.sessionMu.Lock()
	defer a.sessionMu.Unlock()
	return a.session != nil
}

// GetUserEmail returns the logged-in user's email, or empty string.
func (a *App) GetUserEmail() string {
	a.sessionMu.Lock()
	defer a.sessionMu.Unlock()
	if a.session == nil {
		return ""
	}
	return a.session.Email
}

// Login starts the Google OAuth2 browser flow. Blocks until complete or cancelled.
func (a *App) Login() error {
	session, err := auth.Login(a.ctx, browser.OpenURL)
	if err != nil {
		return err
	}
	a.sessionMu.Lock()
	a.session = session
	a.sessionMu.Unlock()
	runtime.EventsEmit(a.ctx, "auth:login", session.Email)
	return nil
}

// Logout clears the session from memory and disk.
func (a *App) Logout() error {
	if err := auth.DeleteSession(); err != nil {
		return err
	}
	a.sessionMu.Lock()
	a.session = nil
	a.sessionMu.Unlock()
	runtime.EventsEmit(a.ctx, "auth:logout", nil)
	return nil
}

// SettingsPayload holds user-configurable ingest URL overrides.
type SettingsPayload struct {
	PublicIngestBaseUrls  string `json:"publicIngestBaseUrls"`
	PrivateIngestBaseUrls string `json:"privateIngestBaseUrls"`
}

// GetSettings returns current ingest URL configuration.
func (a *App) GetSettings() SettingsPayload {
	a.initConfig()
	return SettingsPayload{
		PublicIngestBaseUrls:  client.ConfigGlobal.PublicIngestBaseUrls,
		PrivateIngestBaseUrls: client.ConfigGlobal.PrivateIngestBaseUrls,
	}
}

// SaveSettings applies ingest URL overrides in-memory.
// Persistence to config.yaml is not implemented yet — settings reset on app restart.
func (a *App) SaveSettings(s SettingsPayload) error {
	a.initConfig()
	if s.PublicIngestBaseUrls != "" {
		client.ConfigGlobal.PublicIngestBaseUrls = s.PublicIngestBaseUrls
	}
	if s.PrivateIngestBaseUrls != "" {
		client.ConfigGlobal.PrivateIngestBaseUrls = s.PrivateIngestBaseUrls
	}
	return nil
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
