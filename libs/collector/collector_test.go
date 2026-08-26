package collector_test

import (
	"errors"
	"testing"
	"time"

	"github.com/ao-data/albiondata-collector"
)

func TestCollector_ConfigMethods(t *testing.T) {
	c := collector.New(func(cfg collector.Config) error { return nil })

	c.SetIngestURL("https://public.example.com")
	c.SetPrivateIngestURL("https://private.example.com")
	c.SetAuthToken("tok-abc")

	got := c.Config()
	if got.PublicIngestURL != "https://public.example.com" {
		t.Errorf("PublicIngestURL = %q, want %q", got.PublicIngestURL, "https://public.example.com")
	}
	if got.PrivateIngestURL != "https://private.example.com" {
		t.Errorf("PrivateIngestURL = %q, want %q", got.PrivateIngestURL, "https://private.example.com")
	}
	if got.AuthToken != "tok-abc" {
		t.Errorf("AuthToken = %q, want %q", got.AuthToken, "tok-abc")
	}
}

func TestCollector_StartStop(t *testing.T) {
	done := make(chan struct{})
	c := collector.New(func(cfg collector.Config) error {
		<-done
		return nil
	})

	errCh, err := c.Start()
	if err != nil {
		t.Fatalf("Start() unexpected error: %v", err)
	}

	if !c.Running() {
		t.Error("Running() = false immediately after Start()")
	}

	// Starting again must fail.
	_, err2 := c.Start()
	if err2 == nil {
		t.Error("second Start() should return error but got nil")
	}

	// Stop is a no-op; unblock the RunFunc manually.
	c.Stop()
	close(done)

	select {
	case capErr := <-errCh:
		if capErr != nil {
			t.Errorf("errCh returned unexpected error: %v", capErr)
		}
	case <-time.After(2 * time.Second):
		t.Error("timed out waiting for capture goroutine to exit")
	}

	if c.Running() {
		t.Error("Running() = true after RunFunc returned")
	}
}

func TestCollector_StartPropagatesError(t *testing.T) {
	wantErr := errors.New("pcap: permission denied")
	c := collector.New(func(cfg collector.Config) error {
		return wantErr
	})

	errCh, err := c.Start()
	if err != nil {
		t.Fatalf("Start() unexpected error: %v", err)
	}

	select {
	case got := <-errCh:
		if !errors.Is(got, wantErr) {
			t.Errorf("errCh = %v, want %v", got, wantErr)
		}
	case <-time.After(2 * time.Second):
		t.Error("timed out waiting for error propagation")
	}
}

func TestCollector_ConfigPassedToRunFunc(t *testing.T) {
	var received collector.Config
	c := collector.New(func(cfg collector.Config) error {
		received = cfg
		return nil
	})

	c.SetIngestURL("https://ingest.example.com")
	c.SetAuthToken("my-token")

	errCh, err := c.Start()
	if err != nil {
		t.Fatalf("Start() error: %v", err)
	}
	<-errCh

	if received.PublicIngestURL != "https://ingest.example.com" {
		t.Errorf("RunFunc received PublicIngestURL=%q, want %q", received.PublicIngestURL, "https://ingest.example.com")
	}
	if received.AuthToken != "my-token" {
		t.Errorf("RunFunc received AuthToken=%q, want %q", received.AuthToken, "my-token")
	}
}
