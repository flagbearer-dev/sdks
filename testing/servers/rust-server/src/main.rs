//! The Rust SDK behind the same GET /flags and GET /flush routes as node-server.ts.

use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::Arc;
use std::thread;

use flagbearer::{Client, Options, User};
use serde_json::json;
use signal_hook::consts::{SIGINT, SIGTERM};
use signal_hook::iterator::Signals;

fn main() {
    let key = std::env::var("FLAGBEARER_SERVER_KEY")
        .expect("The integration fixture requires FLAGBEARER_SERVER_KEY.");
    let url = std::env::var("FLAGBEARER_URL")
        .ok()
        .filter(|url| !url.is_empty())
        .unwrap_or_else(|| flagbearer::DEFAULT_URL.into());
    let port = std::env::var("PORT").unwrap_or_else(|_| "0".into());

    // One client for the whole server, not one per HTTP request.
    let flags = Arc::new(
        Client::new(
            key,
            Options {
                url,
                debug: true,
                ..Options::default()
            },
        )
        .expect("valid fixture options"),
    );
    match flags.sync() {
        Ok(()) => println!("Initial Flagbearer ruleset loaded."),
        Err(error) => {
            println!("Initial sync failed; serving defaults while the SDK recovers. {error}")
        }
    }

    let listener = TcpListener::bind(format!("127.0.0.1:{port}")).expect("bind fixture port");
    let address = listener.local_addr().expect("fixture address");
    {
        let flags = Arc::clone(&flags);
        thread::spawn(move || {
            for socket in listener.incoming().flatten() {
                let flags = Arc::clone(&flags);
                thread::spawn(move || handle(socket, &flags));
            }
        });
    }
    println!("Rust SDK fixture listening on http://{address}");
    let _ = std::io::stdout().flush();

    let mut signals = Signals::new([SIGTERM, SIGINT]).expect("signal handler");
    if signals.forever().next().is_some() {
        let _ = flags.close();
        std::process::exit(0);
    }
}

fn handle(mut socket: TcpStream, flags: &Client) {
    let mut reader = BufReader::new(match socket.try_clone() {
        Ok(clone) => clone,
        Err(_) => return,
    });
    let mut request_line = String::new();
    if reader.read_line(&mut request_line).is_err() {
        return;
    }
    // Headers aren't needed; read past them.
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line).map_or(true, |n| n == 0) || line.trim().is_empty() {
            break;
        }
    }
    let mut parts = request_line.split_whitespace();
    let (method, target) = (parts.next().unwrap_or(""), parts.next().unwrap_or(""));
    let (path, query) = target.split_once('?').unwrap_or((target, ""));
    let param = |name: &str| {
        query.split('&').find_map(|pair| {
            let (key, value) = pair.split_once('=').unwrap_or((pair, ""));
            (decode(key) == name).then(|| decode(value))
        })
    };

    let (status, body) = match (method, path) {
        // Uploads pending exposures now, so a test sees whether the server took them.
        ("GET", "/flush") => match flags.flush() {
            Ok(()) => (200, json!({"flushed": true})),
            Err(error) => (502, json!({"error": error.to_string()})),
        },
        ("GET", "/flags") => {
            let flag = param("flag").filter(|flag| !flag.is_empty());
            let fallback = param("default").unwrap_or_else(|| "false".into());
            match flag {
                Some(flag) if fallback == "true" || fallback == "false" => {
                    let user = User {
                        key: param("userKey"),
                        email: param("email"),
                    };
                    // Local evaluation: this request does not call Flagbearer's API.
                    let enabled = flags.is_enabled(&flag, &user, fallback == "true");
                    (
                        200,
                        json!({"flag": flag, "user": {"key": user.key, "email": user.email}, "enabled": enabled}),
                    )
                }
                _ => (
                    400,
                    json!({"error": "Provide flag, and optionally default=true or default=false."}),
                ),
            }
        }
        ("GET", _) => (404, json!({"error": "Not found."})),
        _ => (405, json!({"error": "Use GET."})),
    };
    let body = body.to_string();
    let _ = write!(
        socket,
        "HTTP/1.1 {status} X\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
}

/// Decodes a query component: `+` is a space, `%XX` a byte.
fn decode(text: &str) -> String {
    let hex = |byte: u8| (byte as char).to_digit(16).map(|digit| digit as u8);
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        let escaped = (bytes[index] == b'%' && index + 2 < bytes.len())
            .then(|| Some(hex(bytes[index + 1])? * 16 + hex(bytes[index + 2])?))
            .flatten();
        match (bytes[index], escaped) {
            (_, Some(byte)) => {
                out.push(byte);
                index += 3;
            }
            (b'+', None) => {
                out.push(b' ');
                index += 1;
            }
            (byte, None) => {
                out.push(byte);
                index += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}
