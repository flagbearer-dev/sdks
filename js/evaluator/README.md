# @flagbearer/evaluator

Flag evaluation for Flagbearer: rollout buckets, tester overrides and defaults,
exactly as every Flagbearer SDK computes them. It's an internal package that the
Flagbearer server uses; to check flags in your app, install
[`@flagbearer/node`](https://www.npmjs.com/package/@flagbearer/node) or
[`@flagbearer/web`](https://www.npmjs.com/package/@flagbearer/web) instead.

The cases every SDK must reproduce are in
[`fixtures/evaluation.json`](https://github.com/flagbearer-dev/sdks/blob/main/fixtures/evaluation.json).
