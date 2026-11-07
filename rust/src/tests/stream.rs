use std::io::{BufReader, Read};

use crate::stream::{ChunkedReader, EventParser, MAX_EVENT_LENGTH};

fn parse(pieces: &[&[u8]]) -> (Vec<i64>, bool) {
    let mut versions = Vec::new();
    let mut failed = false;
    {
        let mut parser = EventParser::new(|version| versions.push(version));
        for piece in pieces {
            if parser.feed(piece).is_err() {
                failed = true;
                break;
            }
        }
    }
    (versions, failed)
}

#[test]
fn reads_version_events_and_ignores_others() {
    let (got, _) = parse(&[
        b"retry: 9000\n\n",
        b": comment\n",
        b"event: version\ndata: {\"version\":4}\n\n",
        b"event: ping\ndata: {}\n\n",
        b"data: {\"version\":99}\n\n",
    ]);
    assert_eq!(got, vec![4]);
}

#[test]
fn any_line_ending_split_anywhere() {
    for ending in ["\n", "\r\n", "\r"] {
        let text = format!("event: version{ending}data: {{\"version\":7}}{ending}{ending}");
        for cut in 0..=text.len() {
            // A trailing CR might be half a CRLF, so it's read once the next byte arrives.
            let (got, _) = parse(&[
                &text.as_bytes()[..cut],
                &text.as_bytes()[cut..],
                b": heartbeat\n",
            ]);
            assert_eq!(got, vec![7], "ending {ending:?} cut {cut}");
        }
    }
}

#[test]
fn multi_byte_characters_split_across_reads() {
    let text = "event: version\ndata: {\"version\":5,\"note\":\"ü😀\"}\n\n".as_bytes();
    for cut in 0..=text.len() {
        let (got, _) = parse(&[&text[..cut], &text[cut..]]);
        assert_eq!(got, vec![5], "cut {cut}");
    }
}

#[test]
fn multi_line_data_joins_with_newlines() {
    let (got, _) = parse(&[b"event: version\ndata: {\"version\":\ndata: 5}\n\n"]);
    assert_eq!(got, vec![5]);
}

#[test]
fn malformed_versions_are_ignored() {
    for data in [
        "nope",
        r#"{"version":-1}"#,
        r#"{"version":true}"#,
        r#"{"version":1.5}"#,
        "[]",
    ] {
        let event = format!("event: version\ndata: {data}\n\n");
        let (got, failed) = parse(&[event.as_bytes()]);
        assert!(got.is_empty() && !failed, "{data}");
    }
}

#[test]
fn oversized_events_fail_the_connection() {
    let data = format!("data: {}\n", "x".repeat(MAX_EVENT_LENGTH));
    assert!(parse(&[data.as_bytes()]).1);
    let line = "x".repeat(MAX_EVENT_LENGTH + 1);
    assert!(parse(&[line.as_bytes()]).1);
}

/// Hands out at most `step` bytes per read, like a slow network.
struct Trickle<'a> {
    data: &'a [u8],
    step: usize,
}

impl Read for Trickle<'_> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        let n = self.step.min(buf.len()).min(self.data.len());
        buf[..n].copy_from_slice(&self.data[..n]);
        self.data = &self.data[n..];
        Ok(n)
    }
}

#[test]
fn chunked_bodies_decode_however_they_arrive() {
    let wire = b"5\r\nhello\r\n7;ext=1\r\n, world\r\n0\r\n\r\n";
    for step in 1..wire.len() {
        let reader = BufReader::with_capacity(step, Trickle { data: wire, step });
        let mut body = String::new();
        ChunkedReader::new(reader)
            .read_to_string(&mut body)
            .unwrap();
        assert_eq!(body, "hello, world", "step {step}");
    }
    let mut bad = ChunkedReader::new(BufReader::new(&b"zz\r\nhello\r\n"[..]));
    assert!(bad.read(&mut [0; 8]).is_err());
}
