// Package collector provides a platform-agnostic API for Albion Online data collection.
// The capture implementation is injected via RunFunc, allowing desktop (libpcap) and
// mobile (VPN tunnel) platforms to share this common configuration and lifecycle layer.
package collector

import (
	"fmt"
	"sync"
)

// Config holds runtime configuration passed to the capture implementation.
type Config struct {
	// PublicIngestURL is the base URL for anonymous market-data upload.
	PublicIngestURL string
	// PrivateIngestURL is the base URL for authenticated private-server upload.
	PrivateIngestURL string
	// AuthToken is the bearer token used for private-server upload. Empty for public mode.
	AuthToken string
}

// RunFunc is the platform-specific packet-capture implementation.
// It receives the current Config at start time and blocks until capture ends.
// Returning a non-nil error signals that capture failed.
//
// Desktop (Windows/macOS/Linux) uses libpcap/gopacket.
// Mobile uses a VPN-service tunnel.
type RunFunc func(cfg Config) error

// Collector manages the lifecycle and configuration of a data-collection session.
// Safe for concurrent use.
type Collector struct {
	mu      sync.Mutex
	cfg     Config
	runFn   RunFunc
	running bool
}

// New creates a Collector using fn as the capture implementation.
func New(fn RunFunc) *Collector {
	return &Collector{runFn: fn}
}

// SetIngestURL configures the public ingest endpoint.
// May be called before or after Start; the new value takes effect on the next Start.
func (c *Collector) SetIngestURL(url string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.cfg.PublicIngestURL = url
}

// SetPrivateIngestURL configures the private ingest endpoint.
func (c *Collector) SetPrivateIngestURL(url string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.cfg.PrivateIngestURL = url
}

// SetAuthToken sets the bearer token for private-server upload.
// Pass an empty string to switch to public (unauthenticated) mode.
func (c *Collector) SetAuthToken(token string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.cfg.AuthToken = token
}

// Config returns a snapshot of the current configuration.
func (c *Collector) Config() Config {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.cfg
}

// Running reports whether capture is active.
func (c *Collector) Running() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.running
}

// Start begins packet capture. Returns an error if capture is already running.
// The call is non-blocking; use the returned error channel or Running() to track state.
//
// fn is called in a new goroutine with a snapshot of the current Config.
// When fn returns, Running() becomes false.
func (c *Collector) Start() (<-chan error, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.running {
		return nil, fmt.Errorf("collector: already running")
	}
	c.running = true

	cfg := c.cfg
	errCh := make(chan error, 1)
	go func() {
		defer func() {
			c.mu.Lock()
			c.running = false
			c.mu.Unlock()
		}()
		errCh <- c.runFn(cfg)
		close(errCh)
	}()
	return errCh, nil
}

// Stop is a no-op placeholder. The underlying RunFunc controls its own termination;
// Stop exists so callers can call it without checking whether a mechanism exists.
// Concrete implementations may expose a context-cancellation path through RunFunc.
func (c *Collector) Stop() {
	// No-op: the current desktop RunFunc (client.Run) does not expose a stop channel.
	// Mobile implementations may implement graceful shutdown via context cancellation.
}
