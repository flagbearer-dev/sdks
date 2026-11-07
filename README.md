# Flagbearer SDKs

Official SDKs for [Flagbearer](https://flagbearer.dev) feature flags. Server SDKs
download an environment's rules once and check flags locally, so a flag check
never waits on the network. Docs for each are at
[docs.flagbearer.dev](https://docs.flagbearer.dev).

| SDK | Directory | Package |
| --- | --- | --- |
| Browser | [`js/web`](js/web) | `@flagbearer/web` (npm) |
| Node.js | [`js/node`](js/node) | `@flagbearer/node` (npm) |
| Python | [`python`](python) | `flagbearer` (PyPI) |
| Go | [`go`](go) | `github.com/flagbearer-dev/sdks/go` |
| Java and Kotlin | [`java`](java) | `dev.flagbearer:flagbearer` (Maven Central) |
| .NET | [`dotnet`](dotnet) | `Flagbearer` (NuGet) |
| Ruby | [`ruby`](ruby) | `flagbearer` (RubyGems) |
| Rust | [`rust`](rust) | `flagbearer` (crates.io) |
| C++ | [`cpp`](cpp) | CMake, from a git tag |
| PHP | [`php`](php) | `flagbearer/flagbearer` (Packagist) |

Only the Browser and Node.js SDKs are published so far. The others are on their
way to their registries.

## How the SDKs stay consistent

- [`fixtures/evaluation.json`](fixtures/evaluation.json) holds rollout buckets
  and evaluation cases that every SDK's tests must reproduce exactly.
- [`testing/servers/`](testing/servers) has a small HTTP server per SDK with the
  same routes (`GET /flags`, `GET /flush`), which Flagbearer's integration tests
  run against the real backend.
- [`js/core`](js/core) and [`js/evaluator`](js/evaluator) are internal packages
  the JavaScript SDKs bundle.

Each SDK's README covers how to build, test and release it.

## License

MIT
