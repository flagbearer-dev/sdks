# flagbearer for Ruby

Ruby SDK for Flagbearer. Downloads an environment's ruleset and evaluates flags
locally for each user, without making a network request for every flag check.
Requires Ruby 3.2 or later and has no runtime dependencies.

```sh
bundle add flagbearer
```

## Docs

Keys, syncing at startup, per-request checks, forking servers, shutdown and
exposures are covered at
[docs.flagbearer.dev/sdks/ruby](https://docs.flagbearer.dev/sdks/ruby).

## Development

From this directory, with Ruby 3.2 or later first on `PATH`:

```sh
bundle install
bundle exec rake test
```

`bun run test:sdk` does the same, and finds Homebrew's `ruby@3.4` even when the
macOS system Ruby comes first on `PATH`; set `RUBY_BIN` to use another Ruby.
The tests include the shared evaluation fixtures in
[`fixtures/evaluation.json`](../fixtures/evaluation.json),
which the other SDKs also run, and fork tests that use a real `Process.fork`.
Flagbearer's integration tests run this SDK against the real backend through
[`testing/servers/ruby-server.rb`](../testing/servers/ruby-server.rb).

## Releasing

Bump `VERSION` in `lib/flagbearer/version.rb`, merge, then build and push the
gem from this directory with a RubyGems account that owns `flagbearer` (MFA is
required):

```sh
gem build flagbearer.gemspec
gem push flagbearer-0.1.0.gem
```

RubyGems versions can't be reused, so bump the version for every release.
