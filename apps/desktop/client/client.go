package client

import (
	"os"
	"time"

	"github.com/ao-data/albiondata-collector/pipeline"
	"github.com/ao-data/albiondata-client/log"
)

var version string

// Client struct base
type Client struct {
}

// NewClient return a new Client instance
func NewClient(_version string) *Client {
	version = _version
	return &Client{}
}

// Run starts client settings and run
func (client *Client) Run() error {
	log.Infof("Starting Albion Data Client, version: %s", version)
	log.Info("This is a third-party application and is in no way affiliated with Sandbox Interactive or Albion Online.")
	log.Info("Additional parameters can listed by calling this file with the -h parameter.")

	ConfigGlobal.setupDebugEvents()
	ConfigGlobal.setupDebugOperations()

	// Propagate desktop config into the shared pipeline config.
	syncPipelineConfig()

	if ConfigGlobal.Offline {
		processOffline(ConfigGlobal.OfflinePath)

		// Allow time for any async uploads/processing to complete, then exit.
		time.Sleep(10 * time.Second)
		os.Exit(0)

	} else {
		apw := newAlbionProcessWatcher()
		return apw.run()
	}
	return nil
}

// syncPipelineConfig copies the desktop ConfigGlobal fields into the pipeline
// package's ConfigGlobal so the shared parser/uploader code uses the same values.
func syncPipelineConfig() {
	pipeline.ConfigGlobal.PublicIngestBaseUrls = ConfigGlobal.PublicIngestBaseUrls
	pipeline.ConfigGlobal.PrivateIngestBaseUrls = ConfigGlobal.PrivateIngestBaseUrls
	pipeline.ConfigGlobal.DisableUpload = ConfigGlobal.DisableUpload
	pipeline.ConfigGlobal.EnableWebsockets = ConfigGlobal.EnableWebsockets
	pipeline.ConfigGlobal.AllowedWSHosts = ConfigGlobal.AllowedWSHosts
	pipeline.ConfigGlobal.RecordPath = ConfigGlobal.RecordPath
	pipeline.ConfigGlobal.NoCPULimit = ConfigGlobal.NoCPULimit
	pipeline.ConfigGlobal.Debug = ConfigGlobal.Debug
	pipeline.ConfigGlobal.Version = version
	pipeline.ConfigGlobal.DebugOperations = ConfigGlobal.DebugOperations
	pipeline.ConfigGlobal.DebugOperationsString = ConfigGlobal.DebugOperationsString
	pipeline.ConfigGlobal.DebugEvents = ConfigGlobal.DebugEvents
	pipeline.ConfigGlobal.DebugEventsString = ConfigGlobal.DebugEventsString
	pipeline.ConfigGlobal.DebugIgnoreDecodingErrors = ConfigGlobal.DebugIgnoreDecodingErrors
	pipeline.PrivateAuthToken = PrivateAuthToken
	pipeline.OnAuthExpired = OnAuthExpired
}
