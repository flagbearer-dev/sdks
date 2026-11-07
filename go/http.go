package flagbearer

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"io"
	"math"
	mathrand "math/rand/v2"
	"net/http"
	"net/url"
	"strconv"
	"time"
	"unicode/utf16"
)

// refreshError classifies a failed request: retryable ones back off and try
// again; permanent ones stop automatic refreshes until Sync is called.
type refreshError struct {
	message    string
	retryable  bool
	retryAfter time.Duration
}

func (e *refreshError) Error() string { return e.message }

// IsPermanent reports whether err is a failure retrying won't fix, such as a
// revoked key or an invalid ruleset.
func IsPermanent(err error) bool {
	var refresh *refreshError
	return errors.As(err, &refresh) && !refresh.retryable
}

func retryAfter(header string, now time.Time) time.Duration {
	if header == "" {
		return 0
	}
	if seconds, err := strconv.ParseFloat(header, 64); err == nil {
		if seconds < 0 || math.IsNaN(seconds) || math.IsInf(seconds, 0) {
			return 0
		}
		return time.Duration(seconds * float64(time.Second))
	}
	if at, err := http.ParseTime(header); err == nil && at.After(now) {
		return at.Sub(now)
	}
	return 0
}

func checkStatus(response *http.Response) error {
	if response.StatusCode >= 200 && response.StatusCode < 300 {
		return nil
	}
	// A 3xx lands here too: requests never follow redirects.
	status := response.StatusCode
	return &refreshError{
		message:    fmt.Sprintf("Flagbearer request failed: %d", status),
		retryable:  status == 408 || status == 429 || status >= 500,
		retryAfter: retryAfter(response.Header.Get("Retry-After"), time.Now()),
	}
}

func backoff(attempt int, base, cap time.Duration) time.Duration {
	delay := min(cap, base*time.Duration(1<<min(attempt, 5)))
	return min(cap, time.Duration(float64(delay)*(0.8+mathrand.Float64()*0.4)))
}

func jitter(interval time.Duration, spread float64) time.Duration {
	return time.Duration(float64(interval) * (1 - spread + mathrand.Float64()*2*spread))
}

func newHTTPClient() *http.Client {
	return &http.Client{
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
}

// send makes one request and reads the whole body. Network errors are retryable.
func send(ctx context.Context, client *http.Client, method, target string, headers map[string]string, body io.Reader) (*http.Response, []byte, error) {
	request, err := http.NewRequestWithContext(ctx, method, target, body)
	if err != nil {
		return nil, nil, &refreshError{message: err.Error()}
	}
	for name, value := range headers {
		request.Header.Set(name, value)
	}
	response, err := client.Do(request)
	if err != nil {
		return nil, nil, &refreshError{message: "Flagbearer request failed: " + err.Error(), retryable: true}
	}
	defer response.Body.Close()
	data, err := io.ReadAll(io.LimitReader(response.Body, 64<<20))
	if err != nil {
		return nil, nil, &refreshError{message: "Flagbearer request failed: " + err.Error(), retryable: true}
	}
	return response, data, nil
}

func endpoint(base *url.URL, path string) string {
	joined := *base
	joined.Path = base.Path + path
	joined.RawQuery = ""
	return joined.String()
}

// jsLength counts UTF-16 code units, as JavaScript and the server's limits do.
func jsLength(text string) int {
	return len(utf16.Encode([]rune(text)))
}

func newUUID() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

func randomFraction() float64 { return mathrand.Float64() }
