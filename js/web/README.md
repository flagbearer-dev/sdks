# @flagbearer/web

Browser SDK for Flagbearer with synchronous flag lookups, server-sent updates, and
framework-independent subscriptions. No runtime dependencies are required.

## Install

```sh
npm install @flagbearer/web
```

## Docs

Keys, users, defaults, exposures and React are covered at
[docs.flagbearer.dev/sdks/web](https://docs.flagbearer.dev/sdks/web) and
[docs.flagbearer.dev/sdks/react](https://docs.flagbearer.dev/sdks/react).

## Develop

Refresh coordination and SDK-facing types/validators live in the internal
`@flagbearer/sdk-core` package. This SDK keeps browser-specific fetching and
`EventSource` handling, validating responses/events with those shared guards.
The browser build bundles the core and validation code without an additional
runtime dependency or validation library. It does not import application/admin
endpoint contracts.

From the repository root:

```sh
bun install
bun run --filter @flagbearer/web build
bun run --filter @flagbearer/web typecheck
bun run --filter @flagbearer/web test
```

Flagbearer's integration tests also run the built SDK in Chromium against the
real backend.

## Packaging

The build produces browser ESM JavaScript and TypeScript declarations in `dist/`.
Only `dist/` and npm's standard package metadata/README files are included in the
package; source files are not shipped. `prepack` builds the package
before packing or publishing. Bun is only build tooling, not a consumer dependency.

Releases run through the `Publish SDKs` GitHub Action, which tests the packed
packages in a clean project before publishing them.
