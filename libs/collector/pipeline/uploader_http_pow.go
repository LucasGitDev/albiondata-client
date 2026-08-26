package pipeline

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io/ioutil"
	"net/http"
	"net/url"
	"runtime"
	"strconv"
	"strings"
	"time"

	"log"
)

const (
	powMaxRetries  = 3
	powRetryBaseMs = 1000 // exponential: 1s, 2s, 4s
)

type httpUploaderPow struct {
	baseURL   string
	transport *http.Transport
}

type Pow struct {
	Key    string `json:"key"`
	Wanted string `json:"wanted"`
}

// newHTTPUploaderPow creates a new HTTP uploader
func newHTTPUploaderPow(url string) uploader {

	if !ConfigGlobal.NoCPULimit {
		// Limit to 25% of available cpu cores
		procs := runtime.NumCPU() / 4
		if procs < 1 {
			procs = 1
		}
		runtime.GOMAXPROCS(procs)
	}

	url = strings.ReplaceAll(url, "https+pow", "https")
	url = strings.ReplaceAll(url, "http+pow", "http")

	return &httpUploaderPow{
		baseURL:   url,
		transport: &http.Transport{},
	}
}

func (u *httpUploaderPow) getPow(target interface{}) error {
	log.Printf("GETTING POW")
	fullURL := u.baseURL + "/pow"

	client := &http.Client{}
	req, _ := http.NewRequest("GET", fullURL, nil)
	req.Header.Add("User-Agent", fmt.Sprintf("albiondata-client/%v", ConfigGlobal.Version))
	resp, err := client.Do(req)

	if err != nil {
		return fmt.Errorf("pow GET failed: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != 200 {
		return fmt.Errorf("pow GET bad status: %v", resp.StatusCode)
	}

	if err := json.NewDecoder(resp.Body).Decode(target); err != nil {
		return fmt.Errorf("pow GET decode failed: %w", err)
	}
	return nil
}

// Proves to the server that a pow was solved by submitting
// the pow's key, the solution and a nats msg as a POST request
// the topic becomes part of the URL
func (u *httpUploaderPow) uploadWithPow(pow Pow, solution string, natsmsg []byte, topic string, serverid int, identifier string) error {
	fullURL := u.baseURL + "/pow/" + topic

	client := &http.Client{}
	data := url.Values{
		"key":        {pow.Key},
		"solution":   {solution},
		"serverid":   {strconv.Itoa(serverid)},
		"natsmsg":    {string(natsmsg)},
		"identifier": {string(identifier)},
	}
	req, _ := http.NewRequest("POST", fullURL, strings.NewReader(data.Encode()))
	req.Header.Add("User-Agent", fmt.Sprintf("albiondata-client/%v", ConfigGlobal.Version))
	resp, err := client.Do(req)

	if err != nil {
		return fmt.Errorf("pow POST failed: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != 200 {
		body, _ := ioutil.ReadAll(resp.Body)
		return fmt.Errorf("pow POST bad status %v: %s", resp.StatusCode, string(body))
	}

	log.Printf("Successfully sent ingest request to %v", u.baseURL)
	return nil
}

// Generates a random hex string e.g.: faa2743d9181dca5
func randomHex(n int) string {
    b := make([]byte, n)
    rand.Read(b)
    dst := make([]byte, n*2)
    hex.Encode(dst, b)
    return string(dst)
}

// Converts a string to bits e.g.: 0110011...
func toBinaryBytes(s string) string {
	var buffer bytes.Buffer
	for i := 0; i < len(s); i++ {
		fmt.Fprintf(&buffer, "%08b", s[i])
	}
	return buffer.String()
}

// Solves a pow looping through possible solutions
// until a correct one is found
// returns the solution
func solvePow(pow Pow) string {
	wantedLen := len(pow.Wanted)
	var hexBuf [64]byte
	var binBuf [512]byte

	prefix := "aod^"
	sep := "^"
	for {
		randhex := randomHex(16)
		challenge := prefix + randhex + sep + pow.Key
		hash := sha256.Sum256([]byte(challenge))
		hex.Encode(hexBuf[:], hash[:])

		idx := 0
		for i := 0; i < 64; i++ {
			b := hexBuf[i]
			for j := 7; j >= 0; j-- {
				if (b>>j)&1 == 1 {
					binBuf[idx] = '1'
				} else {
					binBuf[idx] = '0'
				}
				idx++
				if idx >= wantedLen {
					break
				}
			}
			if idx >= wantedLen {
				break
			}
		}
		if string(binBuf[:wantedLen]) == pow.Wanted {
			return randhex
		}
	}
}

func (u *httpUploaderPow) sendToIngest(body []byte, topic string, state *albionState, identifier string) {
	var lastErr error
	for attempt := 1; attempt <= powMaxRetries; attempt++ {
		pow := Pow{}
		if err := u.getPow(&pow); err != nil {
			lastErr = err
			log.Printf("POW: attempt %d/%d — getPow failed: %v", attempt, powMaxRetries, err)
		} else {
			solution := solvePow(pow)
			if err := u.uploadWithPow(pow, solution, body, topic, state.AODataServerID, identifier); err != nil {
				lastErr = err
				log.Printf("POW: attempt %d/%d — upload failed: %v", attempt, powMaxRetries, err)
			} else {
				return // success
			}
		}
		if attempt < powMaxRetries {
			backoff := time.Duration(powRetryBaseMs<<uint(attempt-1)) * time.Millisecond
			time.Sleep(backoff)
		}
	}
	log.Printf("POW: all %d attempts failed, last error: %v", powMaxRetries, lastErr)
}
