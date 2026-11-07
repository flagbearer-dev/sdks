use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use serde_json::{json, Value};

use crate::refresh::Timings;
use crate::{Client, Options, User};

/// Short enough that retries finish within a test, long enough that nothing
/// polls unless a test asks for it.
fn fast() -> Timings {
    Timings {
        request_timeout: Duration::from_secs(1),
        poll_interval: Duration::from_secs(3600),
        retry_base: Duration::from_millis(10),
        max_retry: Duration::from_millis(50),
        max_version_refresh_delay: Duration::ZERO,
        stream_connect_timeout: Duration::from_secs(1),
        stream_idle_timeout: Duration::from_secs(5),
        exposure_flush_interval: Duration::from_secs(3600),
        exposure_timeout: Duration::from_secs(1),
    }
}

fn rule(key: &str, enabled: bool) -> Value {
    json!({"key": key, "enabled": enabled, "rolloutBps": 10000, "overrideEmails": []})
}

/// What the fake SDK server returns, and what it received.
struct State {
    version: i64,
    environment_id: String,
    flags: Vec<Value>,
    ruleset_body: Option<String>,
    ruleset_statuses: Vec<u16>,
    ruleset_redirect: bool,
    stream_status: u16,
    exposure_statuses: Vec<u16>,
    ruleset_requests: Vec<Option<String>>,
    exposures: Vec<Value>,
    stream_connections: usize,
}

/// The SDK server's three routes on a real socket.
struct Fake {
    url: String,
    state: Arc<Mutex<State>>,
    events: mpsc::Sender<String>,
    stream_opened: Arc<(Mutex<bool>, std::sync::Condvar)>,
    stopping: Arc<AtomicBool>,
}

impl Drop for Fake {
    fn drop(&mut self) {
        self.stopping.store(true, Ordering::SeqCst);
    }
}

impl Fake {
    fn new() -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let state = Arc::new(Mutex::new(State {
            version: 1,
            environment_id: "env-1".into(),
            flags: vec![rule("checkout", true)],
            ruleset_body: None,
            ruleset_statuses: Vec::new(),
            ruleset_redirect: false,
            stream_status: 200,
            exposure_statuses: Vec::new(),
            ruleset_requests: Vec::new(),
            exposures: Vec::new(),
            stream_connections: 0,
        }));
        let (events, receiver) = mpsc::channel::<String>();
        let receiver = Arc::new(Mutex::new(receiver));
        let stream_opened = Arc::new((Mutex::new(false), std::sync::Condvar::new()));
        let stopping = Arc::new(AtomicBool::new(false));
        {
            let state = Arc::clone(&state);
            let stream_opened = Arc::clone(&stream_opened);
            let stopping = Arc::clone(&stopping);
            listener.set_nonblocking(true).unwrap();
            thread::spawn(move || loop {
                if stopping.load(Ordering::SeqCst) {
                    return;
                }
                match listener.accept() {
                    Ok((socket, _)) => {
                        socket.set_nonblocking(false).unwrap();
                        let (state, receiver, opened, stopping) = (
                            Arc::clone(&state),
                            Arc::clone(&receiver),
                            Arc::clone(&stream_opened),
                            Arc::clone(&stopping),
                        );
                        thread::spawn(move || {
                            let _ = serve(socket, &state, &receiver, &opened, &stopping);
                        });
                    }
                    Err(_) => thread::sleep(Duration::from_millis(2)),
                }
            });
        }
        Self {
            url,
            state,
            events,
            stream_opened,
            stopping,
        }
    }

    fn set(&self, apply: impl FnOnce(&mut State)) {
        apply(&mut self.state.lock().unwrap());
    }

    fn get<T>(&self, read: impl FnOnce(&State) -> T) -> T {
        read(&self.state.lock().unwrap())
    }

    fn announce(&self, version: i64) {
        self.events
            .send(format!(
                "event: version\ndata: {{\"version\":{version}}}\n\n"
            ))
            .unwrap();
    }

    fn wait_for_stream(&self) {
        let (opened, signal) = &*self.stream_opened;
        let guard = opened.lock().unwrap();
        let (guard, _) = signal
            .wait_timeout_while(guard, Duration::from_secs(3), |opened| !*opened)
            .unwrap();
        assert!(*guard, "stream never opened");
    }

    fn client(&self, timings: Timings) -> Client {
        self.client_with(timings, None)
    }

    fn client_with(&self, timings: Timings, app_version: Option<&str>) -> Client {
        Client::new(
            "fbs_test",
            Options {
                url: self.url.clone(),
                app_version: app_version.map(str::to_owned),
                timings,
                ..Options::default()
            },
        )
        .unwrap()
    }
}

fn respond(socket: &mut TcpStream, status: u16, headers: &[(&str, &str)], body: &str) {
    let mut head = format!(
        "HTTP/1.1 {status} X\r\nContent-Length: {}\r\nConnection: close\r\n",
        body.len()
    );
    for (name, value) in headers {
        head.push_str(&format!("{name}: {value}\r\n"));
    }
    let _ = socket.write_all(format!("{head}\r\n{body}").as_bytes());
}

fn serve(
    mut socket: TcpStream,
    state: &Mutex<State>,
    events: &Mutex<mpsc::Receiver<String>>,
    opened: &(Mutex<bool>, std::sync::Condvar),
    stopping: &AtomicBool,
) -> std::io::Result<()> {
    let mut reader = BufReader::new(socket.try_clone()?);
    let mut request_line = String::new();
    reader.read_line(&mut request_line)?;
    let path = request_line
        .split_whitespace()
        .nth(1)
        .unwrap_or("")
        .to_owned();
    let mut headers = Vec::new();
    loop {
        let mut line = String::new();
        reader.read_line(&mut line)?;
        let line = line.trim_end();
        if line.is_empty() {
            break;
        }
        if let Some((name, value)) = line.split_once(':') {
            headers.push((name.trim().to_ascii_lowercase(), value.trim().to_owned()));
        }
    }
    let header = |name: &str| {
        headers
            .iter()
            .find(|(n, _)| n == name)
            .map(|(_, v)| v.clone())
    };
    if header("authorization").as_deref() != Some("Bearer fbs_test") {
        respond(&mut socket, 401, &[], "{}");
        return Ok(());
    }
    match path.as_str() {
        "/v1/ruleset" => {
            let (status, redirect, etag, body, inm) = {
                let mut s = state.lock().unwrap();
                let inm = header("if-none-match");
                s.ruleset_requests.push(inm.clone());
                let status = if s.ruleset_statuses.is_empty() {
                    200
                } else {
                    s.ruleset_statuses.remove(0)
                };
                let body = s.ruleset_body.clone().unwrap_or_else(|| {
                    json!({"environmentId": s.environment_id, "version": s.version, "flags": s.flags}).to_string()
                });
                (
                    status,
                    s.ruleset_redirect,
                    format!("\"{}\"", s.version),
                    body,
                    inm,
                )
            };
            if redirect {
                respond(&mut socket, 302, &[("Location", "/v1/elsewhere")], "");
            } else if status != 200 {
                respond(&mut socket, status, &[("Retry-After", "0")], "{}");
            } else if inm.as_deref() == Some(etag.as_str()) {
                let _ = socket.write_all(b"HTTP/1.1 304 Not Modified\r\nConnection: close\r\n\r\n");
            } else {
                respond(
                    &mut socket,
                    200,
                    &[("ETag", &etag), ("Content-Type", "application/json")],
                    &body,
                );
            }
        }
        "/v1/stream" => {
            let (status, version) = {
                let mut s = state.lock().unwrap();
                s.stream_connections += 1;
                (s.stream_status, s.version)
            };
            if status != 200 {
                respond(&mut socket, status, &[], "{}");
                return Ok(());
            }
            socket.write_all(
                b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n",
            )?;
            let chunk = |text: &str| format!("{:x}\r\n{text}\r\n", text.len());
            let opening =
                format!("retry: 5000\n\nevent: version\ndata: {{\"version\":{version}}}\n\n");
            socket.write_all(chunk(&opening).as_bytes())?;
            {
                let (flag, signal) = opened;
                *flag.lock().unwrap() = true;
                signal.notify_all();
            }
            loop {
                if stopping.load(Ordering::SeqCst) {
                    return Ok(());
                }
                let next = events
                    .lock()
                    .unwrap()
                    .recv_timeout(Duration::from_millis(20));
                if let Ok(event) = next {
                    socket.write_all(chunk(&event).as_bytes())?;
                }
            }
        }
        "/v1/exposures" => {
            let length: usize = header("content-length")
                .and_then(|v| v.parse().ok())
                .unwrap_or(0);
            let mut body = vec![0; length];
            reader.read_exact(&mut body)?;
            let body: Value = serde_json::from_slice(&body).unwrap();
            let status = {
                let mut s = state.lock().unwrap();
                s.exposures.push(body.clone());
                if s.exposure_statuses.is_empty() {
                    200
                } else {
                    s.exposure_statuses.remove(0)
                }
            };
            if status != 200 {
                respond(&mut socket, status, &[], "{}");
            } else {
                let accepted = body["events"].as_array().map_or(0, Vec::len);
                let receipt = json!({"accepted": accepted, "rejectedChecks": 0}).to_string();
                respond(
                    &mut socket,
                    200,
                    &[("Content-Type", "application/json")],
                    &receipt,
                );
            }
        }
        _ => respond(&mut socket, 404, &[], "{}"),
    }
    Ok(())
}

/// Polls a condition the SDK reaches on its own threads.
fn wait_for(condition: impl Fn() -> bool) {
    let deadline = Instant::now() + Duration::from_secs(3);
    while !condition() {
        assert!(Instant::now() < deadline, "condition not met in time");
        thread::sleep(Duration::from_millis(5));
    }
}

fn user1() -> User {
    User::with_key("user-1")
}

#[test]
fn checks_return_the_default_before_any_ruleset() {
    let fake = Fake::new();
    fake.set(|s| s.ruleset_statuses = vec![503; 1000]);
    let client = fake.client(fast());
    assert!(client.is_enabled("checkout", &user1(), true));
    assert!(!client.is_enabled("checkout", &user1(), false));
}

#[test]
fn checks_use_the_rules_after_a_sync() {
    let fake = Fake::new();
    let client = fake.client(fast());
    client.sync().unwrap();
    assert!(client.is_enabled("checkout", &user1(), false));
    assert!(client.is_enabled("missing", &user1(), true));
}

#[test]
fn a_stream_announcement_refetches_with_the_current_version() {
    let fake = Fake::new();
    let client = fake.client(fast());
    client.sync().unwrap();
    fake.wait_for_stream();
    fake.set(|s| {
        s.flags = vec![rule("checkout", false)];
        s.version = 2;
    });
    fake.announce(2);
    wait_for(|| !client.is_enabled("checkout", &user1(), true));
    assert!(fake.get(|s| s.ruleset_requests.contains(&Some("\"1\"".into()))));
}

#[test]
fn an_unchanged_ruleset_keeps_the_rules() {
    let fake = Fake::new();
    let client = fake.client(fast());
    client.sync().unwrap();
    client.sync().unwrap();
    assert_eq!(
        fake.get(|s| s.ruleset_requests.last().cloned()),
        Some(Some("\"1\"".into()))
    );
    assert!(client.is_enabled("checkout", &user1(), false));
}

#[test]
fn an_invalid_ruleset_fails_and_keeps_the_last_rules() {
    let fake = Fake::new();
    let client = fake.client(fast());
    client.sync().unwrap();
    fake.set(|s| {
        s.version = 2;
        s.ruleset_body =
            Some(r#"{"environmentId":"env-1","version":2,"flags":[{"key":"checkout"}]}"#.into());
    });
    let error = client.sync().unwrap_err();
    assert!(error.is_permanent(), "{error}");
    assert!(client.is_enabled("checkout", &user1(), false));

    // An explicit sync tries again after a permanent failure.
    fake.set(|s| {
        s.ruleset_body = None;
        s.flags = vec![rule("checkout", false)];
    });
    client.sync().unwrap();
    assert!(!client.is_enabled("checkout", &user1(), true));
}

#[test]
fn sync_after_a_permanent_failure_restarts_polling() {
    let fake = Fake::new();
    fake.set(|s| s.stream_status = 401);
    let client = fake.client(Timings {
        poll_interval: Duration::from_millis(50),
        ..fast()
    });
    client.sync().unwrap();
    fake.set(|s| {
        s.ruleset_body = Some("not json".into());
        s.version = 2;
    });
    assert!(client.sync().is_err());
    fake.set(|s| s.ruleset_body = None);
    client.sync().unwrap();
    fake.set(|s| {
        s.flags = vec![rule("checkout", false)];
        s.version = 3;
    });
    wait_for(|| !client.is_enabled("checkout", &user1(), true));
}

#[test]
fn a_ruleset_for_another_environment_is_rejected() {
    let fake = Fake::new();
    let client = fake.client(fast());
    client.sync().unwrap();
    fake.set(|s| {
        s.environment_id = "env-2".into();
        s.flags = vec![rule("checkout", false)];
        s.version = 2;
    });
    let error = client.sync().unwrap_err();
    assert!(error.to_string().contains("environment changed"), "{error}");
    assert!(client.is_enabled("checkout", &user1(), false));
}

#[test]
fn server_errors_retry_in_the_background() {
    let fake = Fake::new();
    // Without a stream, only the retry schedule can recover.
    fake.set(|s| {
        s.stream_status = 401;
        s.ruleset_statuses = vec![503, 500, 429];
    });
    let client = fake.client(fast());
    let error = client.sync().unwrap_err();
    assert!(!error.is_permanent(), "{error}");
    wait_for(|| client.is_enabled("checkout", &user1(), false));
}

#[test]
fn a_redirect_is_a_permanent_failure() {
    let fake = Fake::new();
    fake.set(|s| s.ruleset_redirect = true);
    let client = fake.client(fast());
    let error = client.sync().unwrap_err();
    assert!(error.is_permanent(), "{error}");
    assert!(error.to_string().contains("302"), "{error}");
}

#[test]
fn sync_timeout_gives_up_in_time() {
    let fake = Fake::new();
    fake.set(|s| s.ruleset_statuses = vec![503; 5]);
    let client = fake.client(Timings {
        retry_base: Duration::from_secs(3600),
        max_retry: Duration::from_secs(3600),
        ..fast()
    });
    let _ = client.sync();
    let started = Instant::now();
    let error = client.sync_timeout(Duration::from_millis(50)).unwrap_err();
    assert!(error.is_timeout(), "{error}");
    assert!(started.elapsed() < Duration::from_secs(1));
}

#[test]
fn the_stream_outlives_its_connect_timeout_and_close_ends_it() {
    let fake = Fake::new();
    let client = fake.client(Timings {
        stream_connect_timeout: Duration::from_millis(50),
        ..fast()
    });
    client.sync().unwrap();
    fake.wait_for_stream();
    thread::sleep(Duration::from_millis(300));
    assert_eq!(fake.get(|s| s.stream_connections), 1);

    // The idle timeout is 5 seconds, so only an abort ends the read this soon.
    client.close().unwrap();
    let stream = client.stream.lock().unwrap();
    assert!(
        stream.as_ref().unwrap().join(Duration::from_secs(1)),
        "stream still running"
    );
}

#[test]
fn a_silent_stream_is_dropped_and_reconnected() {
    let fake = Fake::new();
    let client = fake.client(Timings {
        stream_idle_timeout: Duration::from_millis(100),
        ..fast()
    });
    client.sync().unwrap();
    // The fake sends nothing after its opening event, like a dead connection.
    wait_for(|| fake.get(|s| s.stream_connections) >= 2);
}

#[test]
fn a_rejected_stream_falls_back_to_polling() {
    let fake = Fake::new();
    fake.set(|s| s.stream_status = 401);
    let client = fake.client(Timings {
        poll_interval: Duration::from_millis(50),
        ..fast()
    });
    client.sync().unwrap();
    fake.set(|s| {
        s.flags = vec![rule("checkout", false)];
        s.version = 2;
    });
    wait_for(|| !client.is_enabled("checkout", &user1(), true));
}

#[test]
fn exposures_count_repeated_checks_and_batch_by_user() {
    let fake = Fake::new();
    let client = fake.client_with(fast(), Some("build-7"));
    client.sync().unwrap();
    let ann = User {
        key: Some("user-1".into()),
        email: Some(" Ann@Example.com ".into()),
    };
    for _ in 0..3 {
        client.is_enabled("checkout", &ann, false);
    }
    client.is_enabled("checkout", &User::with_key("user-2"), false);
    client.flush().unwrap();

    let uploads = fake.get(|s| s.exposures.clone());
    let by_user = |key: &str| {
        uploads
            .iter()
            .find(|u| u["user"]["key"] == key)
            .unwrap()
            .clone()
    };
    for upload in &uploads {
        assert_eq!(upload["sdk"], "rust");
        assert_eq!(upload["sdkVersion"], crate::VERSION);
        assert_eq!(upload["appVersion"], "build-7");
        assert_eq!(upload["reporterId"], uploads[0]["reporterId"]);
    }
    let first = by_user("user-1");
    assert_eq!(
        first["user"],
        json!({"key": "user-1", "email": "ann@example.com"})
    );
    let event = &first["events"][0];
    assert_eq!(event["checkCount"], 3);
    assert_eq!(event["result"], true);
    assert_eq!(event["reason"], "rollout");
    assert_eq!(event["configurationVersion"], 1);
    let bucket = event["bucket"].as_str().unwrap();
    assert!(
        bucket.ends_with(".000Z") && bucket[17..19].parse::<u32>().unwrap() % 30 == 0,
        "{bucket}"
    );
    assert!(event["firstCheckedAt"].as_str().unwrap() >= bucket);
    assert_eq!(by_user("user-2")["events"][0]["checkCount"], 1);
}

#[test]
fn a_failed_upload_retries_the_same_batch() {
    let fake = Fake::new();
    let client = fake.client(fast());
    client.sync().unwrap();
    client.is_enabled("checkout", &user1(), false);
    fake.set(|s| s.exposure_statuses = vec![500]);
    assert!(client.flush().is_err());
    client.flush().unwrap();
    let uploads = fake.get(|s| s.exposures.clone());
    assert_eq!(uploads.len(), 2);
    assert_eq!(uploads[0]["batchId"], uploads[1]["batchId"]);
}

#[test]
fn a_refused_upload_is_dropped_and_counted_as_lost() {
    let fake = Fake::new();
    let client = fake.client(fast());
    client.sync().unwrap();
    client.is_enabled("checkout", &user1(), false);
    client.is_enabled("checkout", &user1(), false);
    fake.set(|s| s.exposure_statuses = vec![400]);
    assert!(client.flush().is_err());
    client.flush().unwrap();
    let last = fake.get(|s| s.exposures.last().cloned()).unwrap();
    assert_eq!(last["events"], json!([]));
    assert_eq!(last["droppedChecks"], 2);
}

#[test]
fn checks_that_cant_be_recorded_are_counted_as_lost() {
    let fake = Fake::new();
    let client = fake.client(fast());
    client.sync().unwrap();
    assert!(client.is_enabled(&"x".repeat(257), &user1(), true));
    // 200 emoji are 400 UTF-16 units: past the server's 256 limit.
    client.is_enabled("checkout", &User::with_key("😀".repeat(200)), true);
    client.flush().unwrap();
    let last = fake.get(|s| s.exposures.last().cloned()).unwrap();
    assert_eq!(last["droppedChecks"], 2);
    assert_eq!(last["events"], json!([]));
}

#[test]
fn close_flushes_once_and_keeps_rules_usable() {
    let fake = Fake::new();
    let client = fake.client(fast());
    client.sync().unwrap();
    client.is_enabled("checkout", &user1(), false);
    client.close().unwrap();
    client.close().unwrap();
    assert_eq!(fake.get(|s| s.exposures.len()), 1);
    assert!(client.is_enabled("checkout", &user1(), false));
    assert!(client.sync().unwrap_err().is_closed());
}

#[test]
fn dropping_the_client_flushes() {
    let fake = Fake::new();
    let client = fake.client(fast());
    client.sync().unwrap();
    client.is_enabled("checkout", &user1(), false);
    drop(client);
    assert_eq!(fake.get(|s| s.exposures.len()), 1);
}

#[test]
fn new_rejects_bad_keys_and_urls() {
    for (key, url) in [
        ("", crate::DEFAULT_URL),
        ("fbs_ bad", crate::DEFAULT_URL),
        ("fbs_test", "ftp://example.com"),
        ("fbs_test", "not a url"),
    ] {
        let options = Options {
            url: url.into(),
            ..Options::default()
        };
        assert!(
            Client::new(key, options).is_err(),
            "accepted {key:?} {url:?}"
        );
    }
}

#[test]
fn the_client_is_send_and_sync() {
    fn assert_send_sync<T: Send + Sync>() {}
    assert_send_sync::<Client>();
}
