package flagbearer

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// Short enough that retries finish within a test, long enough that nothing
// polls unless a test asks for it.
var fast = timings{
	requestTimeout:         time.Second,
	pollInterval:           time.Hour,
	retryBase:              10 * time.Millisecond,
	maxRetry:               50 * time.Millisecond,
	maxVersionRefreshDelay: 0,
	streamConnectTimeout:   time.Second,
	streamIdleTimeout:      5 * time.Second,
	exposureFlushInterval:  time.Hour,
	exposureTimeout:        time.Second,
}

func withTimings(t timings) Option { return func(o *options) { o.timings = t } }

type rule struct {
	Key            string   `json:"key"`
	Enabled        bool     `json:"enabled"`
	RolloutBps     int      `json:"rolloutBps"`
	OverrideEmails []string `json:"overrideEmails"`
}

func on(key string) rule  { return rule{key, true, 10000, []string{}} }
func off(key string) rule { return rule{key, false, 10000, []string{}} }

// fakeSDK serves the SDK server's three routes. Tests set what it returns and
// read what it got.
type fakeSDK struct {
	mu                sync.Mutex
	version           int
	environmentID     string
	flags             []rule
	rulesetBody       string
	rulesetStatuses   []int
	streamStatus      int
	exposureStatuses  []int
	rulesetRequests   []string
	exposures         []map[string]any
	streamConnections int
	events            chan string
	streamOpened      chan struct{}
	openOnce          sync.Once
	server            *httptest.Server
}

func newFakeSDK(t *testing.T) *fakeSDK {
	fake := &fakeSDK{
		version: 1, environmentID: "env-1", flags: []rule{on("checkout")}, streamStatus: 200,
		events: make(chan string, 16), streamOpened: make(chan struct{}),
	}
	fake.server = httptest.NewServer(http.HandlerFunc(fake.serve))
	t.Cleanup(fake.server.Close)
	return fake
}

func (f *fakeSDK) set(apply func(f *fakeSDK)) {
	f.mu.Lock()
	defer f.mu.Unlock()
	apply(f)
}

func (f *fakeSDK) announce(version int) {
	f.events <- fmt.Sprintf("event: version\ndata: {\"version\":%d}\n\n", version)
}

func (f *fakeSDK) serve(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("Authorization") != "Bearer fbs_test" {
		w.WriteHeader(401)
		return
	}
	switch r.URL.Path {
	case "/v1/ruleset":
		f.mu.Lock()
		f.rulesetRequests = append(f.rulesetRequests, r.Header.Get("If-None-Match"))
		status := 200
		if len(f.rulesetStatuses) > 0 {
			status, f.rulesetStatuses = f.rulesetStatuses[0], f.rulesetStatuses[1:]
		}
		etag := fmt.Sprintf(`"%d"`, f.version)
		body := f.rulesetBody
		if body == "" {
			data, _ := json.Marshal(map[string]any{"environmentId": f.environmentID, "version": f.version, "flags": f.flags})
			body = string(data)
		}
		f.mu.Unlock()
		switch {
		case status != 200:
			w.Header().Set("Retry-After", "0")
			w.WriteHeader(status)
		case r.Header.Get("If-None-Match") == etag:
			w.WriteHeader(304)
		default:
			w.Header().Set("ETag", etag)
			_, _ = w.Write([]byte(body))
		}
	case "/v1/stream":
		f.mu.Lock()
		f.streamConnections++
		status, version := f.streamStatus, f.version
		f.mu.Unlock()
		if status != 200 {
			w.WriteHeader(status)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprintf(w, "retry: 5000\n\nevent: version\ndata: {\"version\":%d}\n\n", version)
		w.(http.Flusher).Flush()
		f.openOnce.Do(func() { close(f.streamOpened) })
		for {
			select {
			case event := <-f.events:
				_, _ = w.Write([]byte(event))
				w.(http.Flusher).Flush()
			case <-r.Context().Done():
				return
			}
		}
	case "/v1/exposures":
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		f.mu.Lock()
		f.exposures = append(f.exposures, body)
		status := 200
		if len(f.exposureStatuses) > 0 {
			status, f.exposureStatuses = f.exposureStatuses[0], f.exposureStatuses[1:]
		}
		f.mu.Unlock()
		if status != 200 {
			w.WriteHeader(status)
			return
		}
		events, _ := body["events"].([]any)
		_ = json.NewEncoder(w).Encode(map[string]int{"accepted": len(events), "rejectedChecks": 0})
	default:
		w.WriteHeader(404)
	}
}

func (f *fakeSDK) uploads() []map[string]any {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]map[string]any(nil), f.exposures...)
}

func newClient(t *testing.T, fake *fakeSDK, opts ...Option) *Client {
	t.Helper()
	client, err := New("fbs_test", append([]Option{WithURL(fake.server.URL), withTimings(fast)}, opts...)...)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = client.Close() })
	return client
}

func mustSync(t *testing.T, client *Client) {
	t.Helper()
	if err := client.Sync(context.Background()); err != nil {
		t.Fatal(err)
	}
}

// waitFor polls a condition the SDK reaches on its own goroutines.
func waitFor(t *testing.T, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for !condition() {
		if time.Now().After(deadline) {
			t.Fatal("condition not met in time")
		}
		time.Sleep(5 * time.Millisecond)
	}
}

var user1 = User{Key: "user-1"}

func TestDefaultsUntilTheFirstRulesetThenLocalRules(t *testing.T) {
	fake := newFakeSDK(t)
	fake.set(func(f *fakeSDK) {
		for range 1000 {
			f.rulesetStatuses = append(f.rulesetStatuses, 503)
		}
	})
	client := newClient(t, fake)
	if !client.IsEnabled("checkout", user1, true) || client.IsEnabled("checkout", user1, false) {
		t.Fatal("expected the default before any rules")
	}
	fake.set(func(f *fakeSDK) { f.rulesetStatuses = nil })
	mustSync(t, client)
	if !client.IsEnabled("checkout", user1, false) {
		t.Fatal("expected the rule after sync")
	}
	if !client.IsEnabled("missing", user1, true) {
		t.Fatal("expected the default for a missing flag")
	}
}

func TestAStreamAnnouncementRefetchesWithTheCurrentVersion(t *testing.T) {
	fake := newFakeSDK(t)
	client := newClient(t, fake)
	mustSync(t, client)
	<-fake.streamOpened

	fake.set(func(f *fakeSDK) { f.flags, f.version = []rule{off("checkout")}, 2 })
	fake.announce(2)
	waitFor(t, func() bool { return !client.IsEnabled("checkout", user1, true) })
	fake.set(func(f *fakeSDK) {
		if !strings.Contains(strings.Join(f.rulesetRequests, ","), `"1"`) {
			t.Error("refetch didn't send If-None-Match")
		}
	})
}

func TestAnUnchangedRulesetKeepsTheRules(t *testing.T) {
	fake := newFakeSDK(t)
	client := newClient(t, fake)
	mustSync(t, client)
	mustSync(t, client)
	fake.set(func(f *fakeSDK) {
		if last := f.rulesetRequests[len(f.rulesetRequests)-1]; last != `"1"` {
			t.Errorf("last If-None-Match = %q", last)
		}
	})
	if !client.IsEnabled("checkout", user1, false) {
		t.Fatal("rules were lost")
	}
}

func TestAnInvalidRulesetFailsAndKeepsTheLastRules(t *testing.T) {
	fake := newFakeSDK(t)
	client := newClient(t, fake)
	mustSync(t, client)

	fake.set(func(f *fakeSDK) {
		f.version = 2
		f.rulesetBody = `{"environmentId":"env-1","version":2,"flags":[{"key":"checkout"}]}`
	})
	if err := client.Sync(context.Background()); !IsPermanent(err) {
		t.Fatalf("got %v, want a permanent error", err)
	}
	if !client.IsEnabled("checkout", user1, false) {
		t.Fatal("rules were lost")
	}

	// An explicit sync tries again after a permanent failure.
	fake.set(func(f *fakeSDK) { f.rulesetBody, f.flags = "", []rule{off("checkout")} })
	mustSync(t, client)
	if client.IsEnabled("checkout", user1, true) {
		t.Fatal("expected the new rules")
	}
}

func TestSyncAfterAPermanentFailureRestartsPolling(t *testing.T) {
	fake := newFakeSDK(t)
	fake.set(func(f *fakeSDK) { f.streamStatus = 401 })
	quick := fast
	quick.pollInterval = 50 * time.Millisecond
	client := newClient(t, fake, withTimings(quick))
	mustSync(t, client)
	fake.set(func(f *fakeSDK) { f.rulesetBody, f.version = "not json", 2 })
	if err := client.Sync(context.Background()); err == nil {
		t.Fatal("expected a failure")
	}

	fake.set(func(f *fakeSDK) { f.rulesetBody = "" })
	mustSync(t, client)
	fake.set(func(f *fakeSDK) { f.flags, f.version = []rule{off("checkout")}, 3 })
	waitFor(t, func() bool { return !client.IsEnabled("checkout", user1, true) })
}

func TestARulesetForAnotherEnvironmentIsRejected(t *testing.T) {
	fake := newFakeSDK(t)
	client := newClient(t, fake)
	mustSync(t, client)
	fake.set(func(f *fakeSDK) { f.environmentID, f.flags, f.version = "env-2", []rule{off("checkout")}, 2 })
	err := client.Sync(context.Background())
	if err == nil || !strings.Contains(err.Error(), "environment changed") {
		t.Fatalf("got %v", err)
	}
	if !client.IsEnabled("checkout", user1, false) {
		t.Fatal("rules were replaced")
	}
}

func TestServerErrorsRetryInTheBackground(t *testing.T) {
	fake := newFakeSDK(t)
	// Without a stream, only the retry schedule can recover.
	fake.set(func(f *fakeSDK) { f.streamStatus, f.rulesetStatuses = 401, []int{503, 500, 429} })
	client := newClient(t, fake)
	if err := client.Sync(context.Background()); err == nil || IsPermanent(err) {
		t.Fatalf("got %v, want a retryable error", err)
	}
	waitFor(t, func() bool { return client.IsEnabled("checkout", user1, false) })
}

func TestSyncHonorsItsContext(t *testing.T) {
	fake := newFakeSDK(t)
	fake.set(func(f *fakeSDK) { f.rulesetStatuses = []int{503, 503, 503, 503, 503} })
	slow := fast
	slow.retryBase, slow.maxRetry = time.Hour, time.Hour
	client := newClient(t, fake, withTimings(slow))
	_ = client.Sync(context.Background())
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	if err := client.Sync(ctx); err != context.DeadlineExceeded {
		t.Fatalf("got %v", err)
	}
}

func TestTheStreamOutlivesItsConnectTimeoutAndCloseEndsIt(t *testing.T) {
	fake := newFakeSDK(t)
	quick := fast
	quick.streamConnectTimeout = 50 * time.Millisecond
	client := newClient(t, fake, withTimings(quick))
	mustSync(t, client)
	<-fake.streamOpened
	time.Sleep(300 * time.Millisecond)
	fake.set(func(f *fakeSDK) {
		if f.streamConnections != 1 {
			t.Errorf("stream connected %d times", f.streamConnections)
		}
	})

	// The idle timeout is 5 seconds, so only an abort ends the read this soon.
	_ = client.Close()
	select {
	case <-client.stream.done:
	case <-time.After(time.Second):
		t.Fatal("stream still running after Close")
	}
}

func TestASilentStreamIsDroppedAndReconnected(t *testing.T) {
	fake := newFakeSDK(t)
	quick := fast
	quick.streamIdleTimeout = 100 * time.Millisecond
	client := newClient(t, fake, withTimings(quick))
	mustSync(t, client)
	// The fake sends nothing after its opening event, like a dead connection.
	waitFor(t, func() bool {
		fake.mu.Lock()
		defer fake.mu.Unlock()
		return fake.streamConnections >= 2
	})
}

func TestARejectedStreamFallsBackToPolling(t *testing.T) {
	fake := newFakeSDK(t)
	fake.set(func(f *fakeSDK) { f.streamStatus = 401 })
	quick := fast
	quick.pollInterval = 50 * time.Millisecond
	client := newClient(t, fake, withTimings(quick))
	mustSync(t, client)
	fake.set(func(f *fakeSDK) { f.flags, f.version = []rule{off("checkout")}, 2 })
	waitFor(t, func() bool { return !client.IsEnabled("checkout", user1, true) })
}

func TestExposuresCountRepeatedChecksAndBatchByUser(t *testing.T) {
	fake := newFakeSDK(t)
	client := newClient(t, fake, WithAppVersion("build-7"))
	mustSync(t, client)
	for range 3 {
		client.IsEnabled("checkout", User{Key: "user-1", Email: " Ann@Example.com "}, false)
	}
	client.IsEnabled("checkout", User{Key: "user-2"}, false)
	if err := client.Flush(context.Background()); err != nil {
		t.Fatal(err)
	}

	byUser := map[string]map[string]any{}
	for _, upload := range fake.uploads() {
		user := upload["user"].(map[string]any)
		byUser[fmt.Sprint(user["key"])] = upload
		if upload["sdk"] != "go" || upload["sdkVersion"] != Version || upload["appVersion"] != "build-7" {
			t.Errorf("upload labels: %v %v %v", upload["sdk"], upload["sdkVersion"], upload["appVersion"])
		}
	}
	first := byUser["user-1"]
	if user := first["user"].(map[string]any); user["email"] != "ann@example.com" {
		t.Errorf("email = %v", user["email"])
	}
	event := first["events"].([]any)[0].(map[string]any)
	if event["checkCount"] != 3.0 || event["result"] != true || event["reason"] != "rollout" || event["configurationVersion"] != 1.0 {
		t.Errorf("event = %v", event)
	}
	bucketAt, _ := time.Parse(time.RFC3339Nano, event["bucket"].(string))
	if bucketAt.UnixMilli()%30_000 != 0 || !strings.HasSuffix(event["bucket"].(string), ".000Z") {
		t.Errorf("bucket = %v", event["bucket"])
	}
	if byUser["user-2"]["events"].([]any)[0].(map[string]any)["checkCount"] != 1.0 {
		t.Error("user-2 checks mixed in")
	}
	if first["reporterId"] != byUser["user-2"]["reporterId"] {
		t.Error("reporter ID changed between uploads")
	}
}

func TestAFailedUploadRetriesTheSameBatch(t *testing.T) {
	fake := newFakeSDK(t)
	client := newClient(t, fake)
	mustSync(t, client)
	client.IsEnabled("checkout", user1, false)
	fake.set(func(f *fakeSDK) { f.exposureStatuses = []int{500} })
	if client.Flush(context.Background()) == nil {
		t.Fatal("expected the first upload to fail")
	}
	if err := client.Flush(context.Background()); err != nil {
		t.Fatal(err)
	}
	uploads := fake.uploads()
	if len(uploads) != 2 || uploads[0]["batchId"] != uploads[1]["batchId"] {
		t.Fatalf("uploads = %v", uploads)
	}
}

func TestARefusedUploadIsDroppedAndCountedAsLost(t *testing.T) {
	fake := newFakeSDK(t)
	client := newClient(t, fake)
	mustSync(t, client)
	client.IsEnabled("checkout", user1, false)
	client.IsEnabled("checkout", user1, false)
	fake.set(func(f *fakeSDK) { f.exposureStatuses = []int{400} })
	if client.Flush(context.Background()) == nil {
		t.Fatal("expected the upload to fail")
	}
	if err := client.Flush(context.Background()); err != nil {
		t.Fatal(err)
	}
	last := fake.uploads()[len(fake.uploads())-1]
	if len(last["events"].([]any)) != 0 || last["droppedChecks"] != 2.0 {
		t.Fatalf("last upload = %v", last)
	}
}

func TestChecksThatCantBeRecordedAreCountedAsLost(t *testing.T) {
	fake := newFakeSDK(t)
	client := newClient(t, fake)
	mustSync(t, client)
	client.IsEnabled(strings.Repeat("x", 257), user1, true)
	// 200 emoji are 400 UTF-16 units: past the server's 256 limit.
	client.IsEnabled("checkout", User{Key: strings.Repeat("😀", 200)}, true)
	if err := client.Flush(context.Background()); err != nil {
		t.Fatal(err)
	}
	last := fake.uploads()[len(fake.uploads())-1]
	if last["droppedChecks"] != 2.0 || len(last["events"].([]any)) != 0 {
		t.Fatalf("last upload = %v", last)
	}
}

func TestCloseFlushesOnceAndKeepsRulesUsable(t *testing.T) {
	fake := newFakeSDK(t)
	client := newClient(t, fake)
	mustSync(t, client)
	client.IsEnabled("checkout", user1, false)
	if err := client.Close(); err != nil {
		t.Fatal(err)
	}
	_ = client.Close()
	if len(fake.uploads()) != 1 {
		t.Fatalf("uploads = %d", len(fake.uploads()))
	}
	if !client.IsEnabled("checkout", user1, false) {
		t.Fatal("rules unusable after Close")
	}
	if err := client.Sync(context.Background()); err != ErrClosed {
		t.Fatalf("got %v", err)
	}
}

func TestNewRejectsBadKeysAndURLs(t *testing.T) {
	for _, item := range []struct {
		key, url string
	}{{"", DefaultURL}, {"fbs_ bad", DefaultURL}, {"fbs_test", "ftp://example.com"}} {
		if _, err := New(item.key, WithURL(item.url)); err == nil {
			t.Errorf("accepted %q %q", item.key, item.url)
		}
	}
}
