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
	"net"
	"sync"

	"github.com/ao-data/albiondata-collector/pipeline"
	"log"
)

// albionUDPPort is the Photon server port used by Albion Online.
const albionUDPPort = 5056

// packetChannelSize is the buffer depth for the raw-packet relay channel.
// Sized to absorb a burst of packets without blocking the VPN read loop.
const packetChannelSize = 256

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
	packetCh  chan []byte
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

// Start begins packet capture. The VPN service must call ProcessPacket with each
// raw IP packet read from the TUN file descriptor.
// Returns an error if capture is already running or configuration is invalid.
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
	m.packetCh = make(chan []byte, packetChannelSize)
	m.running = true
	m.lastError = ""

	ingestURL := m.ingestURL
	authToken := m.authToken
	packetCh := m.packetCh

	go func() {
		err := runCapture(ctx, ingestURL, authToken, packetCh)
		m.mu.Lock()
		m.running = false
		if err != nil && err != context.Canceled {
			m.lastError = err.Error()
		}
		m.mu.Unlock()
	}()

	return nil
}

// ProcessPacket delivers a raw IP packet (as read from the TUN fd) to the Go
// collector for Photon parsing and upload. The data is copied internally so the
// caller's buffer may be reused immediately after this call returns.
//
// Returns an error if the collector is not running or the internal buffer is full.
// A full-buffer error is non-fatal; the packet is dropped and capture continues.
func (m *MobileCollector) ProcessPacket(data []byte) error {
	m.mu.Lock()
	ch := m.packetCh
	running := m.running
	m.mu.Unlock()

	if !running || ch == nil {
		return fmt.Errorf("collector: not running")
	}

	// Copy so the caller can reuse its buffer.
	pkt := make([]byte, len(data))
	copy(pkt, data)

	select {
	case ch <- pkt:
		return nil
	default:
		// Buffer full — drop packet rather than block the VPN read loop.
		log.Println("collector: packet channel full, dropping packet")
		return nil
	}
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
// It creates a pipeline.Handler, configures it, then relays packets from packetCh
// to the Photon parser until ctx is cancelled.
func runCapture(ctx context.Context, ingestURL, authToken string, packetCh <-chan []byte) error {
	// Configure the pipeline's global config for this session.
	pipeline.ConfigGlobal.PublicIngestBaseUrls = ingestURL
	if authToken != "" {
		pipeline.ConfigGlobal.PrivateIngestBaseUrls = ingestURL
		pipeline.PrivateAuthToken = authToken
	} else {
		pipeline.ConfigGlobal.PrivateIngestBaseUrls = ""
		pipeline.PrivateAuthToken = ""
	}
	pipeline.ConfigGlobal.DisableUpload = false
	pipeline.ConfigGlobal.Debug = true // log only; no pop-ups on mobile

	h := pipeline.NewHandler()
	h.Start()
	defer h.Stop()

	log.Printf("collector/mobile: capture started, ingest=%s", ingestURL)

	for {
		select {
		case <-ctx.Done():
			log.Println("collector/mobile: capture stopped")
			return ctx.Err()
		case pkt, ok := <-packetCh:
			if !ok {
				return nil
			}
			srcIP, payload, err := extractUDPPayload(pkt)
			if err != nil {
				// Not an Albion packet — silently skip.
				continue
			}
			h.SetGameServerIP(srcIP)
			h.ReceivePayload(payload)
		}
	}
}

// extractUDPPayload parses a raw IPv4 packet and returns the source IP string
// and UDP payload bytes when the destination port matches albionUDPPort.
// Returns an error for any non-matching or malformed packet.
func extractUDPPayload(pkt []byte) (srcIP string, payload []byte, err error) {
	// Minimum IPv4 header is 20 bytes; need at least that plus 8-byte UDP header.
	if len(pkt) < 28 {
		return "", nil, fmt.Errorf("packet too short")
	}

	version := pkt[0] >> 4
	if version != 4 {
		return "", nil, fmt.Errorf("not IPv4")
	}

	ihl := int(pkt[0]&0x0f) * 4
	if ihl < 20 || len(pkt) < ihl+8 {
		return "", nil, fmt.Errorf("malformed IPv4 header")
	}

	protocol := pkt[9]
	if protocol != 17 { // UDP
		return "", nil, fmt.Errorf("not UDP")
	}

	src := net.IP(pkt[12:16])
	udpHeader := pkt[ihl:]

	dstPort := binary.BigEndian.Uint16(udpHeader[2:4])
	if dstPort != albionUDPPort {
		srcPort := binary.BigEndian.Uint16(udpHeader[0:2])
		if srcPort != albionUDPPort {
			return "", nil, fmt.Errorf("not Albion port")
		}
	}

	udpLen := int(binary.BigEndian.Uint16(udpHeader[4:6]))
	if udpLen < 8 || ihl+udpLen > len(pkt) {
		return "", nil, fmt.Errorf("malformed UDP header")
	}

	return src.String(), udpHeader[8 : udpLen], nil
}
