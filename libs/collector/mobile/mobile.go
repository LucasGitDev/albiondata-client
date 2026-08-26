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
	"encoding/binary"
	"fmt"
	"sync"

	"github.com/ao-data/albiondata-collector/pipeline"
)

const (
	// albionUDPPort is the well-known Albion Online game server UDP port.
	albionUDPPort = 5056
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
	handler   *pipeline.Handler
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
// Returns an error if capture is already running or configuration is invalid.
// On success, returns nil.
//
// Capture runs in the background; call Stop to terminate it.
// Feed raw IP packets via FeedPacket.
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
		err := m.runCapture(ctx, ingestURL, authToken)
		m.mu.Lock()
		m.running = false
		m.handler = nil
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

// FeedPacket delivers a raw IPv4 packet read from the VPN TUN file descriptor.
// The Android VpnService reads each IP packet and calls this method. Only UDP
// packets destined for albionUDPPort are forwarded to the Photon parser.
//
// Safe to call from any goroutine, including the packet-capture thread.
func (m *MobileCollector) FeedPacket(data []byte) {
	m.mu.Lock()
	h := m.handler
	m.mu.Unlock()
	if h == nil {
		return
	}

	payload, srcIP := extractUDPPayload(data)
	if payload == nil {
		return
	}
	if srcIP != "" {
		h.SetGameServerIP(srcIP)
	}
	h.ReceivePayload(payload)
}

// runCapture initialises the pipeline and blocks until ctx is cancelled.
func (m *MobileCollector) runCapture(ctx context.Context, ingestURL, authToken string) error {
	pipeline.ConfigGlobal.PublicIngestBaseUrls = ingestURL
	pipeline.ConfigGlobal.Version = "mobile"
	if authToken != "" {
		pipeline.ConfigGlobal.PrivateIngestBaseUrls = ingestURL
		pipeline.PrivateAuthToken = authToken
	} else {
		pipeline.ConfigGlobal.PrivateIngestBaseUrls = ""
		pipeline.PrivateAuthToken = ""
	}

	h := pipeline.NewHandler()
	h.Start()

	m.mu.Lock()
	m.handler = h
	m.mu.Unlock()

	<-ctx.Done()

	h.Stop()
	return ctx.Err()
}

// extractUDPPayload parses a raw IPv4 packet and returns the UDP payload and
// source IP string if the packet is a UDP datagram on albionUDPPort.
// Returns nil, "" for non-matching or malformed packets.
func extractUDPPayload(packet []byte) (payload []byte, srcIP string) {
	// Minimum IPv4 header is 20 bytes.
	if len(packet) < 20 {
		return nil, ""
	}

	// Version/IHL byte: upper nibble must be 4 (IPv4).
	version := packet[0] >> 4
	if version != 4 {
		return nil, ""
	}

	// Protocol: 17 = UDP.
	if packet[9] != 17 {
		return nil, ""
	}

	// IP header length in 32-bit words.
	ihl := int(packet[0]&0x0f) * 4
	if ihl < 20 || len(packet) < ihl+8 {
		return nil, ""
	}

	// Source IP (bytes 12–15).
	src := fmt.Sprintf("%d.%d.%d.%d", packet[12], packet[13], packet[14], packet[15])

	// UDP header starts after IP header.
	udp := packet[ihl:]
	if len(udp) < 8 {
		return nil, ""
	}

	// UDP source port (bytes 0–1) and destination port (bytes 2–3).
	srcPort := binary.BigEndian.Uint16(udp[0:2])
	dstPort := binary.BigEndian.Uint16(udp[2:4])
	if srcPort != albionUDPPort && dstPort != albionUDPPort {
		return nil, ""
	}

	udpPayloadLen := binary.BigEndian.Uint16(udp[4:6])
	if udpPayloadLen < 8 || int(udpPayloadLen) > len(udp) {
		return nil, ""
	}

	return udp[8:udpPayloadLen], src
}
