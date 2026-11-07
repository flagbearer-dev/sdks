import { afterEach, beforeEach, expect, spyOn, test } from "bun:test";

import { init } from "../src/index";

// Stands in for the SDK server: each /v1/evaluate request waits until the test
// answers it, so the order of responses is under the test's control.
type Pending = {
  email: string | undefined;
  answer(flags: Record<string, boolean>): void;
  fail(): void;
};
let requests: Pending[];
const originalFetch = globalThis.fetch;

beforeEach(() => {
  requests = [];
  spyOn(console, "error").mockImplementation(() => {});
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input));
    if (url.pathname !== "/v1/evaluate")
      return new Response(null, { status: 204 });
    const body = JSON.parse(String(init?.body)) as { userEmail?: string };
    return new Promise<Response>((resolve, reject) => {
      requests.push({
        email: body.userEmail,
        answer: (flags) =>
          resolve(Response.json({ version: 1, flags, details: {} })),
        fail: () => reject(new TypeError("Network down")),
      });
    });
  }) as typeof fetch;
});

afterEach(() => {
  globalThis.fetch = originalFetch;
});

const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

function setup(user?: { key: string; email: string }) {
  const client = init({ clientKey: "fbc_test", url: "http://sdk.test", user });
  let notified = 0;
  client.subscribe(() => notified++);
  return { client, notified: () => notified };
}

test("flags are loading until they arrive, then ready, and subscribers hear it", async () => {
  const { client, notified } = setup();
  await settle();
  expect(client.getStatus()).toBe("loading");

  requests[0]!.answer({ "app-access": true });
  await settle();

  expect(client.getStatus()).toBe("ready");
  expect(client.isEnabled("app-access")).toBe(true);
  expect(notified()).toBeGreaterThan(0);
  client.close();
});

test("a fetch that fails before any flags arrive is failed, not ready", async () => {
  const { client } = setup();
  await settle();

  requests[0]!.fail();
  await settle();

  expect(client.getStatus()).toBe("failed");
  client.close();
});

test("identify is loading until the new user's flags arrive", async () => {
  const { client } = setup({ key: "1", email: "andrew@example.com" });
  await settle();
  requests[0]!.answer({ "app-access": true });
  await settle();

  const identified = client.identify({ key: "2", email: "bea@example.com" });
  await settle();
  expect(client.getStatus()).toBe("loading");

  requests.at(-1)!.answer({ "app-access": false });
  await identified;
  expect(client.getStatus()).toBe("ready");
  expect(client.isEnabled("app-access", true)).toBe(false);
  client.close();
});

test("a previous user's late answer doesn't make the new user ready", async () => {
  const { client } = setup({ key: "1", email: "andrew@example.com" });
  await settle();
  const andrews = requests[0]!;

  const identified = client.identify({ key: "2", email: "bea@example.com" });
  await settle();
  const beas = requests.find((request) => request.email === "bea@example.com")!;
  andrews.answer({ "app-access": true });
  await settle();

  expect(client.getStatus()).toBe("loading");
  expect(client.isEnabled("app-access")).toBe(false);

  beas.answer({ "app-access": false });
  await identified;
  expect(client.getStatus()).toBe("ready");
  client.close();
});

test("identifying the same user again keeps their flags", async () => {
  const { client } = setup({ key: "1", email: "andrew@example.com" });
  await settle();
  requests[0]!.answer({ "app-access": true });
  await settle();

  await client.identify({ key: "1", email: "andrew@example.com" });

  expect(client.getStatus()).toBe("ready");
  expect(client.isEnabled("app-access")).toBe(true);
  expect(requests).toHaveLength(1);
  client.close();
});
