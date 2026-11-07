# @flagbearer/sdk-core

Internal SDK data contracts and TypeScript refresh coordination shared by
Flagbearer SDKs. It contains no admin endpoints, database code, or local flag
evaluation. SDK builds bundle this package; consumers don't install it separately.

`src/types.ts` owns rulesets, flag rules, evaluation requests/results, version
events, and evaluation-user types. `src/validation.ts` contains small dependency-free
type guards such as `isRuleset`, `isEvaluationResponse`, and `isVersionEvent`.
They validate nested fields and numeric ranges, allow unknown fields for forward
compatibility, and do not transform inputs.

Flagbearer's server wraps these guards in its own schemas. The
evaluator reuses the plain types; its hashing/evaluation code stays in
`@flagbearer/evaluator`. Internal workspace exports point to source, so working on the SDKs
doesn't require building this package first. This package has no external
runtime dependencies.

The coordinator itself remains independent of those payload shapes and stream
transports: it works with any validated snapshot carrying a `version`.

Optional `debug: true` enables metadata-only `console.debug` diagnostics, including
received stream versions and applied snapshot versions. No payloads, keys, or
request/user details are logged. Logging errors are isolated from refresh logic.

```ts
import { createRefreshCoordinator } from "@flagbearer/sdk-core";

const refresh = createRefreshCoordinator({
  fetchSnapshot: (signal) => fetchRuleset(signal), // Fetch and validate the payload.
  applySnapshot: (snapshot) => {
    currentRuleset = snapshot;
  },
  background: true,
  onPermanentFailure: () => disconnectStream(),
});

refresh.refreshInBackground(); // Start the initial fetch; failures are logged.
await refresh.sync(); // Explicit refresh; failures reject.

// The SDK's stream adapter reports connection state and version announcements.
refresh.setStreaming({ connected: true, refresh: false }); // Initial connection.
refresh.onVersion(42);
refresh.setStreaming({ connected: false }); // Enable fallback polling.
refresh.setStreaming({ connected: true }); // Reconnected; refresh to catch missed changes.

refresh.close(); // Abort pending work and clear retry/poll timers.
```

`fetchSnapshot(signal)` must honor cancellation and return a validated payload
with a nonnegative safe-integer `version`. `readJsonResponse(response, signal)`
provides shared HTTP/JSON error handling, including `Retry-After`. Throw
`new RefreshError(message, false)` for invalid payloads or other permanent errors.
Network errors and other thrown errors are treated as retryable.

The coordinator retains version tracking, combines overlapping refreshes, ignores
older snapshots, and catches up when a newer version is announced during a fetch.
Overlapping `sync()` calls share the active promise without queuing another fetch.
There is a five-second request timeout, 30-second fallback polling, and jittered
retry backoff capped at 30 seconds (longer `Retry-After` values are honored).
With `background: false`, automatic polling and retry timers are disabled.

## Optional clock

`createRefreshCoordinator` accepts `clock?: Clock`. SDKs omit it to use real time;
tests can supply a fake clock without replacing global timers:

```ts
type Clock = {
  now(): number; // Epoch milliseconds, like Date.now().
  setTimeout(callback: () => void, delayMs: number): () => void;
};
```

`setTimeout` must schedule the callback asynchronously and return an idempotent
cancellation function. The coordinator uses it for request timeouts, retry waits,
and polling (repeated timeouts). `close()` cancels pending timers and requests.
Retry jitter still uses `Math.random()`, which tests can mock separately.

`checkResponse(response, clock?)` and
`readJsonResponse(response, signal, clock?)` also accept the same clock when
testing HTTP-date `Retry-After` headers. Both default to real time. This clock
does not affect SDK-specific stream reconnection timers.

Keep `applySnapshot` synchronous and non-throwing; it should replace the SDK's
last-good snapshot. The SDK owns that snapshot, response validation, streaming,
and any persistence. The core does not open or reconnect a stream itself.

This package shares implementation between TypeScript SDKs only. Future Python
or Go SDKs will implement the same HTTP/streaming behavior in their own languages.

## Tests

From the repository root:

```sh
bun run --filter @flagbearer/sdk-core test
bun run --filter @flagbearer/sdk-core typecheck
```

`tests/refresh.test.ts` covers fetch coalescing, version ordering, stream catch-up,
retries/backoff, polling, request timeouts, permanent failures, and shutdown.
Tests inject a fake clock and controlled fetch promises: no HTTP server, database,
or timed sleeps are needed. Promise chains settle between fake timer callbacks.
Retry jitter is held deterministic with a restored `Math.random` spy; tests in
this file run serially. Helpers contain no assertions, and each test closes its
coordinator and restores its spies. Test tooling is development-only.
