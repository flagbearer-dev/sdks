use std::sync::mpsc;
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::thread;
use std::time::{Duration, Instant};

use crate::evaluate::Ruleset;
use crate::http::{backoff, jitter, random_fraction, Error};
use crate::log::{DebugLog, Optional};

/// Every delay the client uses. Tests shorten them.
#[derive(Clone, Copy, Debug)]
pub struct Timings {
    pub(crate) request_timeout: Duration,
    pub(crate) poll_interval: Duration,
    pub(crate) retry_base: Duration,
    pub(crate) max_retry: Duration,
    pub(crate) max_version_refresh_delay: Duration,
    pub(crate) stream_connect_timeout: Duration,
    pub(crate) stream_idle_timeout: Duration,
    pub(crate) exposure_flush_interval: Duration,
    pub(crate) exposure_timeout: Duration,
}

impl Default for Timings {
    fn default() -> Self {
        Self {
            request_timeout: Duration::from_secs(5),
            poll_interval: Duration::from_secs(30),
            retry_base: Duration::from_secs(1),
            max_retry: Duration::from_secs(30),
            max_version_refresh_delay: Duration::from_secs(2),
            stream_connect_timeout: Duration::from_secs(5),
            stream_idle_timeout: Duration::from_secs(45),
            exposure_flush_interval: Duration::from_secs(30),
            exposure_timeout: Duration::from_secs(10),
        }
    }
}

/// Fetches the ruleset; `Ok(None)` means unchanged (304).
pub(crate) type Fetch = Box<dyn Fn() -> Result<Option<Ruleset>, Error> + Send + Sync>;
pub(crate) type Apply = Box<dyn Fn(Ruleset) + Send + Sync>;
pub(crate) type OnPermanent = Box<dyn Fn() + Send + Sync>;

/// Decides when to fetch the ruleset. One thread makes every fetch, so there
/// is never more than one in flight.
#[derive(Clone)]
pub(crate) struct Coordinator {
    shared: Arc<Shared>,
}

struct Shared {
    state: Mutex<State>,
    wake: Condvar,
    fetch: Fetch,
    apply: Apply,
    on_permanent: OnPermanent,
    timings: Timings,
    debug: DebugLog,
}

#[derive(Default)]
struct State {
    stopped: bool,
    blocked: bool,
    streaming: bool,
    version: Option<i64>,
    wanted: Option<i64>,
    attempt: u32,
    // Nothing is fetched before this: SDK backoff or a server's Retry-After.
    not_before: Option<Instant>,
    retry_at: Option<Instant>,
    poll_at: Option<Instant>,
    version_at: Option<Instant>,
    requested: bool,
    in_flight: bool,
    waiters: Vec<mpsc::Sender<Result<(), Error>>>,
}

impl Coordinator {
    pub(crate) fn start(
        fetch: Fetch,
        apply: Apply,
        on_permanent: OnPermanent,
        timings: Timings,
        debug: DebugLog,
    ) -> Self {
        let shared = Arc::new(Shared {
            state: Mutex::new(State::default()),
            wake: Condvar::new(),
            fetch,
            apply,
            on_permanent,
            timings,
            debug,
        });
        shared.ensure_polling(&mut shared.lock());
        let worker = Arc::clone(&shared);
        thread::Builder::new()
            .name("flagbearer-refresh".into())
            .spawn(move || worker.run())
            .expect("spawn flagbearer refresh thread");
        Self { shared }
    }

    /// Another handle on the same coordinator, for the stream's callbacks.
    pub(crate) fn handle(&self) -> Coordinator {
        self.clone()
    }

    pub(crate) fn sync(&self, timeout: Option<Duration>) -> Result<(), Error> {
        let (sender, receiver) = mpsc::channel();
        {
            let mut state = self.shared.lock();
            if state.stopped {
                return Err(Error::closed());
            }
            state.blocked = false;
            self.shared.ensure_polling(&mut state);
            state.waiters.push(sender);
            if state.in_flight {
                self.shared.debug.log("Joining active refresh", &[]);
            } else {
                state.retry_at = None;
                state.requested = true;
            }
        }
        self.shared.wake.notify_all();
        let result = match timeout {
            Some(timeout) => receiver.recv_timeout(timeout).map_err(|error| match error {
                mpsc::RecvTimeoutError::Timeout => Error::timeout(),
                mpsc::RecvTimeoutError::Disconnected => Error::closed(),
            }),
            None => receiver.recv().map_err(|_| Error::closed()),
        };
        result?
    }

    pub(crate) fn refresh_in_background(&self) {
        self.shared.background(&mut self.shared.lock());
        self.shared.wake.notify_all();
    }

    pub(crate) fn on_version(&self, version: i64) {
        let mut state = self.shared.lock();
        if state.stopped {
            return;
        }
        self.shared.debug.log(
            "Stream version received",
            &[
                ("version", &version),
                ("currentVersion", &Optional(state.version)),
            ],
        );
        state.wanted = Some(state.wanted.map_or(version, |wanted| wanted.max(version)));
        if behind(&state) && state.version_at.is_none() && !state.in_flight {
            let delay = self
                .shared
                .timings
                .max_version_refresh_delay
                .mul_f64(random_fraction());
            state.version_at = Some(Instant::now() + delay);
            drop(state);
            self.shared.wake.notify_all();
        }
    }

    pub(crate) fn set_streaming(&self, connected: bool, refresh: bool) {
        let mut state = self.shared.lock();
        if state.stopped {
            return;
        }
        if state.streaming != connected {
            self.shared.debug.log(
                if connected {
                    "Stream connected"
                } else {
                    "Stream disconnected; using polling"
                },
                &[],
            );
        }
        state.streaming = connected;
        if connected {
            self.shared.clear_polling(&mut state);
            if refresh {
                self.shared.background(&mut state);
            }
        } else {
            self.shared.ensure_polling(&mut state);
        }
        drop(state);
        self.shared.wake.notify_all();
    }

    pub(crate) fn close(&self) {
        let waiters = {
            let mut state = self.shared.lock();
            if state.stopped {
                return;
            }
            state.stopped = true;
            self.shared.debug.log("Coordinator closed", &[]);
            std::mem::take(&mut state.waiters)
        };
        self.shared.wake.notify_all();
        for waiter in waiters {
            let _ = waiter.send(Err(Error::closed()));
        }
    }
}

fn behind(state: &State) -> bool {
    match (state.wanted, state.version) {
        (Some(wanted), Some(version)) => version < wanted,
        (Some(_), None) => true,
        (None, _) => false,
    }
}

fn due(at: Option<Instant>, now: Instant) -> bool {
    at.is_some_and(|at| at <= now)
}

impl Shared {
    fn lock(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn background(&self, state: &mut State) {
        if state.stopped || state.blocked || state.retry_at.is_some() || state.in_flight {
            return;
        }
        state.requested = true;
    }

    fn ensure_polling(&self, state: &mut State) {
        if state.stopped || state.blocked || state.streaming || state.poll_at.is_some() {
            return;
        }
        let interval = jitter(self.timings.poll_interval, 0.1);
        self.debug.log(
            "Polling scheduled",
            &[("intervalMs", &interval.as_millis())],
        );
        state.poll_at = Some(Instant::now() + interval);
    }

    fn clear_polling(&self, state: &mut State) {
        if state.poll_at.take().is_some() {
            self.debug.log("Polling disabled", &[]);
        }
    }

    fn schedule_retry(&self, state: &mut State, error: Option<&Error>) {
        let mut delay = backoff(
            state.attempt,
            self.timings.retry_base,
            self.timings.max_retry,
        );
        state.attempt += 1;
        if let Some(error) = error {
            delay = delay.max(error.retry_after);
        }
        let at = Instant::now() + delay;
        state.not_before = Some(at);
        state.retry_at = Some(at);
        self.debug.log(
            "Refresh retry scheduled",
            &[("delayMs", &delay.as_millis()), ("attempt", &state.attempt)],
        );
    }

    /// How long until the worker has something to do, or None if never.
    fn next_due(state: &State, now: Instant) -> Option<Duration> {
        if state.requested {
            return Some(
                state
                    .not_before
                    .map_or(Duration::ZERO, |at| at.saturating_duration_since(now)),
            );
        }
        [state.retry_at, state.poll_at, state.version_at]
            .into_iter()
            .flatten()
            .min()
            .map(|at| at.saturating_duration_since(now))
    }

    /// Turns any timer that has fired into a fetch request.
    fn take_due(&self, state: &mut State, now: Instant) -> bool {
        if due(state.version_at, now) {
            state.version_at = None;
            if behind(state) {
                self.background(state);
            }
        }
        if due(state.retry_at, now) {
            state.retry_at = None;
            self.background(state);
        }
        if due(state.poll_at, now) {
            state.poll_at = None;
            self.background(state);
            self.ensure_polling(state);
        }
        state.requested && state.not_before.is_none_or(|at| at <= now)
    }

    fn run(&self) {
        loop {
            let waiters = {
                let mut state = self.lock();
                loop {
                    if state.stopped {
                        return;
                    }
                    let now = Instant::now();
                    if self.take_due(&mut state, now) {
                        break;
                    }
                    state = match Self::next_due(&state, now) {
                        Some(delay) => {
                            self.wake
                                .wait_timeout(state, delay)
                                .unwrap_or_else(|e| e.into_inner())
                                .0
                        }
                        None => self.wake.wait(state).unwrap_or_else(|e| e.into_inner()),
                    };
                }
                state.requested = false;
                state.in_flight = true;
                state.version_at = None;
                std::mem::take(&mut state.waiters)
            };

            let result = self.refresh();

            let (waiters, stopped) = {
                let mut state = self.lock();
                state.in_flight = false;
                // Sync calls that arrived during this fetch join it.
                let mut all = waiters;
                all.append(&mut state.waiters);
                (all, state.stopped)
            };
            if let Err(error) = &result {
                if !stopped && waiters.is_empty() {
                    crate::log::warn(&format!("Flagbearer sync failed: {error}"));
                }
            }
            for waiter in waiters {
                let _ = waiter.send(result.clone());
            }
        }
    }

    /// Fetches once, and again while a stream announcement is still ahead.
    fn refresh(&self) -> Result<(), Error> {
        loop {
            self.debug.log("Refresh started", &[]);
            let result = (self.fetch)();
            let mut state = self.lock();
            if state.stopped {
                return result.map(|_| ());
            }
            let ruleset = match result {
                Err(error) => {
                    let permanent = error.is_permanent();
                    if permanent {
                        self.debug
                            .log("Automatic refresh stopped after permanent failure", &[]);
                        state.blocked = true;
                        state.streaming = false;
                        state.retry_at = None;
                        self.clear_polling(&mut state);
                    } else {
                        self.schedule_retry(&mut state, Some(&error));
                    }
                    drop(state);
                    if permanent {
                        (self.on_permanent)();
                    }
                    return Err(error);
                }
                Ok(None) => {
                    self.debug.log(
                        "Snapshot unchanged",
                        &[("version", &Optional(state.version))],
                    );
                    if behind(&state) {
                        // The backend is still behind: retry later rather than spin.
                        self.schedule_retry(&mut state, None);
                    } else {
                        state.attempt = 0;
                        state.not_before = None;
                    }
                    return Ok(());
                }
                Ok(Some(ruleset)) => ruleset,
            };
            let previous = state.version;
            let version = ruleset.version;
            if previous.is_none_or(|previous| version >= previous) {
                state.version = Some(version);
                (self.apply)(ruleset);
                self.debug.log(
                    "Snapshot applied",
                    &[
                        ("version", &version),
                        ("previousVersion", &Optional(previous)),
                    ],
                );
            } else {
                self.debug.log(
                    "Older snapshot ignored",
                    &[
                        ("version", &version),
                        ("currentVersion", &Optional(previous)),
                    ],
                );
            }
            if !behind(&state) {
                state.attempt = 0;
                state.not_before = None;
                return Ok(());
            }
            if previous.is_some_and(|previous| version <= previous) {
                self.schedule_retry(&mut state, None);
                return Ok(());
            }
            // An announcement arrived during the request: fetch again now.
        }
    }
}
