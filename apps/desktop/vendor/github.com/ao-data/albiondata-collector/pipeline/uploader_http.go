package pipeline

import (
	"bytes"
	"io"
	"io/ioutil"
	"net/http"

	"log"
)

type httpUploader struct {
	baseURL   string
	transport *http.Transport
	token     string
}

// newHTTPUploader creates a new HTTP uploader without auth.
func newHTTPUploader(url string) uploader {
	return &httpUploader{baseURL: url, transport: &http.Transport{}}
}

// newHTTPUploaderWithToken creates an HTTP uploader that sends a Bearer token.
func newHTTPUploaderWithToken(url, token string) uploader {
	return &httpUploader{baseURL: url, transport: &http.Transport{}, token: token}
}

func (u *httpUploader) sendToIngest(body []byte, topic string, state *albionState, identifier string) {
	// not handling sending identifier since the official usage is with http_pow

	client := &http.Client{Transport: u.transport}

	fullURL := u.baseURL + "/" + topic

	req, err := http.NewRequest("POST", fullURL, bytes.NewBuffer([]byte(body)))
	if err != nil {
		log.Printf("Error while create new request: %v", err)
		return
	}

	req.Header.Set("Content-Type", "application/json")
	if u.token != "" {
		req.Header.Set("Authorization", "Bearer "+u.token)
	}

	resp, err := client.Do(req)
	if err != nil {
		log.Printf("Error while sending ingest with data: %v", err)
		return
	}

	if resp.StatusCode == 401 {
		log.Printf("Got 401 Unauthorized from %v — token expired or invalid", u.baseURL)
		if OnAuthExpired != nil {
			OnAuthExpired()
		}
		return
	}

	if resp.StatusCode != 200 {
		log.Printf("Got bad response code: %v", resp.StatusCode)
		return
	}

	// See: https://stackoverflow.com/questions/17948827/reusing-http-connections-in-golang
	io.Copy(ioutil.Discard, resp.Body)

	log.Printf("Successfully sent ingest request to %v", u.baseURL)

	defer resp.Body.Close()
}
