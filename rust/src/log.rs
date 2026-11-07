use std::fmt::Display;
use std::io::Write;

/// Diagnostics for `debug: true`, written to stderr. Never includes keys, user
/// data, flag names or rules.
#[derive(Clone, Copy, Debug)]
pub(crate) struct DebugLog(pub(crate) bool);

impl DebugLog {
    pub(crate) fn log(self, message: &str, fields: &[(&str, &dyn Display)]) {
        if !self.0 {
            return;
        }
        let line = if fields.is_empty() {
            format!("[Flagbearer] {message}\n")
        } else {
            // Same shape as the Node SDK's console.debug output: { version: 42 }.
            let parts: Vec<String> = fields
                .iter()
                .map(|(name, value)| format!("{name}: {value}"))
                .collect();
            format!("[Flagbearer] {message} {{ {} }}\n", parts.join(", "))
        };
        let _ = std::io::stderr().write_all(line.as_bytes());
    }
}

/// Formats a missing number the way the Node SDK logs it.
pub(crate) struct Optional(pub(crate) Option<i64>);

impl Display for Optional {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self.0 {
            Some(value) => write!(f, "{value}"),
            None => f.write_str("undefined"),
        }
    }
}

/// Warnings that are always shown, like a background refresh failing.
pub(crate) fn warn(message: &str) {
    let _ = writeln!(std::io::stderr(), "{message}");
}
