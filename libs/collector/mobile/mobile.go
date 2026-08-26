// Package mobile provides a gomobile-compatible API for Albion Online data collection.
//
// This package wraps github.com/ao-data/albiondata-collector for use with
// gomobile bind. Only types supported by gomobile are used in the exported API:
// strings, primitives, error, and exported structs/interfaces.
//
// Channels and function types are intentionally absent from exported signatures
// so that gomobile can generate valid Java/Kotlin bindings.
//
// Build the Android .aar with:
//
//	make gomobile-android
//
// which invokes:
//
//	gomobile bind -target=android -o apps/mobile/android/app/libs/collector.aar \
//	    github.com/ao-data/albiondata-collector/mobile
package mobile

import (
	"context"
	"fmt"
	"sync"
)

// MobileCollector is a gomobile-safe lifecycle controller for Albion packet capture.
// It wraps the platform-agnostic collector with an API that is fully expressible
// in Java/Kotlin via the gomobile bridge.
//
// Create one instance per process; it is safe for concurrent use.
type MobileCollector struct {
	mu        sync.Mutex
	ingestURL string
	authToken string
	running   bool
	cancel    context.CancelFunc
	lastError string
}

// NewMobileCollector creates a MobileCollector ready to start.
func NewMobileCollector() *MobileCollector {
	return &MobileCollector{}
}

// SetIngestURL sets the public ingest endpoint URL.
// Calls after Start take effect on the next Start.
func (m *MobileCollector) SetIngestURL(url string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.ingestURL = url
}

// SetAuthToken sets the bearer token for private-server upload.
// Pass an empty string to switch to public (unauthenticated) mode.
// Calls after Start take effect on the next Start.
func (m *MobileCollector) SetAuthToken(token string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.authToken = token
}

// Start begins packet capture using the VPN tunnel supplied by the Android VPN service.
// Returns an error string if capture is already running or configuration is invalid.
// On success, returns an empty string.
//
// Capture runs in the background; call Stop to terminate it.
func (m *MobileCollector) Start() error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.running {
		return fmt.Errorf("collector: already running")
	}
	if m.ingestURL == "" {
		return fmt.Errorf("collector: ingest URL not configured")
	}

	ctx, cancel := context.WithCancel(context.Background())
	m.cancel = cancel
	m.running = true
	m.lastError = ""

	ingestURL := m.ingestURL
	authToken := m.authToken

	go func() {
		err := runCapture(ctx, ingestURL, authToken)
		m.mu.Lock()
		m.running = false
		if err != nil && err != context.Canceled {
			m.lastError = err.Error()
		}
		m.mu.Unlock()
	}()

	return nil
}

// Stop terminates packet capture gracefully.
// It is safe to call Stop even if capture is not running.
func (m *MobileCollector) Stop() {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.cancel != nil {
		m.cancel()
		m.cancel = nil
	}
	m.running = false
}

// Running reports whether packet capture is currently active.
func (m *MobileCollector) Running() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.running
}

// LastError returns the last capture error as a string, or an empty string if the
// last capture session ended cleanly.
func (m *MobileCollector) LastError() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.lastError
}

// runCapture is the mobile capture implementation.
// On Android it will receive packets from the VPN service file descriptor.
// This stub blocks until ctx is cancelled.
func runCapture(ctx context.Context, ingestURL, authToken string) error {
	// TODO(TASK-11.9): wire the VPN tunnel file descriptor and connect to the
	// existing Photon parser / uploader pipeline. For now this stub validates
	// the gomobile build pipeline end-to-end.
	_ = ingestURL
	_ = authToken
	<-ctx.Done()
	return ctx.Err()
}
