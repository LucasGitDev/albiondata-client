package auth

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google"
)

const (
	scopeEmail   = "https://www.googleapis.com/auth/userinfo.email"
	scopeProfile = "https://www.googleapis.com/auth/userinfo.profile"
)

// clientID and clientSecret are set via ldflags at build time.
// For dev, set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET env vars instead.
var (
	clientID     = ""
	clientSecret = ""
)

// Session holds persisted token data.
type Session struct {
	AccessToken  string    `json:"access_token"`
	RefreshToken string    `json:"refresh_token"`
	Expiry       time.Time `json:"expiry"`
	Email        string    `json:"email"`
}

func sessionPath() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	dir := filepath.Join(home, ".albiondata")
	if err := os.MkdirAll(dir, 0700); err != nil {
		return "", err
	}
	return filepath.Join(dir, "session.json"), nil
}

// LoadSession reads a saved session from disk. Returns nil if not found.
func LoadSession() (*Session, error) {
	path, err := sessionPath()
	if err != nil {
		return nil, err
	}
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var s Session
	if err := json.Unmarshal(data, &s); err != nil {
		return nil, err
	}
	return &s, nil
}

// SaveSession writes session to disk with mode 0600.
func SaveSession(s *Session) error {
	path, err := sessionPath()
	if err != nil {
		return err
	}
	data, err := json.Marshal(s)
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0600)
}

// DeleteSession removes the saved session file.
func DeleteSession() error {
	path, err := sessionPath()
	if err != nil {
		return err
	}
	err = os.Remove(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return err
}

func oauthConfig(redirectURL string) *oauth2.Config {
	id := clientID
	if id == "" {
		id = os.Getenv("GOOGLE_CLIENT_ID")
	}
	secret := clientSecret
	if secret == "" {
		secret = os.Getenv("GOOGLE_CLIENT_SECRET")
	}
	return &oauth2.Config{
		ClientID:     id,
		ClientSecret: secret,
		Scopes:       []string{scopeEmail, scopeProfile},
		Endpoint:     google.Endpoint,
		RedirectURL:  redirectURL,
	}
}

// Login runs the installed-app OAuth2 flow with PKCE:
// opens the system browser, waits for redirect on a local server, exchanges
// the auth code for a token, fetches user email, and persists the session.
// openBrowser is called with the Google consent URL.
func Login(ctx context.Context, openBrowser func(url string) error) (*Session, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, fmt.Errorf("listen: %w", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	redirectURL := fmt.Sprintf("http://127.0.0.1:%d/callback", port)

	cfg := oauthConfig(redirectURL)
	if cfg.ClientID == "" {
		return nil, errors.New("Google OAuth client ID not configured (set GOOGLE_CLIENT_ID or build with -ldflags)")
	}

	verifier := pkceVerifier()
	challenge := pkceChallenge(verifier)

	authURL := cfg.AuthCodeURL("state",
		oauth2.AccessTypeOffline,
		oauth2.SetAuthURLParam("code_challenge", challenge),
		oauth2.SetAuthURLParam("code_challenge_method", "S256"),
	)

	codeCh := make(chan string, 1)
	errCh := make(chan error, 1)

	mux := http.NewServeMux()
	srv := &http.Server{Handler: mux}
	mux.HandleFunc("/callback", func(w http.ResponseWriter, r *http.Request) {
		code := r.URL.Query().Get("code")
		if code == "" {
			e := fmt.Errorf("no code in callback: %s", r.URL.Query().Get("error"))
			errCh <- e
			http.Error(w, "Login failed", http.StatusBadRequest)
			return
		}
		fmt.Fprintln(w, "<html><body><h2>Login successful — you can close this tab.</h2></body></html>")
		codeCh <- code
	})

	go func() {
		if serveErr := srv.Serve(ln); serveErr != nil && !errors.Is(serveErr, http.ErrServerClosed) {
			errCh <- serveErr
		}
	}()
	defer srv.Shutdown(context.Background()) //nolint:errcheck

	if err := openBrowser(authURL); err != nil {
		return nil, fmt.Errorf("open browser: %w", err)
	}

	var code string
	select {
	case code = <-codeCh:
	case err = <-errCh:
		return nil, err
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-time.After(5 * time.Minute):
		return nil, errors.New("login timed out after 5 minutes")
	}

	tok, err := cfg.Exchange(ctx, code,
		oauth2.SetAuthURLParam("code_verifier", verifier),
	)
	if err != nil {
		return nil, fmt.Errorf("token exchange: %w", err)
	}

	client := cfg.Client(ctx, tok)
	resp, err := client.Get("https://www.googleapis.com/oauth2/v2/userinfo")
	if err != nil {
		return nil, fmt.Errorf("userinfo: %w", err)
	}
	defer resp.Body.Close()
	var info struct {
		Email string `json:"email"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&info); err != nil {
		return nil, fmt.Errorf("decode userinfo: %w", err)
	}

	session := &Session{
		AccessToken:  tok.AccessToken,
		RefreshToken: tok.RefreshToken,
		Expiry:       tok.Expiry,
		Email:        info.Email,
	}
	if err := SaveSession(session); err != nil {
		return nil, fmt.Errorf("save session: %w", err)
	}
	return session, nil
}

// RefreshToken exchanges a saved refresh token for a new access token.
func RefreshToken(ctx context.Context, s *Session) (*Session, error) {
	cfg := oauthConfig("")
	if cfg.ClientID == "" {
		return nil, errors.New("Google OAuth client ID not configured")
	}
	oldTok := &oauth2.Token{
		RefreshToken: s.RefreshToken,
		Expiry:       time.Now().Add(-time.Second), // force refresh
	}
	ts := cfg.TokenSource(ctx, oldTok)
	newTok, err := ts.Token()
	if err != nil {
		return nil, fmt.Errorf("refresh: %w", err)
	}
	s.AccessToken = newTok.AccessToken
	s.Expiry = newTok.Expiry
	if newTok.RefreshToken != "" {
		s.RefreshToken = newTok.RefreshToken
	}
	if err := SaveSession(s); err != nil {
		return nil, err
	}
	return s, nil
}

// GetValidToken returns a valid access token, refreshing if within 30s of expiry.
func GetValidToken(ctx context.Context, s *Session) (string, error) {
	if time.Now().Before(s.Expiry.Add(-30 * time.Second)) {
		return s.AccessToken, nil
	}
	if s.RefreshToken == "" {
		return "", errors.New("session expired and no refresh token available")
	}
	refreshed, err := RefreshToken(ctx, s)
	if err != nil {
		return "", err
	}
	return refreshed.AccessToken, nil
}

func pkceVerifier() string {
	b := make([]byte, 32)
	rand.Read(b) //nolint:errcheck
	return base64.RawURLEncoding.EncodeToString(b)
}

func pkceChallenge(verifier string) string {
	h := sha256.Sum256([]byte(verifier))
	return base64.RawURLEncoding.EncodeToString(h[:])
}
