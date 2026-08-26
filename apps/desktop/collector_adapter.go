//go:build !cli

package main

import (
	"github.com/ao-data/albiondata-client/client"
	"github.com/ao-data/albiondata-collector"
)

// newCollector builds a collector.Collector backed by the existing client package.
// The RunFunc applies the Config to client.ConfigGlobal and PrivateAuthToken, then
// delegates to client.NewClient.Run — preserving all existing desktop capture behavior.
func newCollector(appVersion string) *collector.Collector {
	return collector.New(func(cfg collector.Config) error {
		if cfg.PublicIngestURL != "" {
			client.ConfigGlobal.PublicIngestBaseUrls = cfg.PublicIngestURL
		}
		if cfg.PrivateIngestURL != "" {
			client.ConfigGlobal.PrivateIngestBaseUrls = cfg.PrivateIngestURL
		}
		client.PrivateAuthToken = cfg.AuthToken
		return client.NewClient(appVersion).Run()
	})
}
