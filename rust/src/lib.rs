//! The Flagbearer server SDK for Rust.
//!
//! It downloads an environment's rules once and checks flags locally, so a flag
//! check never waits on the network. A stream announces rule changes, and
//! polling covers the gaps. Background work runs on its own threads; no async
//! runtime is needed.
//!
//! ```no_run
//! let flags = flagbearer::Client::new(
//!     std::env::var("FLAGBEARER_SERVER_KEY").unwrap(),
//!     flagbearer::Options::default(),
//! )?;
//! if let Err(error) = flags.sync() {
//!     eprintln!("Initial Flagbearer sync failed: {error}");
//! }
//! let user = flagbearer::User::with_key("user-1");
//! let advanced = flags.is_enabled("advanced-search", &user, false);
//! # Ok::<(), flagbearer::Error>(())
//! ```

mod evaluate;
mod exposures;
mod http;
mod log;
mod refresh;
mod stream;

#[cfg(test)]
mod tests;

use std::sync::{Arc, Mutex, RwLock};
use std::time::Duration;

use evaluate::{evaluate, parse_ruleset, Evaluation, Reason, Ruleset};
use exposures::Reporter;
use log::DebugLog;
use refresh::{Coordinator, Timings};
use stream::{StreamOptions, Target, VersionStream};

pub use http::Error;

/// This SDK's version, sent with exposure reports.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

/// The production SDK address.
pub const DEFAULT_URL: &str = "https://sdk.flagbearer.dev";

/// Who a flag is checked for. Both fields are optional; empty means unset.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct User {
    pub key: Option<String>,
    pub email: Option<String>,
}

impl User {
    /// A user identified by their key.
    pub fn with_key(key: impl Into<String>) -> Self {
        Self {
            key: Some(key.into()),
            email: None,
        }
    }
}

/// Settings for [`Client::new`].
#[derive(Clone, Debug)]
pub struct Options {
    /// The SDK server address. Defaults to [`DEFAULT_URL`].
    pub url: String,
    /// Log stream connections, received versions, refreshes, retries and
    /// applied rules to stderr. Logs never include keys, user data, flag names
    /// or rules.
    pub debug: bool,
    /// Labels which deployment of your app reported each check.
    pub app_version: Option<String>,
    /// Internal delays, shortened by this crate's tests. Leave it at its default.
    #[doc(hidden)]
    pub timings: Timings,
}

impl Default for Options {
    fn default() -> Self {
        Self {
            url: DEFAULT_URL.into(),
            debug: false,
            app_version: None,
            timings: Timings::default(),
        }
    }
}

/// Checks flags against the rules in memory.
///
/// Create one per process and share it, for example in an `Arc<Client>`. It's
/// `Send + Sync`, and every method takes `&self`.
pub struct Client {
    rules: Arc<RwLock<Option<Arc<Ruleset>>>>,
    coordinator: Coordinator,
    stream: Arc<Mutex<Option<VersionStream>>>,
    reporter: Reporter,
    debug: DebugLog,
    closed: Mutex<bool>,
}

impl Client {
    /// Starts a client: it fetches the rules and opens the stream in the
    /// background, without blocking. Call [`Client::sync`] to wait for the
    /// first rules.
    ///
    /// Fails for an empty key, a key containing whitespace, or a URL that isn't
    /// HTTP or HTTPS.
    pub fn new(key: impl Into<String>, options: Options) -> Result<Self, Error> {
        let key = key.into();
        if key.is_empty() || key.chars().any(char::is_whitespace) {
            return Err(Error::invalid_options(
                "A nonempty server SDK key is required",
            ));
        }
        let target = parse_url(&options.url)?;
        let base = format!(
            "{}://{}{}",
            if target.tls { "https" } else { "http" },
            authority(&target),
            target.path
        );
        let timings = options.timings;
        let debug = DebugLog(options.debug);

        let agent = |timeout: Duration| -> ureq::Agent {
            ureq::Agent::config_builder()
                .http_status_as_error(false)
                .max_redirects(0)
                .timeout_global(Some(timeout))
                .user_agent(format!("flagbearer-rust/{VERSION}"))
                .build()
                .into()
        };

        let reporter = Reporter::start(
            agent(timings.exposure_timeout),
            format!("{base}/v1/exposures"),
            key.clone(),
            options.app_version,
            timings,
        );

        let rules: Arc<RwLock<Option<Arc<Ruleset>>>> = Arc::new(RwLock::new(None));
        let stream: Arc<Mutex<Option<VersionStream>>> = Arc::new(Mutex::new(None));

        let fetch = {
            let agent = agent(timings.request_timeout);
            let url = format!("{base}/v1/ruleset");
            let key = key.clone();
            let rules = Arc::clone(&rules);
            Box::new(move || fetch_ruleset(&agent, &url, &key, &rules))
        };
        let apply = {
            let rules = Arc::clone(&rules);
            Box::new(move |ruleset: Ruleset| {
                // One swap, so a check sees one whole version or the other.
                *rules.write().unwrap_or_else(|e| e.into_inner()) = Some(Arc::new(ruleset));
            })
        };
        let on_permanent = {
            let stream = Arc::clone(&stream);
            Box::new(move || {
                if let Some(stream) = stream.lock().unwrap_or_else(|e| e.into_inner()).as_ref() {
                    stream.close(debug);
                }
            })
        };
        let coordinator = Coordinator::start(fetch, apply, on_permanent, timings, debug);

        let client = Self {
            rules,
            coordinator,
            stream,
            reporter,
            debug,
            closed: Mutex::new(false),
        };
        client.start_stream(target, key, timings);
        client.coordinator.refresh_in_background();
        Ok(client)
    }

    fn start_stream(&self, mut target: Target, key: String, timings: Timings) {
        target.path = format!("{}/v1/stream", target.path);
        let shared = self.coordinator.handle();
        let opened = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let on_version = {
            let shared = shared.clone();
            Box::new(move |version| shared.on_version(version))
        };
        let on_open = {
            let shared = shared.clone();
            Box::new(move || {
                // After a reconnect, refetch in case an announcement was missed.
                let reconnected = opened.swap(true, std::sync::atomic::Ordering::SeqCst);
                shared.set_streaming(true, reconnected);
            })
        };
        let on_disconnect = Box::new(move || shared.set_streaming(false, false));
        let stream = VersionStream::start(StreamOptions {
            target,
            key,
            timings,
            debug: self.debug,
            on_version,
            on_open,
            on_disconnect,
        });
        *self.stream.lock().unwrap_or_else(|e| e.into_inner()) = Some(stream);
    }

    /// Waits for the latest rules. Fails when the request fails; the client
    /// keeps retrying in the background either way.
    ///
    /// This blocks the calling thread. In async code, call it through
    /// `tokio::task::spawn_blocking` or your runtime's equivalent.
    pub fn sync(&self) -> Result<(), Error> {
        self.coordinator.sync(None)
    }

    /// Like [`Client::sync`], but gives up after `timeout`.
    pub fn sync_timeout(&self, timeout: Duration) -> Result<(), Error> {
        self.coordinator.sync(Some(timeout))
    }

    /// Checks a flag using only the rules in memory. It never blocks on the
    /// network and never panics. `default` is returned until the first rules
    /// arrive, and when the flag doesn't exist.
    pub fn is_enabled(&self, flag: &str, user: &User, default: bool) -> bool {
        let current = self
            .rules
            .read()
            .ok()
            .and_then(|rules| rules.as_ref().map(Arc::clone));
        let details = match &current {
            Some(ruleset) => evaluate(
                ruleset.flags.get(flag),
                user,
                Some(ruleset.version),
                default,
            ),
            None => Evaluation {
                value: default,
                reason: Reason::Fallback,
                version: None,
            },
        };
        self.reporter.record(flag, user, details);
        details.value
    }

    /// Uploads pending exposure reports. Fails if an upload fails.
    pub fn flush(&self) -> Result<(), Error> {
        self.reporter.flush()
    }

    /// Stops streaming and background threads, then uploads pending exposures
    /// once. Safe to call more than once; later calls do nothing. Rules in
    /// memory stay usable after it. Dropping the client calls this too.
    pub fn close(&self) -> Result<(), Error> {
        {
            let mut closed = self.closed.lock().unwrap_or_else(|e| e.into_inner());
            if *closed {
                return Ok(());
            }
            *closed = true;
        }
        self.coordinator.close();
        if let Some(stream) = self
            .stream
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_ref()
        {
            stream.close(self.debug);
        }
        self.reporter.close()
    }
}

impl Drop for Client {
    fn drop(&mut self) {
        let _ = self.close();
    }
}

fn fetch_ruleset(
    agent: &ureq::Agent,
    url: &str,
    key: &str,
    rules: &RwLock<Option<Arc<Ruleset>>>,
) -> Result<Option<Ruleset>, Error> {
    let current = rules
        .read()
        .ok()
        .and_then(|rules| rules.as_ref().map(Arc::clone));
    let mut request = agent
        .get(url)
        .header("Authorization", format!("Bearer {key}"));
    if let Some(current) = &current {
        request = request.header("If-None-Match", format!("\"{}\"", current.version));
    }
    let failed = |e: ureq::Error| Error::retryable(format!("Flagbearer request failed: {e}"));
    let mut response = request.call().map_err(failed)?;
    let status = response.status().as_u16();
    if status == 304 {
        return Ok(None);
    }
    if !(200..300).contains(&status) {
        let header = response
            .headers()
            .get("retry-after")
            .and_then(|value| value.to_str().ok());
        return Err(http::status_error(status, header));
    }
    let body = response
        .body_mut()
        .with_config()
        .limit(64 << 20)
        .read_to_vec()
        .map_err(failed)?;
    let ruleset =
        parse_ruleset(&body).ok_or_else(|| Error::permanent("Invalid ruleset response"))?;
    if current.is_some_and(|current| current.environment_id != ruleset.environment_id) {
        return Err(Error::permanent("Ruleset environment changed unexpectedly"));
    }
    Ok(Some(ruleset))
}

fn parse_url(url: &str) -> Result<Target, Error> {
    let invalid = || Error::invalid_options("Flagbearer url must use HTTP or HTTPS");
    let uri: ureq::http::Uri = url.parse().map_err(|_| invalid())?;
    let tls = match uri.scheme_str() {
        Some("https") => true,
        Some("http") => false,
        _ => return Err(invalid()),
    };
    let host = uri
        .host()
        .filter(|host| !host.is_empty())
        .ok_or_else(invalid)?;
    // An IPv6 literal keeps its brackets in the URI but not for connecting.
    let host = host
        .trim_start_matches('[')
        .trim_end_matches(']')
        .to_owned();
    Ok(Target {
        tls,
        port: uri.port_u16().unwrap_or(if tls { 443 } else { 80 }),
        host,
        path: uri.path().trim_end_matches('/').to_owned(),
    })
}

fn authority(target: &Target) -> String {
    let host = if target.host.contains(':') {
        format!("[{}]", target.host)
    } else {
        target.host.clone()
    };
    format!("{host}:{}", target.port)
}
