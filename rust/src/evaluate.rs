use std::collections::HashMap;

use serde_json::Value;
use sha2::{Digest, Sha256};

use crate::User;

pub(crate) const MAX_SAFE_INTEGER: i64 = (1 << 53) - 1;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub(crate) enum Reason {
    Off,
    Tester,
    Rollout,
    Fallback,
}

impl Reason {
    pub(crate) fn as_str(self) -> &'static str {
        match self {
            Reason::Off => "off",
            Reason::Tester => "tester",
            Reason::Rollout => "rollout",
            Reason::Fallback => "fallback",
        }
    }
}

#[derive(Clone, Debug)]
pub(crate) struct FlagRule {
    pub(crate) key: String,
    pub(crate) enabled: bool,
    pub(crate) rollout_bps: i64,
    pub(crate) override_emails: Vec<String>,
}

#[derive(Debug)]
pub(crate) struct Ruleset {
    pub(crate) environment_id: String,
    pub(crate) version: i64,
    pub(crate) flags: HashMap<String, FlagRule>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Evaluation {
    pub(crate) value: bool,
    pub(crate) reason: Reason,
    pub(crate) version: Option<i64>,
}

/// A user's place in a flag's rollout, 0–9999. Below `rollout_bps` means on.
pub(crate) fn bucket(flag_key: &str, user_key: &str) -> i64 {
    let digest = Sha256::digest(format!("{flag_key}:{user_key}").as_bytes());
    // The digest read as a big-endian integer, mod 10000, one byte at a time.
    digest.iter().fold(0i64, |remainder, byte| {
        (remainder * 256 + i64::from(*byte)) % 10000
    })
}

/// Trims the whitespace JavaScript's `String.prototype.trim` removes.
pub(crate) fn js_trim(text: &str) -> &str {
    // Unicode White_Space includes U+0085, which JavaScript doesn't trim; it
    // trims U+FEFF, which isn't White_Space.
    text.trim_matches(|c: char| (c.is_whitespace() && c != '\u{85}') || c == '\u{feff}')
}

/// Lowercases like JavaScript's `toLowerCase`, which maps Σ by context the
/// way Rust's `to_lowercase` does.
pub(crate) fn normalize_email(text: &str) -> String {
    js_trim(text).to_lowercase()
}

pub(crate) fn evaluate(
    flag: Option<&FlagRule>,
    user: &User,
    version: Option<i64>,
    default: bool,
) -> Evaluation {
    let result = |value, reason| Evaluation {
        value,
        reason,
        version,
    };
    let Some(flag) = flag else {
        return result(default, Reason::Fallback);
    };
    if !flag.enabled {
        return result(false, Reason::Off);
    }
    if let Some(email) = user.email.as_deref() {
        let email = normalize_email(email);
        if !email.is_empty()
            && flag
                .override_emails
                .iter()
                .any(|item| normalize_email(item) == email)
        {
            return result(true, Reason::Tester);
        }
    }
    match user.key.as_deref().filter(|key| !key.is_empty()) {
        None if flag.rollout_bps != 0 && flag.rollout_bps != 10000 => {
            result(default, Reason::Fallback)
        }
        None => result(flag.rollout_bps == 10000, Reason::Rollout),
        Some(key) => result(bucket(&flag.key, key) < flag.rollout_bps, Reason::Rollout),
    }
}

/// A JSON number with no fractional part, as JavaScript accepts it. Booleans
/// are never numbers.
pub(crate) fn integer(value: &Value) -> Option<i64> {
    let number = value.as_number()?;
    if let Some(n) = number.as_i64() {
        return Some(n);
    }
    let f = number.as_f64()?;
    (f.fract() == 0.0 && f.abs() <= MAX_SAFE_INTEGER as f64).then_some(f as i64)
}

pub(crate) fn parse_version(value: &Value) -> Option<i64> {
    integer(value).filter(|n| (0..=MAX_SAFE_INTEGER).contains(n))
}

/// Reads a GET /v1/ruleset body, rejecting it whole if any part is invalid.
pub(crate) fn parse_ruleset(body: &[u8]) -> Option<Ruleset> {
    // serde_json rejects anything but whitespace after the value, like JSON.parse.
    let value: Value = serde_json::from_slice(body).ok()?;
    let object = value.as_object()?;
    let environment_id = object.get("environmentId")?.as_str()?.to_owned();
    let version = parse_version(object.get("version")?)?;
    let mut flags = HashMap::new();
    for item in object.get("flags")?.as_array()? {
        let rule = parse_flag_rule(item)?;
        flags.insert(rule.key.clone(), rule);
    }
    Some(Ruleset {
        environment_id,
        version,
        flags,
    })
}

fn parse_flag_rule(value: &Value) -> Option<FlagRule> {
    let object = value.as_object()?;
    let key = object.get("key")?.as_str()?.to_owned();
    let enabled = object.get("enabled")?.as_bool()?;
    let rollout_bps = integer(object.get("rolloutBps")?).filter(|n| (0..=10000).contains(n))?;
    let override_emails = object
        .get("overrideEmails")?
        .as_array()?
        .iter()
        .map(|email| email.as_str().map(str::to_owned))
        .collect::<Option<Vec<_>>>()?;
    Some(FlagRule {
        key,
        enabled,
        rollout_bps,
        override_emails,
    })
}
