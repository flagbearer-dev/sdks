// Package flagbearer is the Flagbearer server SDK for Go. It downloads an
// environment's rules once and checks flags locally, so a flag check never
// waits on the network.
package flagbearer

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"unicode"
)

// DefaultURL is the production SDK address.
const DefaultURL = "https://sdk.flagbearer.dev"

// User is who a flag is checked for. Both fields are optional; empty means unset.
type User struct {
	Key   string
	Email string
}

// Option configures New.
type Option func(*options)

type options struct {
	url        string
	debug      bool
	appVersion string
	timings    timings
	httpClient *http.Client
}

// WithURL sets the SDK server address, for staging or local development.
func WithURL(address string) Option { return func(o *options) { o.url = address } }

// WithDebug logs stream connections, received versions, refreshes, retries and
// applied rules to stderr. Logs never include keys, user data, flag names or rules.
func WithDebug(enabled bool) Option { return func(o *options) { o.debug = enabled } }

// WithAppVersion labels which deployment of your app reported each check.
func WithAppVersion(version string) Option { return func(o *options) { o.appVersion = version } }

// WithHTTPClient replaces the client used for every request, such as to set a
// proxy or custom TLS. Redirects are still refused.
func WithHTTPClient(client *http.Client) Option {
	return func(o *options) { o.httpClient = client }
}

// Client checks flags against the rules in memory. Create one per process with
// New and keep it for the life of the process. It's safe for concurrent use.
type Client struct {
	rulesetURL  string
	headers     map[string]string
	client      *http.Client
	ruleset     atomic.Pointer[ruleset]
	coordinator *coordinator
	stream      *versionStream
	reporter    *exposureReporter
	connected   atomic.Bool
	closeOnce   sync.Once
	closeErr    error
}

// New starts a client: it fetches the rules and opens the stream in the
// background, without blocking. Call Sync to wait for the first rules.
func New(key string, opts ...Option) (*Client, error) {
	o := options{url: DefaultURL, timings: defaultTimings}
	for _, opt := range opts {
		opt(&o)
	}
	if key == "" || strings.IndexFunc(key, unicode.IsSpace) >= 0 {
		return nil, errors.New("a nonempty server SDK key is required")
	}
	base, err := url.Parse(o.url)
	if err != nil || (base.Scheme != "http" && base.Scheme != "https") || base.Host == "" {
		return nil, errors.New("Flagbearer url must use HTTP or HTTPS")
	}
	base.Path = strings.TrimSuffix(base.Path, "/")

	client := newHTTPClient()
	if o.httpClient != nil {
		copied := *o.httpClient
		copied.CheckRedirect = client.CheckRedirect
		client = &copied
	}
	debug := debugLog(o.debug)
	c := &Client{
		rulesetURL: endpoint(base, "/v1/ruleset"),
		headers:    map[string]string{"Authorization": "Bearer " + key},
		client:     client,
	}
	c.reporter = newExposureReporter(client, endpoint(base, "/v1/exposures"), key, o.appVersion, o.timings)
	c.coordinator = newCoordinator(c.fetch, c.ruleset.Store, func() { c.stream.close() }, o.timings, debug)
	c.stream = startVersionStream(&versionStream{
		client:    client,
		url:       endpoint(base, "/v1/stream"),
		headers:   c.headers,
		t:         o.timings,
		debug:     debug,
		onVersion: c.coordinator.onVersion,
		onOpen: func() {
			// After a reconnect, refetch in case an announcement was missed.
			c.coordinator.setStreaming(true, c.connected.Swap(true))
		},
		onDisconnect: func() { c.coordinator.setStreaming(false, false) },
	})
	c.coordinator.refreshInBackground()
	return c, nil
}

// Sync waits for the latest rules. It returns an error when the request fails
// or ctx ends; the client keeps retrying in the background either way.
func (c *Client) Sync(ctx context.Context) error {
	return c.coordinator.sync(ctx)
}

// IsEnabled checks a flag using only the rules in memory. It never blocks on the
// network. fallback is returned until the first rules arrive, and when the flag
// doesn't exist or can't be evaluated.
func (c *Client) IsEnabled(flag string, user User, fallback bool) bool {
	current := c.ruleset.Load()
	var details evaluation
	if current == nil {
		details = evaluation{fallback, reasonFallback, nil}
	} else {
		version := current.version
		var rule *flagRule
		if found, ok := current.flags[flag]; ok {
			rule = &found
		}
		details = evaluate(rule, user, &version, fallback)
	}
	c.reporter.record(flag, user, details)
	return details.value
}

// Flush uploads pending exposure reports, returning an error if an upload fails.
func (c *Client) Flush(ctx context.Context) error {
	return c.reporter.flush(ctx)
}

// Close stops streaming and background work, then uploads pending exposures.
// It's safe to call more than once. Rules in memory stay usable after it.
func (c *Client) Close() error {
	c.closeOnce.Do(func() {
		c.coordinator.close()
		c.stream.close()
		c.closeErr = c.reporter.close(context.Background())
	})
	return c.closeErr
}

func (c *Client) fetch(ctx context.Context) (*ruleset, error) {
	current := c.ruleset.Load()
	headers := map[string]string{}
	for name, value := range c.headers {
		headers[name] = value
	}
	if current != nil {
		headers["If-None-Match"] = `"` + strconv.FormatInt(current.version, 10) + `"`
	}
	response, body, err := send(ctx, c.client, http.MethodGet, c.rulesetURL, headers, nil)
	if err != nil {
		return nil, err
	}
	if response.StatusCode == http.StatusNotModified {
		return nil, nil
	}
	if err := checkStatus(response); err != nil {
		return nil, err
	}
	next, ok := parseRuleset(body)
	if !ok {
		return nil, &refreshError{message: "Invalid ruleset response"}
	}
	if current != nil && next.environmentID != current.environmentID {
		return nil, &refreshError{message: "Ruleset environment changed unexpectedly"}
	}
	return next, nil
}
