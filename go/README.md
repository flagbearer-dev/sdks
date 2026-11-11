# flagbearer for Go

Go SDK for Flagbearer. Downloads an environment's ruleset and evaluates flags
locally for each user, without making a network request for every flag check.
Requires Go 1.23 or later and has no dependencies outside the standard library.

## Docs

Keys, syncing at startup, per-request checks, shutdown and exposures are covered
at [docs.flagbearer.dev/sdks/go](https://docs.flagbearer.dev/sdks/go).

## Development

From this directory:

```sh
go test -race ./...
go vet ./... && gofmt -l .
```

The tests include the shared evaluation fixtures in
[`fixtures/evaluation.json`](../fixtures/evaluation.json),
which the Node and Python SDKs also run. Flagbearer's integration tests run this SDK against the real backend through
[`testing/servers/go-server`](../testing/servers/go-server).

## Releasing

Go modules are published by git tag, not uploaded. Bump `Version` in
`version.go`, merge, then tag the commit with the module's directory prefix:

```sh
git tag go/v1.0.0
git push origin go/v1.0.0
```

`go get` fetches the module straight from the repository, so the repository
must be public for anyone else to install it.
