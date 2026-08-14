package client

// PrivateAuthToken is the Bearer token for private ingest endpoints.
// Set by app.go before calling NewClient().Run() in private mode.
var PrivateAuthToken string

// OnAuthExpired is called by the HTTP uploader when a private endpoint returns 401.
// Set by app.go to trigger session refresh or logout flow.
var OnAuthExpired func()
