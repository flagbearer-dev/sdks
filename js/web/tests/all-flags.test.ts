import { afterEach, beforeEach, expect, test } from "bun:test";

import { init } from "../src/index";

let uploads: unknown[];
const originalFetch = globalThis.fetch;

beforeEach(() => {
  uploads = [];
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const body = JSON.parse(String(init?.body));
    if (new URL(String(input)).pathname === "/v1/exposures") {
      uploads.push(...body.events);
      return Response.json({ accepted: body.events.length, rejectedChecks: 0 });
    }
    return Response.json({
      version: 1,
      flags: { "new-checkout": true, "dark-mode": false },
      details: {},
    });
  }) as typeof fetch;
});

afterEach(() => {
  globalThis.fetch = originalFetch;
});

async function start() {
  const client = init({ clientKey: "fbc_test", url: "http://sdk.test" });
  await client.sync();
  return client;
}

test("allFlags returns what checking each flag returns", async () => {
  const client = await start();

  expect(client.allFlags()).toEqual({
    "new-checkout": client.isEnabled("new-checkout"),
    "dark-mode": client.isEnabled("dark-mode"),
  });
  client.close();
});

test("allFlags reports no exposures", async () => {
  const client = await start();

  client.allFlags();
  await client.flush();
  expect(uploads).toHaveLength(0);

  // A check that does report: a flag with no value falls back.
  client.isEnabled("missing-flag");
  await client.flush();
  expect(uploads).toHaveLength(1);
  client.close();
});

test("allFlags is a copy the caller can change", async () => {
  const client = await start();
  const flags = client.allFlags();

  flags["new-checkout"] = false;

  expect(client.isEnabled("new-checkout")).toBe(true);
  client.close();
});
