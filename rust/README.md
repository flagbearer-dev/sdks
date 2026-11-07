# flagbearer for Rust

Rust SDK for Flagbearer. Downloads an environment's ruleset and evaluates flags
locally for each user, without making a network request for every flag check.
Background work runs on its own threads, so it needs no async runtime. Requires
Rust 1.85 or later.

## Docs

Keys, syncing at startup, per-request checks, shutdown and exposures are covered
at [docs.flagbearer.dev/sdks/rust](https://docs.flagbearer.dev/sdks/rust).

## Development

From this directory:

```sh
cargo test
cargo clippy --all-targets -- -D warnings && cargo fmt --check
```

The tests include the shared evaluation fixtures in
[`fixtures/evaluation.json`](../fixtures/evaluation.json),
which every server SDK also runs. Flagbearer's integration tests run this SDK against the real backend through
[`testing/servers/rust-server`](../testing/servers/rust-server).

## Releasing

Releases go to [crates.io](https://crates.io/crates/flagbearer), where versions
can't be replaced once published. Bump `version` in `Cargo.toml`, merge, then
from this directory:

```sh
cargo publish --dry-run
cargo publish
```

crates.io supports trusted publishing from GitHub Actions, so a release
workflow needs no stored token once the crate exists.
