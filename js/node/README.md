# @flagbearer/node

Node.js SDK for Flagbearer. Downloads an environment's ruleset and evaluates flags
locally for each user, without making a network request for every flag check.
Requires Node.js 22 or later.

## Install

```sh
npm install @flagbearer/node
```

## Docs

Keys, syncing at startup, per-request checks, shutdown and exposures are covered
at [docs.flagbearer.dev/sdks/node](https://docs.flagbearer.dev/sdks/node).

## Development

From the repository root:

```sh
bun run --filter @flagbearer/node typecheck
bun run --filter @flagbearer/node build
```

The build produces a Node-targeted ES module and self-contained TypeScript
declarations in `dist/`, bundling the internal refresh core, plain SDK validators,
and evaluator directly from their workspace sources. It does not import the
application's `shared` package or admin endpoint contracts. Internal packages
are build dependencies, not extra packages SDK consumers must install. The SDK
does not include Valibot or another validation library.
Bun is used as the build tool; the output targets Node.js.

## Integration tests

Flagbearer's integration tests run this SDK against the real backend through
[`testing/servers/node-server.ts`](../../testing/servers/node-server.ts).
