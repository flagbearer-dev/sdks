use std::collections::hash_map::RandomState;
use std::fmt;
use std::hash::{BuildHasher, Hasher};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// A failed request or an unusable response.
///
/// Retryable failures back off and try again in the background. Permanent ones,
/// like a revoked key or an invalid ruleset, stop automatic refreshes until
/// [`crate::Client::sync`] is called.
#[derive(Clone, Debug)]
pub struct Error {
    message: String,
    kind: ErrorKind,
    pub(crate) retry_after: Duration,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ErrorKind {
    Retryable,
    Permanent,
    Closed,
    Timeout,
    InvalidOptions,
}

impl Error {
    pub(crate) fn retryable(message: impl Into<String>) -> Self {
        Self::new(message, ErrorKind::Retryable)
    }

    pub(crate) fn permanent(message: impl Into<String>) -> Self {
        Self::new(message, ErrorKind::Permanent)
    }

    pub(crate) fn closed() -> Self {
        Self::new("Flagbearer client is closed", ErrorKind::Closed)
    }

    pub(crate) fn timeout() -> Self {
        Self::new("Flagbearer sync timed out", ErrorKind::Timeout)
    }

    pub(crate) fn invalid_options(message: impl Into<String>) -> Self {
        Self::new(message, ErrorKind::InvalidOptions)
    }

    fn new(message: impl Into<String>, kind: ErrorKind) -> Self {
        Self {
            message: message.into(),
            kind,
            retry_after: Duration::ZERO,
        }
    }

    /// A failure retrying won't fix, such as a revoked key or an invalid ruleset.
    pub fn is_permanent(&self) -> bool {
        self.kind == ErrorKind::Permanent
    }

    /// Returned by `sync` after `close`.
    pub fn is_closed(&self) -> bool {
        self.kind == ErrorKind::Closed
    }

    /// Returned by `sync_timeout` when the timeout passed first.
    pub fn is_timeout(&self) -> bool {
        self.kind == ErrorKind::Timeout
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for Error {}

/// Classifies a non-2xx status: 408, 429 and 5xx retry; a 3xx lands here too,
/// since requests never follow redirects.
pub(crate) fn status_error(status: u16, retry_after_header: Option<&str>) -> Error {
    let message = format!("Flagbearer request failed: {status}");
    let mut error = if status == 408 || status == 429 || status >= 500 {
        Error::retryable(message)
    } else {
        Error::permanent(message)
    };
    error.retry_after = retry_after(retry_after_header, SystemTime::now());
    error
}

/// Seconds to wait from a Retry-After header, given in seconds or as an HTTP date.
pub(crate) fn retry_after(header: Option<&str>, now: SystemTime) -> Duration {
    let Some(header) = header.map(str::trim) else {
        return Duration::ZERO;
    };
    if let Ok(seconds) = header.parse::<f64>() {
        return if seconds.is_finite() && seconds > 0.0 {
            Duration::from_secs_f64(seconds.min(1e9))
        } else {
            Duration::ZERO
        };
    }
    parse_http_date(header)
        .and_then(|at| at.duration_since(now).ok())
        .unwrap_or(Duration::ZERO)
}

/// Parses the IMF-fixdate form, like `Sun, 06 Nov 1994 08:49:37 GMT`.
fn parse_http_date(text: &str) -> Option<SystemTime> {
    let parts: Vec<&str> = text.split_whitespace().collect();
    let [_, day, month, year, time, "GMT"] = parts.as_slice() else {
        return None;
    };
    let day: i64 = day.parse().ok()?;
    let month = [
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    ]
    .iter()
    .position(|name| name == month)? as i64
        + 1;
    let year: i64 = year.parse().ok()?;
    let mut clock = time.split(':').map(|part| part.parse::<i64>().ok());
    let (hour, minute, second) = (clock.next()??, clock.next()??, clock.next()??);
    // Days from the civil date, per Howard Hinnant's algorithm.
    let y = if month <= 2 { year - 1 } else { year };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let mp = (month + 9) % 12;
    let doy = (153 * mp + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146_097 + doe - 719_468;
    let seconds = days * 86_400 + hour * 3600 + minute * 60 + second;
    u64::try_from(seconds)
        .ok()
        .map(|s| UNIX_EPOCH + Duration::from_secs(s))
}

/// A random fraction in [0, 1). Not cryptographic; it only spreads timers out.
pub(crate) fn random_fraction() -> f64 {
    (random_u64() >> 11) as f64 / (1u64 << 53) as f64
}

fn random_u64() -> u64 {
    // RandomState is seeded from the OS once per thread and differs per call.
    let mut hasher = RandomState::new().build_hasher();
    hasher.write_u128(
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |d| d.as_nanos()),
    );
    hasher.finish()
}

pub(crate) fn backoff(attempt: u32, base: Duration, cap: Duration) -> Duration {
    let delay = cap.min(base * (1u32 << attempt.min(5)));
    cap.min(delay.mul_f64(0.8 + random_fraction() * 0.4))
}

pub(crate) fn jitter(interval: Duration, spread: f64) -> Duration {
    interval.mul_f64(1.0 - spread + random_fraction() * 2.0 * spread)
}

/// A version 4 UUID, for batch and reporter IDs.
pub(crate) fn new_uuid() -> String {
    let mut bytes = [0u8; 16];
    bytes[..8].copy_from_slice(&random_u64().to_le_bytes());
    bytes[8..].copy_from_slice(&random_u64().to_le_bytes());
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    let hex: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
    format!(
        "{}-{}-{}-{}-{}",
        &hex[0..8],
        &hex[8..12],
        &hex[12..16],
        &hex[16..20],
        &hex[20..32]
    )
}

/// UTF-16 code units, as JavaScript and the server's limits count them.
pub(crate) fn js_length(text: &str) -> usize {
    text.encode_utf16().count()
}

/// The ISO 8601 UTC time with milliseconds, like `2026-11-06T18:30:00.000Z`.
pub(crate) fn iso_millis(at: SystemTime) -> String {
    let millis = at
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_millis() as i64);
    let days = millis.div_euclid(86_400_000);
    let ms_of_day = millis.rem_euclid(86_400_000);
    // Civil date from days, per Howard Hinnant's algorithm.
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}.{:03}Z",
        ms_of_day / 3_600_000,
        ms_of_day / 60_000 % 60,
        ms_of_day / 1000 % 60,
        ms_of_day % 1000
    )
}
