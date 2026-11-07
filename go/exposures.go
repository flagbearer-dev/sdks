package flagbearer

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"
)

const (
	exposureWindow  = 30 * time.Second
	maxRecords      = 1000
	batchSize       = 100
	maxCheckCount   = 1_000_000_000
	maxUserKeyChars = 256
	maxEmailChars   = 254
)

type exposureUser struct {
	Key   string `json:"key,omitempty"`
	Email string `json:"email,omitempty"`
}

type exposureEvent struct {
	FlagKey              string `json:"flagKey"`
	Result               bool   `json:"result"`
	Reason               reason `json:"reason"`
	ConfigurationVersion *int64 `json:"configurationVersion"`
	Bucket               string `json:"bucket"`
	CheckCount           int    `json:"checkCount"`
	FirstCheckedAt       string `json:"firstCheckedAt"`
	LastCheckedAt        string `json:"lastCheckedAt"`
}

// exposureBatch is one upload, fixed when sealed, so a retry sends exactly the
// same body and the server can recognize it by its batch ID.
type exposureBatch struct {
	id     string
	user   exposureUser
	events []*exposureEvent
}

type exposureKey struct {
	flag    string
	user    exposureUser
	result  bool
	reason  reason
	version int64 // -1 before the first ruleset
	bucket  string
}

type activeExposure struct {
	user  exposureUser
	event *exposureEvent
}

type exposureReporter struct {
	client     *http.Client
	url        string
	key        string
	appVersion string
	timeout    time.Duration
	reporterID string

	sendMu          sync.Mutex // one upload at a time; Flush waits for the one in progress
	mu              sync.Mutex
	active          map[exposureKey]*activeExposure
	order           []exposureKey // active keys in the order first seen
	sealed          []*exposureBatch
	sealedEvents    int
	dropped         int
	reportedDropped int
	retryAt         time.Time
	failures        int
	closed          bool
	stop            chan struct{}
}

func newExposureReporter(client *http.Client, url, key, appVersion string, t timings) *exposureReporter {
	if jsLength(appVersion) > 100 {
		appVersion = string([]rune(appVersion)[:100])
	}
	r := &exposureReporter{
		client: client, url: url, key: key, appVersion: appVersion,
		timeout: t.exposureTimeout, reporterID: newUUID(),
		active: map[exposureKey]*activeExposure{}, stop: make(chan struct{}),
	}
	go r.run(t.exposureFlushInterval)
	return r
}

func isoMillis(at time.Time) string {
	return at.UTC().Format("2006-01-02T15:04:05.000Z")
}

func (r *exposureReporter) record(flag string, user User, details evaluation) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return
	}
	if flag == "" || jsLength(flag) > 256 || jsLength(user.Key) > maxUserKeyChars {
		r.dropped++
		return
	}
	now := time.Now()
	bucket := isoMillis(now.Truncate(exposureWindow))
	email := ""
	if jsLength(user.Email) <= maxEmailChars {
		email = strings.ToLower(strings.TrimSpace(user.Email))
	}
	who := exposureUser{Key: user.Key, Email: email}
	version := int64(-1)
	if details.version != nil {
		version = *details.version
	}
	key := exposureKey{flag, who, details.value, details.reason, version, bucket}
	if existing, ok := r.active[key]; ok {
		if existing.event.CheckCount < maxCheckCount {
			existing.event.CheckCount++
			existing.event.LastCheckedAt = isoMillis(now)
		} else {
			r.dropped++
		}
		return
	}
	if len(r.active)+r.sealedEvents >= maxRecords {
		r.dropped++
		return
	}
	r.active[key] = &activeExposure{who, &exposureEvent{
		FlagKey: flag, Result: details.value, Reason: details.reason,
		ConfigurationVersion: details.version, Bucket: bucket, CheckCount: 1,
		FirstCheckedAt: isoMillis(now), LastCheckedAt: isoMillis(now),
	}}
	r.order = append(r.order, key)
}

func (r *exposureReporter) run(interval time.Duration) {
	for {
		timer := time.NewTimer(jitter(interval, 0.1))
		select {
		case <-r.stop:
			timer.Stop()
			return
		case <-timer.C:
		}
		r.mu.Lock()
		due := !time.Now().Before(r.retryAt)
		r.mu.Unlock()
		if due {
			_ = r.flush(context.Background())
		}
	}
}

// flush uploads every pending check, returning the first failure.
func (r *exposureReporter) flush(ctx context.Context) error {
	r.sendMu.Lock()
	defer r.sendMu.Unlock()
	err := r.send(ctx)
	if err != nil {
		r.mu.Lock()
		r.failures++
		delay := min(60*time.Second, time.Second<<min(r.failures, 6))
		if at := time.Now().Add(delay); at.After(r.retryAt) {
			r.retryAt = at
		}
		r.mu.Unlock()
	}
	return err
}

func (r *exposureReporter) close(ctx context.Context) error {
	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		return nil
	}
	r.closed = true
	close(r.stop)
	r.mu.Unlock()
	return r.flush(ctx)
}

// seal moves active checks into batches. A batch never mixes users. Expects r.mu held.
func (r *exposureReporter) seal() {
	open := map[exposureUser]*exposureBatch{}
	for _, key := range r.order {
		item := r.active[key]
		batch := open[item.user]
		if batch == nil || len(batch.events) >= batchSize {
			batch = &exposureBatch{id: newUUID(), user: item.user}
			open[item.user] = batch
			r.sealed = append(r.sealed, batch)
		}
		batch.events = append(batch.events, item.event)
		r.sealedEvents++
	}
	r.active = map[exposureKey]*activeExposure{}
	r.order = nil
}

func (r *exposureReporter) settle(batch *exposureBatch) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if len(r.sealed) > 0 && r.sealed[0] == batch {
		r.sealed = r.sealed[1:]
		r.sealedEvents -= len(batch.events)
	}
}

func (r *exposureReporter) send(ctx context.Context) error {
	r.mu.Lock()
	r.seal()
	r.mu.Unlock()
	for {
		r.mu.Lock()
		if len(r.sealed) == 0 && r.dropped <= r.reportedDropped {
			r.mu.Unlock()
			return nil
		}
		// Only lost checks to report: an empty batch carries the count.
		batch := &exposureBatch{id: newUUID()}
		if len(r.sealed) > 0 {
			batch = r.sealed[0]
		}
		dropped := r.dropped
		r.mu.Unlock()

		checks := 0
		for _, event := range batch.events {
			checks += event.CheckCount
		}
		events := batch.events
		if events == nil {
			events = []*exposureEvent{}
		}
		body := map[string]any{
			"batchId":       batch.id,
			"reporterId":    r.reporterID,
			"user":          batch.user,
			"events":        events,
			"droppedChecks": dropped,
			"sdk":           "go",
			"sdkVersion":    Version,
		}
		if r.appVersion != "" {
			body["appVersion"] = r.appVersion
		}
		payload, err := json.Marshal(body)
		if err != nil {
			return err
		}
		requestCtx, cancel := context.WithTimeout(ctx, r.timeout)
		response, data, err := send(requestCtx, r.client, http.MethodPost, r.url, map[string]string{
			"Authorization": "Bearer " + r.key,
			"Content-Type":  "application/json",
		}, bytes.NewReader(payload))
		cancel()
		if err != nil {
			return err
		}
		status := response.StatusCode
		if status < 200 || status >= 300 {
			refused := status >= 400 && status < 500 && status != 429
			r.mu.Lock()
			if wait := retryAfter(response.Header.Get("Retry-After"), time.Now()); wait > 0 {
				if at := time.Now().Add(min(wait, time.Hour)); at.After(r.retryAt) {
					r.retryAt = at
				}
			}
			if refused {
				// The server won't take this batch, so retrying can't help.
				r.dropped += checks
				r.retryAt = time.Now().Add(time.Minute)
			}
			r.mu.Unlock()
			if refused {
				r.settle(batch)
			}
			return fmt.Errorf("exposure upload failed (%d)", status)
		}
		rejected, err := readReceipt(data, checks, len(batch.events))
		if err != nil {
			return err
		}
		r.mu.Lock()
		r.dropped += rejected
		r.reportedDropped = max(r.reportedDropped, dropped)
		r.mu.Unlock()
		r.settle(batch)
	}
}

func readReceipt(data []byte, checks, events int) (int, error) {
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.UseNumber()
	var receipt map[string]any
	if decoder.Decode(&receipt) == nil {
		accepted, acceptedOK := integer(receipt["accepted"])
		rejected, rejectedOK := integer(receipt["rejectedChecks"])
		if acceptedOK && rejectedOK && accepted >= 0 && accepted <= int64(events) &&
			rejected >= 0 && rejected <= int64(checks) {
			return int(rejected), nil
		}
	}
	return 0, fmt.Errorf("invalid exposure upload receipt")
}
