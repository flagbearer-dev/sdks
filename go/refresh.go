package flagbearer

import (
	"context"
	"errors"
	"sync"
	"time"
)

// ErrClosed is returned by Sync after Close.
var ErrClosed = errors.New("Flagbearer client is closed")

// timings holds every delay the client uses. Tests shorten them.
type timings struct {
	requestTimeout         time.Duration
	pollInterval           time.Duration
	retryBase              time.Duration
	maxRetry               time.Duration
	maxVersionRefreshDelay time.Duration
	streamConnectTimeout   time.Duration
	streamIdleTimeout      time.Duration
	exposureFlushInterval  time.Duration
	exposureTimeout        time.Duration
}

var defaultTimings = timings{
	requestTimeout:         5 * time.Second,
	pollInterval:           30 * time.Second,
	retryBase:              time.Second,
	maxRetry:               30 * time.Second,
	maxVersionRefreshDelay: 2 * time.Second,
	streamConnectTimeout:   5 * time.Second,
	streamIdleTimeout:      45 * time.Second,
	exposureFlushInterval:  30 * time.Second,
	exposureTimeout:        10 * time.Second,
}

// coordinator decides when to fetch the ruleset. One goroutine makes every
// fetch, so there is never more than one in flight.
type coordinator struct {
	fetch       func(context.Context) (*ruleset, error) // nil ruleset: unchanged (304)
	apply       func(*ruleset)
	onPermanent func()
	t           timings
	debug       debugLog

	ctx    context.Context // canceled by close, which aborts a fetch in flight
	cancel context.CancelFunc
	wake   chan struct{}

	mu        sync.Mutex
	stopped   bool
	blocked   bool
	streaming bool
	version   *int64
	wanted    *int64
	attempt   int
	notBefore time.Time // nothing is fetched before this: backoff or Retry-After
	retryAt   time.Time
	pollAt    time.Time
	versionAt time.Time
	requested bool
	inFlight  bool
	waiters   []chan error
}

func newCoordinator(fetch func(context.Context) (*ruleset, error), apply func(*ruleset), onPermanent func(), t timings, debug debugLog) *coordinator {
	ctx, cancel := context.WithCancel(context.Background())
	c := &coordinator{
		fetch: fetch, apply: apply, onPermanent: onPermanent, t: t, debug: debug,
		ctx: ctx, cancel: cancel, wake: make(chan struct{}, 1),
	}
	c.ensurePolling()
	go c.run()
	return c
}

func (c *coordinator) notify() {
	select {
	case c.wake <- struct{}{}:
	default:
	}
}

func (c *coordinator) sync(ctx context.Context) error {
	done := make(chan error, 1)
	c.mu.Lock()
	if c.stopped {
		c.mu.Unlock()
		return ErrClosed
	}
	c.blocked = false
	c.ensurePolling()
	c.waiters = append(c.waiters, done)
	if c.inFlight {
		c.debug.log("Joining active refresh")
	} else {
		c.retryAt = time.Time{}
		c.requested = true
	}
	c.mu.Unlock()
	c.notify()
	select {
	case err := <-done:
		return err
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (c *coordinator) refreshInBackground() {
	c.mu.Lock()
	c.backgroundLocked()
	c.mu.Unlock()
	c.notify()
}

func (c *coordinator) onVersion(version int64) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.stopped {
		return
	}
	c.debug.log("Stream version received", field{"version", version}, field{"currentVersion", optional(c.version)})
	if c.wanted == nil || version > *c.wanted {
		c.wanted = &version
	}
	if c.behind() && c.versionAt.IsZero() && !c.inFlight {
		delay := time.Duration(float64(c.t.maxVersionRefreshDelay) * randomFraction())
		c.versionAt = time.Now().Add(delay)
		c.notify()
	}
}

func (c *coordinator) setStreaming(connected, refresh bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.stopped {
		return
	}
	if c.streaming != connected {
		if connected {
			c.debug.log("Stream connected")
		} else {
			c.debug.log("Stream disconnected; using polling")
		}
	}
	c.streaming = connected
	if connected {
		c.clearPolling()
		if refresh {
			c.backgroundLocked()
		}
	} else {
		c.ensurePolling()
	}
	c.notify()
}

func (c *coordinator) close() {
	c.mu.Lock()
	if c.stopped {
		c.mu.Unlock()
		return
	}
	c.stopped = true
	c.debug.log("Coordinator closed")
	waiters := c.waiters
	c.waiters = nil
	c.mu.Unlock()
	c.cancel()
	for _, waiter := range waiters {
		waiter <- ErrClosed
	}
}

// The helpers below expect c.mu to be held.

func (c *coordinator) behind() bool {
	return c.wanted != nil && (c.version == nil || *c.version < *c.wanted)
}

func (c *coordinator) backgroundLocked() {
	if c.stopped || c.blocked || !c.retryAt.IsZero() || c.inFlight {
		return
	}
	c.requested = true
}

func (c *coordinator) ensurePolling() {
	if c.stopped || c.blocked || c.streaming || !c.pollAt.IsZero() {
		return
	}
	interval := jitter(c.t.pollInterval, 0.1)
	c.debug.log("Polling scheduled", field{"intervalMs", interval.Milliseconds()})
	c.pollAt = time.Now().Add(interval)
}

func (c *coordinator) clearPolling() {
	if !c.pollAt.IsZero() {
		c.debug.log("Polling disabled")
	}
	c.pollAt = time.Time{}
}

func (c *coordinator) scheduleRetry(err error) {
	delay := backoff(c.attempt, c.t.retryBase, c.t.maxRetry)
	c.attempt++
	var refresh *refreshError
	if errors.As(err, &refresh) {
		delay = max(delay, refresh.retryAfter)
	}
	c.notBefore = time.Now().Add(delay)
	c.retryAt = c.notBefore
	c.debug.log("Refresh retry scheduled", field{"delayMs", delay.Milliseconds()}, field{"attempt", c.attempt})
}

// nextDue is how long until the worker has something to do; ok is false if never.
func (c *coordinator) nextDue(now time.Time) (time.Duration, bool) {
	if c.requested {
		return max(0, c.notBefore.Sub(now)), true
	}
	var next time.Time
	for _, at := range []time.Time{c.retryAt, c.pollAt, c.versionAt} {
		if !at.IsZero() && (next.IsZero() || at.Before(next)) {
			next = at
		}
	}
	if next.IsZero() {
		return 0, false
	}
	return max(0, next.Sub(now)), true
}

// takeDue turns any timer that has fired into a fetch request.
func (c *coordinator) takeDue(now time.Time) bool {
	if !c.versionAt.IsZero() && !c.versionAt.After(now) {
		c.versionAt = time.Time{}
		if c.behind() {
			c.backgroundLocked()
		}
	}
	if !c.retryAt.IsZero() && !c.retryAt.After(now) {
		c.retryAt = time.Time{}
		c.backgroundLocked()
	}
	if !c.pollAt.IsZero() && !c.pollAt.After(now) {
		c.pollAt = time.Time{}
		c.backgroundLocked()
		c.ensurePolling()
	}
	return c.requested && !now.Before(c.notBefore)
}

// The worker goroutine.

func (c *coordinator) run() {
	timer := time.NewTimer(time.Hour)
	defer timer.Stop()
	for {
		c.mu.Lock()
		for {
			if c.stopped {
				c.mu.Unlock()
				return
			}
			now := time.Now()
			if c.takeDue(now) {
				break
			}
			delay, ok := c.nextDue(now)
			c.mu.Unlock()
			if !ok {
				delay = time.Hour
			}
			timer.Reset(delay)
			select {
			case <-c.wake:
			case <-timer.C:
			case <-c.ctx.Done():
			}
			if !timer.Stop() {
				select {
				case <-timer.C:
				default:
				}
			}
			c.mu.Lock()
		}
		c.requested = false
		c.inFlight = true
		c.versionAt = time.Time{}
		waiters := c.waiters
		c.waiters = nil
		c.mu.Unlock()

		err := c.refresh()

		c.mu.Lock()
		c.inFlight = false
		// Sync calls that arrived during this fetch join it.
		waiters = append(waiters, c.waiters...)
		c.waiters = nil
		stopped := c.stopped
		c.mu.Unlock()
		for _, waiter := range waiters {
			waiter <- err
		}
		if err != nil && !stopped && len(waiters) == 0 {
			logger.Printf("Flagbearer sync failed: %v", err)
		}
	}
}

// refresh fetches once, and again while a stream announcement is still ahead.
func (c *coordinator) refresh() error {
	for {
		c.debug.log("Refresh started")
		ctx, cancel := context.WithTimeout(c.ctx, c.t.requestTimeout)
		result, err := c.fetch(ctx)
		cancel()
		if err != nil {
			c.mu.Lock()
			if c.stopped {
				c.mu.Unlock()
				return err
			}
			permanent := IsPermanent(err)
			if permanent {
				c.debug.log("Automatic refresh stopped after permanent failure")
				c.blocked = true
				c.streaming = false
				c.retryAt = time.Time{}
				c.clearPolling()
			} else {
				c.scheduleRetry(err)
			}
			c.mu.Unlock()
			if permanent {
				c.onPermanent()
			}
			return err
		}

		c.mu.Lock()
		if c.stopped {
			c.mu.Unlock()
			return nil
		}
		if result == nil {
			c.debug.log("Snapshot unchanged", field{"version", optional(c.version)})
			if c.behind() {
				// The backend is still behind: retry later rather than spin.
				c.scheduleRetry(nil)
			} else {
				c.attempt = 0
				c.notBefore = time.Time{}
			}
			c.mu.Unlock()
			return nil
		}
		previous := c.version
		if previous == nil || result.version >= *previous {
			version := result.version
			c.version = &version
			c.apply(result)
			c.debug.log("Snapshot applied", field{"version", version}, field{"previousVersion", optional(previous)})
		} else {
			c.debug.log("Older snapshot ignored", field{"version", result.version}, field{"currentVersion", *previous})
		}
		if !c.behind() {
			c.attempt = 0
			c.notBefore = time.Time{}
			c.mu.Unlock()
			return nil
		}
		if previous != nil && result.version <= *previous {
			c.scheduleRetry(nil)
			c.mu.Unlock()
			return nil
		}
		// An announcement arrived during the request: fetch again now.
		c.mu.Unlock()
	}
}
