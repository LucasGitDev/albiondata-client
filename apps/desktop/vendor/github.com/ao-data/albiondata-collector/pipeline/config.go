package pipeline

// Config holds the subset of runtime configuration needed by the pipeline.
// Set values on ConfigGlobal before calling Start.
type Config struct {
	// PublicIngestBaseUrls is a comma-separated list of public ingest targets.
	PublicIngestBaseUrls string
	// PrivateIngestBaseUrls is a comma-separated list of private ingest targets.
	PrivateIngestBaseUrls string
	// DisableUpload prevents any data from being sent to ingest.
	DisableUpload bool
	// EnableWebsockets enables the local WebSocket broadcast server.
	EnableWebsockets bool
	// AllowedWSHosts are the allowed WebSocket upgrade origins.
	AllowedWSHosts []string
	// RecordPath, if non-empty, writes raw Photon commands to this file path.
	RecordPath string
	// NoCPULimit skips the GOMAXPROCS throttle used by the POW uploader.
	NoCPULimit bool
	// Debug disables notification pop-ups (location warnings are logged only).
	Debug bool
	// Version is the client version string embedded in outgoing HTTP User-Agent headers.
	Version string

	// Debug operation/event filtering (keyed by operation/event code int).
	DebugOperations             map[int]bool
	DebugOperationsString       string
	DebugEvents                 map[int]bool
	DebugEventsString           string
	DebugIgnoreDecodingErrors   bool
}

// ConfigGlobal is the package-level configuration used by all pipeline functions.
var ConfigGlobal = &Config{}
