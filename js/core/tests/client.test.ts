import {
  afterEach,
  beforeEach,
  expect,
  setSystemTime,
  spyOn,
  test,
} from "bun:test";

import { createClient, type Platform, type Storage } from "../src/client";
import { deferred, settlePromises } from "./test-utils";

// Stands in for the SDK server. Each test says how /v1/evaluate answers.
type Answer = Record<string, boolean> | "fail" | "hang";
let answer: (email: string | undefined) => Answer;
let uploads: { batchId: string; reporterId: string }[];
const originalFetch = globalThis.fetch;
const clients: ReturnType<typeof createClient>[] = [];

beforeEach(() => {
  uploads = [];
  answer = () => ({});
  spyOn(console, "error").mockImplementation(() => {});
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = new URL(String(input)).pathname;
    const body = JSON.parse(String(init?.body));
    if (path === "/v1/exposures") {
      uploads.push(body);
      return Response.json({ accepted: body.events.length, rejectedChecks: 0 });
    }
    const result = answer(body.userEmail);
    if (result === "fail") throw new TypeError("Network down");
    if (result === "hang") return new Promise<Response>(() => {});
    return Response.json({ version: 1, flags: result, details: {} });
  }) as typeof fetch;
});

afterEach(() => {
  for (const client of clients.splice(0)) client.close();
  globalThis.fetch = originalFetch;
  setSystemTime();
});

function memoryStorage(): Storage & { items: Map<string, string> } {
  const items = new Map<string, string>();
  return {
    items,
    getItem: (key) => items.get(key) ?? null,
    setItem: (key, value) => void items.set(key, value),
    removeItem: (key) => void items.delete(key),
  };
}

// Like AsyncStorage: every call resolves later.
function asyncStorage(): Storage & { items: Map<string, string> } {
  const sync = memoryStorage();
  return {
    items: sync.items,
    getItem: async (key) => sync.getItem(key),
    setItem: async (key, value) => sync.setItem(key, value),
    removeItem: async (key) => sync.removeItem(key),
  };
}

function platform(overrides: Partial<Platform> = {}): Platform {
  return {
    sdk: "web",
    sdkVersion: "0.0.0",
    background: false,
    cacheFlags: true,
    ...overrides,
  };
}

const andrew = { key: "1", email: "andrew@example.com" };
const bea = { key: "2", email: "bea@example.com" };

async function start(
  storage: Storage | null | undefined,
  user = andrew,
  options: { platform?: Partial<Platform>; cacheFlags?: boolean } = {},
) {
  const client = createClient(
    {
      clientKey: "fbc_test",
      url: "http://sdk.test",
      user,
      storage,
      cacheFlags: options.cacheFlags,
    },
    platform(options.platform),
  );
  clients.push(client);
  for (let i = 0; i < 5; i++) await settlePromises();
  return client;
}

// One session with the network up leaves this user's flags in the cache.
async function seed(
  storage: Storage,
  flags: Record<string, boolean>,
  user = andrew,
) {
  answer = () => flags;
  const client = await start(storage, user);
  expect(client.getStatus()).toBe("ready");
  client.close();
}

test.each([
  ["sync", memoryStorage],
  ["async", asyncStorage],
])(
  "an offline launch starts with the flags this user last had (%s storage)",
  async (_, make) => {
    const storage = make();
    await seed(storage, { "new-checkout": true });

    answer = () => "fail";
    const client = await start(storage);

    expect(client.getStatus()).toBe("ready");
    expect(client.isEnabled("new-checkout", false)).toBe(true);
  },
);

test("another user's cached flags are never used", async () => {
  const storage = memoryStorage();
  await seed(storage, { "new-checkout": true }, andrew);

  answer = () => "fail";
  const client = await start(storage, bea);

  expect(client.getStatus()).toBe("failed");
  expect(client.isEnabled("new-checkout", false)).toBe(false);
});

test("switching users clears the previous user's cached flags", async () => {
  const storage = memoryStorage();
  await seed(storage, { "new-checkout": true }, andrew);

  answer = () => "hang";
  const client = await start(storage, andrew);
  expect(client.isEnabled("new-checkout", false)).toBe(true);

  void client.identify(bea);
  for (let i = 0; i < 5; i++) await settlePromises();

  expect(client.getStatus()).toBe("loading");
  expect(client.getSnapshot()).toEqual({});
});

test("fresh flags replace cached ones", async () => {
  const storage = memoryStorage();
  await seed(storage, { "new-checkout": true });

  answer = () => ({ "new-checkout": false });
  const client = await start(storage);

  expect(client.isEnabled("new-checkout", true)).toBe(false);
});

test("a slow cache never overwrites fresh flags", async () => {
  const storage = memoryStorage();
  await seed(storage, { "new-checkout": true });
  // What the cache held at launch, before the fresh flags replace it.
  const stale = storage.items.get("flagbearer:flags")!;
  const slowRead = deferred<string | null>();
  const slow: Storage = {
    ...storage,
    getItem: (key) =>
      key === "flagbearer:flags" ? slowRead.promise : storage.getItem(key),
  };

  answer = () => ({ "new-checkout": false });
  const client = await start(slow);
  expect(client.isEnabled("new-checkout", true)).toBe(false);

  slowRead.resolve(stale);
  for (let i = 0; i < 5; i++) await settlePromises();

  expect(client.isEnabled("new-checkout", true)).toBe(false);
});

test("cached flags older than seven days are ignored", async () => {
  const storage = memoryStorage();
  setSystemTime(new Date("2026-11-01T00:00:00Z"));
  await seed(storage, { "new-checkout": true });

  setSystemTime(new Date("2026-11-08T00:00:01Z"));
  answer = () => "fail";
  const client = await start(storage);

  expect(client.getStatus()).toBe("failed");
  expect(client.isEnabled("new-checkout", false)).toBe(false);
});

test("storage that throws behaves like none", async () => {
  const broken: Storage = {
    getItem: () => {
      throw new Error("Blocked");
    },
    setItem: () => {
      throw new Error("Blocked");
    },
    removeItem: () => {
      throw new Error("Blocked");
    },
  };
  answer = () => ({ "new-checkout": true });
  const client = await start(broken);

  expect(client.getStatus()).toBe("ready");
  expect(client.isEnabled("new-checkout", false)).toBe(true);
});

test("storage: null keeps nothing, even where the platform has storage", async () => {
  const platformStorage = memoryStorage();
  answer = () => ({ "new-checkout": true });
  await start(null, andrew, { platform: { storage: () => platformStorage } });

  expect(platformStorage.items.size).toBe(0);
});

test("without cacheFlags, storage holds only the anonymous ID", async () => {
  const storage = memoryStorage();
  answer = () => ({ "new-checkout": true });
  await start(storage, andrew, { platform: { cacheFlags: false } });

  expect([...storage.items.keys()]).toEqual(["flagbearer:anon"]);
});

test("works without crypto.randomUUID or AbortSignal.timeout, as in React Native", async () => {
  const randomUUID = Object.getOwnPropertyDescriptor(crypto, "randomUUID");
  const timeout = Object.getOwnPropertyDescriptor(AbortSignal, "timeout");
  Object.defineProperty(crypto, "randomUUID", {
    value: undefined,
    configurable: true,
  });
  Object.defineProperty(AbortSignal, "timeout", {
    value: undefined,
    configurable: true,
  });
  try {
    expect(crypto.randomUUID).toBeUndefined();
    expect(AbortSignal.timeout).toBeUndefined();
    const storage = memoryStorage();
    const client = await start(storage);
    client.isEnabled("missing-flag", false);
    await client.flush();

    const uuid =
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
    expect(uploads).toHaveLength(1);
    expect(uploads[0]!.batchId).toMatch(uuid);
    expect(uploads[0]!.reporterId).toMatch(uuid);
    expect(storage.items.get("flagbearer:anon")).toMatch(uuid);
  } finally {
    // Put back exactly what was there: an own property, or none.
    if (randomUUID) Object.defineProperty(crypto, "randomUUID", randomUUID);
    else delete (crypto as { randomUUID?: unknown }).randomUUID;
    if (timeout) Object.defineProperty(AbortSignal, "timeout", timeout);
    else delete (AbortSignal as { timeout?: unknown }).timeout;
  }
  expect(typeof crypto.randomUUID).toBe("function");
  expect(typeof AbortSignal.timeout).toBe("function");
});
