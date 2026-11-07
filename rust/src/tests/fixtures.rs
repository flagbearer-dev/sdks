use serde_json::Value;

use crate::evaluate::{bucket, evaluate, parse_ruleset, Reason};
use crate::User;

fn fixtures() -> Value {
    let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../fixtures/evaluation.json");
    serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

fn user(value: &Value) -> User {
    User {
        key: value.get("key").and_then(Value::as_str).map(str::to_owned),
        email: value
            .get("email")
            .and_then(Value::as_str)
            .map(str::to_owned),
    }
}

#[test]
fn fixture_buckets() {
    for item in fixtures()["buckets"].as_array().unwrap() {
        let (flag, user_key) = (
            item["flag"].as_str().unwrap(),
            item["user"].as_str().unwrap(),
        );
        assert_eq!(
            bucket(flag, user_key),
            item["bucket"].as_i64().unwrap(),
            "{flag}:{user_key:.20}"
        );
    }
}

#[test]
fn fixture_cases() {
    for item in fixtures()["cases"].as_array().unwrap() {
        let name = item["name"].as_str().unwrap();
        let ruleset = (!item["flag"].is_null()).then(|| {
            let body = format!(
                r#"{{"environmentId":"1","version":7,"flags":[{}]}}"#,
                item["flag"]
            );
            parse_ruleset(body.as_bytes()).expect(name)
        });
        let rule = ruleset.as_ref().and_then(|r| r.flags.values().next());
        let got = evaluate(
            rule,
            &user(&item["user"]),
            Some(7),
            item["default"].as_bool().unwrap(),
        );
        let reason = match item["expect"]["reason"].as_str().unwrap() {
            "off" => Reason::Off,
            "tester" => Reason::Tester,
            "rollout" => Reason::Rollout,
            _ => Reason::Fallback,
        };
        assert_eq!(
            (got.value, got.reason, got.version),
            (item["expect"]["value"].as_bool().unwrap(), reason, Some(7)),
            "{name}"
        );
    }
}

#[test]
fn invalid_rules_reject_the_whole_ruleset() {
    for flag in [
        r#"{"key":"a","enabled":true,"rolloutBps":10001,"overrideEmails":[]}"#,
        r#"{"key":"a","enabled":true,"rolloutBps":50.5,"overrideEmails":[]}"#,
        r#"{"key":"a","enabled":true,"rolloutBps":true,"overrideEmails":[]}"#,
        r#"{"key":"a","enabled":1,"rolloutBps":0,"overrideEmails":[]}"#,
        r#"{"key":"a","enabled":true,"rolloutBps":0,"overrideEmails":[1]}"#,
        r#"{"key":"a","enabled":true,"rolloutBps":0}"#,
    ] {
        let body = format!(
            r#"{{"environmentId":"1","version":1,"flags":[{{"key":"b","enabled":true,"rolloutBps":0,"overrideEmails":[]}},{flag}]}}"#
        );
        assert!(parse_ruleset(body.as_bytes()).is_none(), "accepted {flag}");
    }
    assert!(parse_ruleset(br#"{"environmentId":"1","version":1,"flags":[]} trailing"#).is_none());
    assert!(parse_ruleset(br#"{"environmentId":"1","version":-1,"flags":[]}"#).is_none());
    assert!(parse_ruleset(br#"{"environmentId":"1","version":1.0,"flags":[]}"#).is_some());
}

#[test]
fn emails_are_trimmed_like_javascript() {
    use crate::evaluate::js_trim;
    assert_eq!(js_trim("\u{feff} a@b.c \u{3000}"), "a@b.c");
    // JavaScript's trim keeps U+0085, though Unicode calls it whitespace.
    assert_eq!(js_trim("\u{85}a@b.c"), "\u{85}a@b.c");
}

#[test]
fn timestamps_and_http_dates() {
    use crate::http::{iso_millis, retry_after};
    use std::time::{Duration, UNIX_EPOCH};
    let at = UNIX_EPOCH + Duration::from_millis(1_783_455_000_123);
    assert_eq!(iso_millis(at), "2026-07-07T20:10:00.123Z");
    assert_eq!(
        iso_millis(UNIX_EPOCH + Duration::from_secs(951_782_400)),
        "2000-02-29T00:00:00.000Z"
    );
    let now = UNIX_EPOCH + Duration::from_secs(784_111_777);
    assert_eq!(
        retry_after(Some("Sun, 06 Nov 1994 08:49:47 GMT"), now),
        Duration::from_secs(10)
    );
    assert_eq!(retry_after(Some("2.5"), now), Duration::from_millis(2500));
    assert_eq!(retry_after(Some("-1"), now), Duration::ZERO);
    assert_eq!(retry_after(Some("soon"), now), Duration::ZERO);
}
