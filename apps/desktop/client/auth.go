package client

// PrivateAuthToken is the Bearer token for private ingest endpoints.
// Set by the desktop app after successful OAuth login.
var PrivateAuthToken string

// OnAuthExpired is called by the HTTP uploader when a private endpoint returns 401.
// Set by the desktop app to handle token refresh / re-login UI.
var OnAuthExpired func()
