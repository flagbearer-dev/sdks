import { afterEach, beforeEach, expect, test } from "bun:test";

import { createExposureReporter } from "../src/exposures";

type Upload = {
  batchId: string;
  user: { key?: string; email?: string };
  events: { flagKey: string; checkCount: number; [field: string]: unknown }[];
  droppedChecks: number;
  sdkVersion: string;
};

let uploads: Upload[];
let respond: (upload: Upload) => Response;
const originalFetch = globalThis.fetch;
const reporters: ReturnType<typeof createExposureReporter>[] = [];

beforeEach(() => {
  uploads = [];
  respond = (upload) =>
    Response.json({ accepted: upload.events.length, rejectedChecks: 0 });
  globalThis.fetch = (async (_input: RequestInfo | URL, init?: RequestInit) => {
    const upload = JSON.parse(String(init?.body)) as Upload;
    uploads.push(upload);
    return respond(upload);
  }) as typeof fetch;
});

afterEach(async () => {
  for (const reporter of reporters.splice(0))
    await reporter.close().catch(() => {});
  globalThis.fetch = originalFetch;
});

function setup() {
  const reporter = createExposureReporter({
    url: new URL("http://sdk.test/v1/exposures"),
    key: "fbc_test",
    sdk: "web",
    sdkVersion: "1.2.3",
  });
  reporters.push(reporter);
  return reporter;
}

const on = {
  value: true,
  reason: "rollout",
  configurationVersion: 1,
} as const;

test("switching users mid-session gives one batch per user, with the user sent once", async () => {
  const reporter = setup();
  reporter.record("search", { key: "maya" }, on);
  reporter.record("search", { key: "maya" }, on);
  reporter.record("search", { key: "sam", email: "Sam@Example.com" }, on);

  await reporter.flush();

  expect(uploads.map((upload) => upload.user)).toEqual([
    { key: "maya" },
    { key: "sam", email: "sam@example.com" },
  ]);
  expect(uploads.map((upload) => upload.events.length)).toEqual([1, 1]);
  expect(uploads[0]!.events[0]!.checkCount).toBe(2);
  expect(Object.keys(uploads[0]!.events[0]!)).not.toContain("userKey");
  expect(uploads[0]!.sdkVersion).toBe("1.2.3");
  expect(new Set(uploads.map((upload) => upload.batchId)).size).toBe(2);
});

test("a failed upload is retried with the same batchId and the same events", async () => {
  const reporter = setup();
  respond = () => new Response(null, { status: 503 });
  reporter.record("search", { key: "maya" }, on);
  await reporter.flush().catch(() => {});

  // Checks made after the failure go in a new batch, not the one being retried.
  reporter.record("search", { key: "maya" }, on);
  respond = (upload) =>
    Response.json({ accepted: upload.events.length, rejectedChecks: 0 });
  await reporter.flush();

  expect(uploads).toHaveLength(3);
  expect(uploads[1]).toEqual(uploads[0]!);
  expect(uploads[2]!.batchId).not.toBe(uploads[0]!.batchId);
});

test("a rejected batch is dropped and its checks are reported as lost", async () => {
  const reporter = setup();
  respond = () => new Response(null, { status: 400 });
  reporter.record("search", { key: "maya" }, on);
  reporter.record("search", { key: "maya" }, on);
  await reporter.flush().catch(() => {});

  respond = (upload) =>
    Response.json({ accepted: upload.events.length, rejectedChecks: 0 });
  await reporter.flush();

  expect(uploads).toHaveLength(2);
  expect(uploads[1]!.events).toEqual([]);
  expect(uploads[1]!.droppedChecks).toBe(2);
});

test("a batch holds at most 100 events", async () => {
  const reporter = setup();
  for (let i = 0; i < 150; i++)
    reporter.record(`flag-${i}`, { key: "maya" }, on);

  await reporter.flush();

  expect(uploads.map((upload) => upload.events.length)).toEqual([100, 50]);
});
