use std::collections::HashMap;
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde_json::{json, Value};

use crate::evaluate::{integer, normalize_email, Evaluation, Reason};
use crate::http::{iso_millis, jitter, js_length, new_uuid, retry_after, Error};
use crate::refresh::Timings;
use crate::User;

const WINDOW_MS: u128 = 30_000;
const MAX_RECORDS: usize = 1000;
const BATCH_SIZE: usize = 100;
const MAX_CHECK_COUNT: u64 = 1_000_000_000;

#[derive(Clone, Debug, Default, PartialEq, Eq, Hash)]
struct Who {
    key: Option<String>,
    email: Option<String>,
}

impl Who {
    fn body(&self) -> Value {
        let mut object = serde_json::Map::new();
        if let Some(key) = &self.key {
            object.insert("key".into(), key.clone().into());
        }
        if let Some(email) = &self.email {
            object.insert("email".into(), email.clone().into());
        }
        Value::Object(object)
    }
}

#[derive(Clone, Debug)]
struct Event {
    flag_key: String,
    result: bool,
    reason: Reason,
    version: Option<i64>,
    bucket: String,
    check_count: u64,
    first_checked_at: String,
    last_checked_at: String,
}

impl Event {
    fn body(&self) -> Value {
        json!({
            "flagKey": self.flag_key,
            "result": self.result,
            "reason": self.reason.as_str(),
            "configurationVersion": self.version,
            "bucket": self.bucket,
            "checkCount": self.check_count,
            "firstCheckedAt": self.first_checked_at,
            "lastCheckedAt": self.last_checked_at,
        })
    }
}

/// One upload, fixed when sealed, so a retry sends exactly the same body and
/// the server can recognize it by its batch ID.
#[derive(Clone, Debug)]
struct Batch {
    id: String,
    user: Who,
    events: Vec<Event>,
}

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
struct Identity {
    flag: String,
    who: Who,
    result: bool,
    reason: Reason,
    version: Option<i64>,
    bucket: String,
}

#[derive(Default)]
struct State {
    active: HashMap<Identity, Event>,
    order: Vec<Identity>,
    sealed: Vec<Batch>,
    sealed_events: usize,
    dropped: u64,
    reported_dropped: u64,
    retry_at: Option<Instant>,
    failures: u32,
    closed: bool,
}

pub(crate) struct Reporter {
    shared: Arc<Shared>,
}

struct Shared {
    agent: ureq::Agent,
    url: String,
    key: String,
    app_version: Option<String>,
    reporter_id: String,
    // One upload at a time; flush() waits for the one in progress.
    send_lock: Mutex<()>,
    state: Mutex<State>,
    wake: Condvar,
}

impl Reporter {
    pub(crate) fn start(
        agent: ureq::Agent,
        url: String,
        key: String,
        app_version: Option<String>,
        timings: Timings,
    ) -> Self {
        let app_version = app_version.map(|version| {
            // Up to 100 UTF-16 code units, the server's limit.
            let mut units = 0;
            version
                .chars()
                .take_while(|c| {
                    units += c.len_utf16();
                    units <= 100
                })
                .collect()
        });
        let shared = Arc::new(Shared {
            agent,
            url,
            key,
            app_version,
            reporter_id: new_uuid(),
            send_lock: Mutex::new(()),
            state: Mutex::new(State::default()),
            wake: Condvar::new(),
        });
        let worker = Arc::clone(&shared);
        thread::Builder::new()
            .name("flagbearer-exposures".into())
            .spawn(move || worker.run(timings.exposure_flush_interval))
            .expect("spawn flagbearer exposure thread");
        Self { shared }
    }

    pub(crate) fn record(&self, flag: &str, user: &User, details: Evaluation) {
        let mut state = self.shared.lock();
        if state.closed {
            return;
        }
        let key = user.key.as_deref().filter(|key| !key.is_empty());
        if flag.is_empty() || js_length(flag) > 256 || key.is_some_and(|key| js_length(key) > 256) {
            state.dropped += 1;
            return;
        }
        let now = SystemTime::now();
        let millis = now.duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis());
        let bucket =
            iso_millis(UNIX_EPOCH + Duration::from_millis((millis / WINDOW_MS * WINDOW_MS) as u64));
        let email = user
            .email
            .as_deref()
            .filter(|email| js_length(email) <= 254)
            .map(normalize_email)
            .filter(|email| !email.is_empty());
        let who = Who {
            key: key.map(str::to_owned),
            email,
        };
        let identity = Identity {
            flag: flag.to_owned(),
            who,
            result: details.value,
            reason: details.reason,
            version: details.version,
            bucket: bucket.clone(),
        };
        if let Some(existing) = state.active.get_mut(&identity) {
            if existing.check_count < MAX_CHECK_COUNT {
                existing.check_count += 1;
                existing.last_checked_at = iso_millis(now);
            } else {
                state.dropped += 1;
            }
            return;
        }
        if state.active.len() + state.sealed_events >= MAX_RECORDS {
            state.dropped += 1;
            return;
        }
        let at = iso_millis(now);
        state.active.insert(
            identity.clone(),
            Event {
                flag_key: flag.to_owned(),
                result: details.value,
                reason: details.reason,
                version: details.version,
                bucket,
                check_count: 1,
                first_checked_at: at.clone(),
                last_checked_at: at,
            },
        );
        state.order.push(identity);
    }

    pub(crate) fn flush(&self) -> Result<(), Error> {
        self.shared.flush()
    }

    pub(crate) fn close(&self) -> Result<(), Error> {
        {
            let mut state = self.shared.lock();
            if state.closed {
                return Ok(());
            }
            state.closed = true;
        }
        self.shared.wake.notify_all();
        self.shared.flush()
    }
}

impl Shared {
    fn lock(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn run(&self, interval: Duration) {
        loop {
            let state = self.lock();
            let (state, _) = self
                .wake
                .wait_timeout_while(state, jitter(interval, 0.1), |state| !state.closed)
                .unwrap_or_else(|e| e.into_inner());
            if state.closed {
                return;
            }
            let due = state.retry_at.is_none_or(|at| at <= Instant::now());
            drop(state);
            if due {
                let _ = self.flush();
            }
        }
    }

    fn flush(&self) -> Result<(), Error> {
        let _sending = self.send_lock.lock().unwrap_or_else(|e| e.into_inner());
        let result = self.send();
        if result.is_err() {
            let mut state = self.lock();
            state.failures += 1;
            let delay =
                Duration::from_secs(60).min(Duration::from_secs(1 << state.failures.min(6)));
            let at = Instant::now() + delay;
            state.retry_at = Some(state.retry_at.map_or(at, |current| current.max(at)));
        }
        result
    }

    /// Moves active checks into batches. A batch never mixes users.
    fn seal(state: &mut State) {
        let mut open: HashMap<Who, usize> = HashMap::new();
        for identity in std::mem::take(&mut state.order) {
            let Some(event) = state.active.remove(&identity) else {
                continue;
            };
            let index = match open.get(&identity.who) {
                Some(&index) if state.sealed[index].events.len() < BATCH_SIZE => index,
                _ => {
                    state.sealed.push(Batch {
                        id: new_uuid(),
                        user: identity.who.clone(),
                        events: Vec::new(),
                    });
                    open.insert(identity.who.clone(), state.sealed.len() - 1);
                    state.sealed.len() - 1
                }
            };
            state.sealed[index].events.push(event);
            state.sealed_events += 1;
        }
        state.active.clear();
    }

    fn settle(&self, batch_id: &str) {
        let mut state = self.lock();
        if state
            .sealed
            .first()
            .is_some_and(|batch| batch.id == batch_id)
        {
            let batch = state.sealed.remove(0);
            state.sealed_events -= batch.events.len();
        }
    }

    fn send(&self) -> Result<(), Error> {
        Self::seal(&mut self.lock());
        loop {
            let (batch, dropped) = {
                let state = self.lock();
                if state.sealed.is_empty() && state.dropped <= state.reported_dropped {
                    return {
                        drop(state);
                        self.lock().failures = 0;
                        Ok(())
                    };
                }
                // Only lost checks to report: an empty batch carries the count.
                let batch = state.sealed.first().cloned().unwrap_or_else(|| Batch {
                    id: new_uuid(),
                    user: Who::default(),
                    events: Vec::new(),
                });
                (batch, state.dropped)
            };
            let checks: u64 = batch.events.iter().map(|event| event.check_count).sum();
            let mut body = json!({
                "batchId": batch.id,
                "reporterId": self.reporter_id,
                "user": batch.user.body(),
                "events": batch.events.iter().map(Event::body).collect::<Vec<_>>(),
                "droppedChecks": dropped,
                "sdk": "rust",
                "sdkVersion": crate::VERSION,
            });
            if let Some(version) = &self.app_version {
                body["appVersion"] = version.clone().into();
            }
            let response = self
                .agent
                .post(&self.url)
                .header("Authorization", format!("Bearer {}", self.key))
                .header("Content-Type", "application/json")
                .send(body.to_string());
            let mut response =
                response.map_err(|e| Error::retryable(format!("Exposure upload failed: {e}")))?;
            let status = response.status().as_u16();
            if !(200..300).contains(&status) {
                let refused = (400..500).contains(&status) && status != 429;
                let header = response
                    .headers()
                    .get("retry-after")
                    .and_then(|value| value.to_str().ok());
                let wait = retry_after(header, SystemTime::now()).min(Duration::from_secs(3600));
                {
                    let mut state = self.lock();
                    if !wait.is_zero() {
                        let at = Instant::now() + wait;
                        state.retry_at = Some(state.retry_at.map_or(at, |current| current.max(at)));
                    }
                    if refused {
                        // The server won't take this batch, so retrying can't help.
                        state.dropped += checks;
                        state.retry_at = Some(Instant::now() + Duration::from_secs(60));
                    }
                }
                if refused {
                    self.settle(&batch.id);
                }
                return Err(Error::retryable(format!(
                    "Exposure upload failed ({status})"
                )));
            }
            let data = response
                .body_mut()
                .with_config()
                .limit(1 << 20)
                .read_to_vec()
                .map_err(|e| Error::retryable(format!("Exposure upload failed: {e}")))?;
            let rejected = read_receipt(&data, checks, batch.events.len())?;
            {
                let mut state = self.lock();
                state.dropped += rejected;
                state.reported_dropped = state.reported_dropped.max(dropped);
            }
            self.settle(&batch.id);
        }
    }
}

fn read_receipt(data: &[u8], checks: u64, events: usize) -> Result<u64, Error> {
    let receipt: Value = serde_json::from_slice(data)
        .map_err(|_| Error::retryable("Invalid exposure upload receipt"))?;
    let accepted = receipt.get("accepted").and_then(integer);
    let rejected = receipt.get("rejectedChecks").and_then(integer);
    match (accepted, rejected) {
        (Some(accepted), Some(rejected))
            if (0..=events as i64).contains(&accepted)
                && (0..=checks as i64).contains(&rejected) =>
        {
            Ok(rejected as u64)
        }
        _ => Err(Error::retryable("Invalid exposure upload receipt")),
    }
}
