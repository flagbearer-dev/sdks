use std::io::{self, BufRead, BufReader, Read, Write};
use std::net::{Shutdown, TcpStream, ToSocketAddrs};
use std::sync::{Arc, Condvar, Mutex};
use std::thread;
use std::time::Duration;

use serde_json::Value;

use crate::evaluate::parse_version;
use crate::http::{backoff, status_error, Error};
use crate::log::DebugLog;
use crate::refresh::Timings;

pub(crate) const MAX_EVENT_LENGTH: usize = 65_536;

/// Reads Server-Sent Events fed in arbitrary pieces, calling `on_version` for
/// each well-formed `version` event and ignoring everything else.
pub(crate) struct EventParser<F: FnMut(i64)> {
    on_version: F,
    pending: Vec<u8>,
    buffer: String,
    event: String,
    data: String,
}

impl<F: FnMut(i64)> EventParser<F> {
    pub(crate) fn new(on_version: F) -> Self {
        Self {
            on_version,
            pending: Vec::new(),
            buffer: String::new(),
            event: "message".into(),
            data: String::new(),
        }
    }

    pub(crate) fn feed(&mut self, chunk: &[u8]) -> io::Result<()> {
        self.pending.extend_from_slice(chunk);
        // Hold back a character split across reads; replace genuinely invalid bytes.
        let complete = match std::str::from_utf8(&self.pending) {
            Ok(_) => self.pending.len(),
            Err(error) if error.error_len().is_none() => error.valid_up_to(),
            Err(_) => self.pending.len(),
        };
        let rest = self.pending.split_off(complete);
        self.buffer
            .push_str(&String::from_utf8_lossy(&std::mem::replace(
                &mut self.pending,
                rest,
            )));

        let buffer = std::mem::take(&mut self.buffer);
        let bytes = buffer.as_bytes();
        let mut start = 0;
        let mut index = 0;
        while index < bytes.len() {
            let byte = bytes[index];
            if byte != b'\r' && byte != b'\n' {
                index += 1;
                continue;
            }
            // A CR at the end might be the first half of a CRLF in the next piece.
            if byte == b'\r' && index == bytes.len() - 1 {
                break;
            }
            self.line(&buffer[start..index])?;
            if byte == b'\r' && bytes[index + 1] == b'\n' {
                index += 1;
            }
            index += 1;
            start = index;
        }
        self.buffer = buffer[start..].to_owned();
        if self.buffer.len() > MAX_EVENT_LENGTH {
            return Err(io::Error::other("Flagbearer stream line too large"));
        }
        Ok(())
    }

    fn line(&mut self, line: &str) -> io::Result<()> {
        if line.is_empty() {
            if self.event == "version" && !self.data.is_empty() {
                let data = self.data.strip_suffix('\n').unwrap_or(&self.data);
                if let Ok(value) = serde_json::from_str::<Value>(data) {
                    if let Some(version) = value.get("version").and_then(parse_version) {
                        (self.on_version)(version);
                    }
                }
            }
            self.event = "message".into();
            self.data.clear();
            return Ok(());
        }
        if line.starts_with(':') {
            return Ok(());
        }
        let (name, content) = line.split_once(':').unwrap_or((line, ""));
        let content = content.strip_prefix(' ').unwrap_or(content);
        match name {
            "event" => self.event = content.to_owned(),
            "data" => {
                self.data.push_str(content);
                self.data.push('\n');
                if self.data.len() > MAX_EVENT_LENGTH {
                    return Err(io::Error::other("Flagbearer stream event too large"));
                }
            }
            _ => {}
        }
        Ok(())
    }
}

/// Where the stream connects, split from the SDK URL.
#[derive(Clone, Debug)]
pub(crate) struct Target {
    pub(crate) tls: bool,
    pub(crate) host: String,
    pub(crate) port: u16,
    pub(crate) path: String,
}

pub(crate) struct StreamOptions {
    pub(crate) target: Target,
    pub(crate) key: String,
    pub(crate) timings: Timings,
    pub(crate) debug: DebugLog,
    pub(crate) on_version: Box<dyn Fn(i64) + Send>,
    pub(crate) on_open: Box<dyn Fn() + Send>,
    pub(crate) on_disconnect: Box<dyn Fn() + Send>,
}

/// Holds GET /v1/stream open on its own thread and reconnects with backoff.
pub(crate) struct VersionStream {
    shared: Arc<Shared>,
    thread: Mutex<Option<thread::JoinHandle<()>>>,
}

struct Shared {
    state: Mutex<State>,
    wake: Condvar,
}

struct State {
    closed: bool,
    // A second handle on the open socket, so close() can wake a blocked read.
    socket: Option<TcpStream>,
}

impl VersionStream {
    pub(crate) fn start(options: StreamOptions) -> Self {
        let shared = Arc::new(Shared {
            state: Mutex::new(State {
                closed: false,
                socket: None,
            }),
            wake: Condvar::new(),
        });
        let worker = Arc::clone(&shared);
        let thread = thread::Builder::new()
            .name("flagbearer-stream".into())
            .spawn(move || run(&worker, &options))
            .expect("spawn flagbearer stream thread");
        Self {
            shared,
            thread: Mutex::new(Some(thread)),
        }
    }

    pub(crate) fn close(&self, debug: DebugLog) {
        let mut state = self.shared.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.closed {
            return;
        }
        state.closed = true;
        debug.log("Stream closed", &[]);
        if let Some(socket) = state.socket.take() {
            let _ = socket.shutdown(Shutdown::Both);
        }
        self.shared.wake.notify_all();
    }

    #[cfg(test)]
    pub(crate) fn join(&self, timeout: Duration) -> bool {
        let deadline = std::time::Instant::now() + timeout;
        while std::time::Instant::now() < deadline {
            let finished = self
                .thread
                .lock()
                .unwrap()
                .as_ref()
                .is_none_or(thread::JoinHandle::is_finished);
            if finished {
                return true;
            }
            thread::sleep(Duration::from_millis(5));
        }
        false
    }
}

impl Drop for VersionStream {
    fn drop(&mut self) {
        // The thread exits on its own once closed; nothing waits for it here.
        let _ = self.thread.lock().map(|mut handle| handle.take());
    }
}

fn is_closed(shared: &Shared) -> bool {
    shared
        .state
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .closed
}

fn run(shared: &Shared, options: &StreamOptions) {
    let mut attempts = 0u32;
    while !is_closed(shared) {
        let failure = connect(shared, options, &mut attempts);
        if is_closed(shared) {
            return;
        }
        (options.on_disconnect)();
        if failure.as_ref().is_some_and(Error::is_permanent) {
            // Polling remains available; the next ruleset request also checks the key.
            crate::log::warn(&format!(
                "Flagbearer stream stopped; using polling: {}",
                failure.unwrap()
            ));
            return;
        }
        let mut delay = backoff(
            attempts,
            options.timings.retry_base,
            options.timings.max_retry,
        );
        attempts += 1;
        if let Some(failure) = &failure {
            delay = delay.max(failure.retry_after);
        }
        options.debug.log(
            "Stream reconnect scheduled",
            &[("delayMs", &delay.as_millis())],
        );
        let state = shared.state.lock().unwrap_or_else(|e| e.into_inner());
        let _ = shared
            .wake
            .wait_timeout_while(state, delay, |state| !state.closed);
    }
}

/// Opens one stream and reads it until it ends. Returns why it ended.
fn connect(shared: &Shared, options: &StreamOptions, attempts: &mut u32) -> Option<Error> {
    options.debug.log("Stream connecting", &[]);
    let target = &options.target;
    let timings = &options.timings;
    let socket = match open_socket(target, timings.stream_connect_timeout) {
        Ok(socket) => socket,
        Err(error) => {
            return Some(Error::retryable(format!(
                "Flagbearer stream failed: {error}"
            )))
        }
    };
    {
        let mut state = shared.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.closed {
            return None;
        }
        match socket.try_clone() {
            Ok(clone) => state.socket = Some(clone),
            Err(error) => return Some(Error::retryable(error.to_string())),
        }
    }
    let result = read_stream(socket, options, attempts);
    let mut state = shared.state.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(socket) = state.socket.take() {
        let _ = socket.shutdown(Shutdown::Both);
    }
    result.err()
}

fn open_socket(target: &Target, timeout: Duration) -> io::Result<TcpStream> {
    let mut last = io::Error::other("no addresses");
    for address in (target.host.as_str(), target.port).to_socket_addrs()? {
        match TcpStream::connect_timeout(&address, timeout) {
            Ok(socket) => {
                // Until the headers arrive, the connect timeout also bounds each read.
                socket.set_read_timeout(Some(timeout))?;
                socket.set_write_timeout(Some(timeout))?;
                return Ok(socket);
            }
            Err(error) => last = error,
        }
    }
    Err(last)
}

fn read_stream(
    socket: TcpStream,
    options: &StreamOptions,
    attempts: &mut u32,
) -> Result<(), Error> {
    let target = &options.target;
    let control = socket
        .try_clone()
        .map_err(|e| Error::retryable(e.to_string()))?;
    let mut connection: Box<dyn ReadWrite> = if target.tls {
        Box::new(tls_stream(socket, &target.host)?)
    } else {
        Box::new(socket)
    };
    let name = if target.host.contains(':') {
        format!("[{}]", target.host)
    } else {
        target.host.clone()
    };
    let host = if (target.tls && target.port == 443) || (!target.tls && target.port == 80) {
        name
    } else {
        format!("{name}:{}", target.port)
    };
    let request = format!(
        "GET {} HTTP/1.1\r\nHost: {host}\r\nAuthorization: Bearer {}\r\nAccept: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n",
        target.path, options.key
    );
    let failed = |error: io::Error| Error::retryable(format!("Flagbearer stream failed: {error}"));
    connection.write_all(request.as_bytes()).map_err(failed)?;
    connection.flush().map_err(failed)?;

    let mut reader = BufReader::new(connection);
    let head = read_head(&mut reader).map_err(failed)?;
    if !(200..300).contains(&head.status) {
        return Err(status_error(head.status, head.header("retry-after")));
    }
    let content_type = head
        .header("content-type")
        .and_then(|value| value.split(';').next())
        .map(|value| value.trim().to_ascii_lowercase());
    if content_type.as_deref() != Some("text/event-stream") {
        return Err(Error::permanent("Invalid Flagbearer stream response"));
    }
    // Heartbeats arrive every 15 seconds, so a quiet stretch past the idle
    // limit means a dead connection. The socket timeout applies to every read.
    control
        .set_read_timeout(Some(options.timings.stream_idle_timeout))
        .map_err(failed)?;
    (options.on_open)();

    let chunked = head
        .header("transfer-encoding")
        .is_some_and(|value| value.to_ascii_lowercase().contains("chunked"));
    let mut body: Box<dyn Read> = if chunked {
        Box::new(ChunkedReader::new(reader))
    } else {
        Box::new(reader)
    };
    let mut parser = EventParser::new(|version| (options.on_version)(version));
    let mut buffer = [0u8; 16_384];
    loop {
        let n = body.read(&mut buffer).map_err(failed)?;
        if n == 0 {
            return Ok(());
        }
        *attempts = 0;
        parser.feed(&buffer[..n]).map_err(failed)?;
    }
}

trait ReadWrite: Read + Write + Send {}
impl<T: Read + Write + Send> ReadWrite for T {}

fn tls_stream(
    socket: TcpStream,
    host: &str,
) -> Result<rustls::StreamOwned<rustls::ClientConnection, TcpStream>, Error> {
    let config = tls_config();
    let name = rustls::pki_types::ServerName::try_from(host.to_owned())
        .map_err(|e| Error::permanent(format!("Invalid Flagbearer host: {e}")))?;
    let connection = rustls::ClientConnection::new(config, name)
        .map_err(|e| Error::retryable(format!("Flagbearer stream failed: {e}")))?;
    Ok(rustls::StreamOwned::new(connection, socket))
}

fn tls_config() -> Arc<rustls::ClientConfig> {
    static CONFIG: std::sync::OnceLock<Arc<rustls::ClientConfig>> = std::sync::OnceLock::new();
    Arc::clone(CONFIG.get_or_init(|| {
        let roots = rustls::RootCertStore {
            roots: webpki_roots::TLS_SERVER_ROOTS.to_vec(),
        };
        let config = rustls::ClientConfig::builder_with_provider(Arc::new(
            rustls::crypto::ring::default_provider(),
        ))
        .with_safe_default_protocol_versions()
        .expect("rustls supports its default protocol versions")
        .with_root_certificates(roots)
        .with_no_client_auth();
        Arc::new(config)
    }))
}

pub(crate) struct Head {
    pub(crate) status: u16,
    headers: Vec<(String, String)>,
}

impl Head {
    pub(crate) fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(key, _)| key.eq_ignore_ascii_case(name))
            .map(|(_, value)| value.as_str())
    }
}

pub(crate) fn read_head(reader: &mut impl BufRead) -> io::Result<Head> {
    let status_line = read_line(reader)?;
    let status = status_line
        .split_whitespace()
        .nth(1)
        .and_then(|code| code.parse().ok())
        .ok_or_else(|| io::Error::other("invalid HTTP status line"))?;
    let mut headers = Vec::new();
    loop {
        let line = read_line(reader)?;
        if line.is_empty() {
            return Ok(Head { status, headers });
        }
        if headers.len() > 100 {
            return Err(io::Error::other("too many HTTP headers"));
        }
        if let Some((name, value)) = line.split_once(':') {
            headers.push((name.trim().to_owned(), value.trim().to_owned()));
        }
    }
}

fn read_line(reader: &mut impl BufRead) -> io::Result<String> {
    let mut line = Vec::new();
    let read = reader.take(8192).read_until(b'\n', &mut line)?;
    if read == 0 {
        return Err(io::ErrorKind::UnexpectedEof.into());
    }
    if line.last() != Some(&b'\n') {
        return Err(io::Error::other("HTTP header line too long"));
    }
    while matches!(line.last(), Some(b'\n' | b'\r')) {
        line.pop();
    }
    String::from_utf8(line).map_err(io::Error::other)
}

/// Decodes `Transfer-Encoding: chunked`, handing out each chunk's bytes as they arrive.
pub(crate) struct ChunkedReader<R: BufRead> {
    inner: R,
    remaining: usize,
    done: bool,
}

impl<R: BufRead> ChunkedReader<R> {
    pub(crate) fn new(inner: R) -> Self {
        Self {
            inner,
            remaining: 0,
            done: false,
        }
    }
}

impl<R: BufRead> Read for ChunkedReader<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if self.done || buf.is_empty() {
            return Ok(0);
        }
        if self.remaining == 0 {
            let line = read_line(&mut self.inner)?;
            let size = line.split(';').next().unwrap_or("").trim();
            self.remaining = usize::from_str_radix(size, 16)
                .map_err(|_| io::Error::other("invalid chunk size"))?;
            if self.remaining == 0 {
                self.done = true;
                return Ok(0);
            }
        }
        let limit = buf.len().min(self.remaining);
        let n = self.inner.read(&mut buf[..limit])?;
        if n == 0 {
            return Err(io::ErrorKind::UnexpectedEof.into());
        }
        self.remaining -= n;
        if self.remaining == 0 {
            // Each chunk ends with CRLF.
            let end = read_line(&mut self.inner)?;
            if !end.is_empty() {
                return Err(io::Error::other("invalid chunk ending"));
            }
        }
        Ok(n)
    }
}
