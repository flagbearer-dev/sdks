import { afterEach, beforeEach, expect, test } from "bun:test";

import { init } from "../src/index";

let uploads: unknown[];
const originalFetch = globalThis.fetch;
const user = { key: "user-1", email: "tester@example.com" };

beforeEach(() => {
  uploads = [];
  globalThis.fetch = (async (input: string | URL | Request, init?: RequestInit) => {
    const { pathname } = new URL(String(input));
    if (pathname === "/v1/exposures") {
      const body = JSON.parse(String(init?.body));
      uploads.push(...body.events);
      return Response.json({ accepted: body.events.length, rejectedChecks: 0 });
    }
    if (pathname === "/v1/ruleset") {
      return Response.json({
        environmentId: "env-1",
        version: 1,
        flags: [
          { key: "new-checkout", enabled: true, rolloutBps: 10_000, overrideEmails: [] },
          { key: "dark-mode", enabled: false, rolloutBps: 0, overrideEmails: [] },
          { key: "beta", enabled: true, rolloutBps: 0, overrideEmails: ["tester@example.com"] },
        ],
      });
    }
    // The change stream stays open until the client closes it.
    return new Promise<Response>((_, reject) => {
      init?.signal?.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")));
    });
  }) as typeof fetch;
});

afterEach(() => {
  globalThis.fetch = originalFetch;
});

async function start() {
  const client = init({ key: "fbs_test", url: "http://sdk.test" });
  await client.sync();
  return client;
}

test("allFlags returns what checking each flag returns for the user", async () => {
  const client = await start();

  expect(client.allFlags(user)).toEqual({
    "new-checkout": client.isEnabled("new-checkout", user),
    "dark-mode": client.isEnabled("dark-mode", user),
    beta: client.isEnabled("beta", user),
  });
  expect(client.allFlags(user)).toEqual({ "new-checkout": true, "dark-mode": false, beta: true });
  client.close();
});

test("allFlags reports no exposures", async () => {
  const client = await start();

  client.allFlags(user);
  await client.flush();
  expect(uploads).toHaveLength(0);

  client.isEnabled("new-checkout", user);
  await client.flush();
  expect(uploads).toHaveLength(1);
  client.close();
});
