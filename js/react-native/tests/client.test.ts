import { setImmediate } from "node:timers";
import { afterEach, beforeEach, expect, jest, mock, spyOn, test } from "bun:test";

// Bun has no React Native, so AppState is a stand-in the test drives by hand.
type AppStateStatus = "active" | "background" | "inactive";
const appState = {
  currentState: "active" as AppStateStatus,
  listeners: new Set<(state: AppStateStatus) => void>(),
  addEventListener(_type: "change", listener: (state: AppStateStatus) => void) {
    appState.listeners.add(listener);
    return { remove: () => appState.listeners.delete(listener) };
  },
  change(state: AppStateStatus) {
    appState.currentState = state;
    for (const listener of appState.listeners) listener(state);
  },
};
mock.module("react-native", () => ({ AppState: appState }));
const { init } = await import("../src/index");

let evaluations: number;
let uploads: { sdk: string; events: unknown[] }[];
const originalFetch = globalThis.fetch;
const clients: ReturnType<typeof init>[] = [];

beforeEach(() => {
  jest.useFakeTimers({ now: Date.parse("2026-11-08T12:00:00Z") });
  spyOn(console, "error").mockImplementation(() => {});
  evaluations = 0;
  uploads = [];
  appState.currentState = "active";
  appState.listeners.clear();
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const body = JSON.parse(String(init?.body));
    if (new URL(String(input)).pathname === "/v1/exposures") {
      uploads.push(body);
      return Response.json({ accepted: body.events.length, rejectedChecks: 0 });
    }
    evaluations++;
    return Response.json({ version: 1, flags: { "new-checkout": true }, details: {} });
  }) as typeof fetch;
});

afterEach(() => {
  for (const client of clients.splice(0)) client.close();
  globalThis.fetch = originalFetch;
  jest.useRealTimers();
});

async function settle() {
  for (let i = 0; i < 5; i++) await new Promise((resolve) => setImmediate(resolve));
}

function memoryStorage() {
  const items = new Map<string, string>();
  return {
    items,
    getItem: async (key: string) => items.get(key) ?? null,
    setItem: async (key: string, value: string) => void items.set(key, value),
    removeItem: async (key: string) => void items.delete(key),
  };
}

async function start(storage?: ReturnType<typeof memoryStorage>) {
  const client = init({ clientKey: "fbc_test", url: "http://sdk.test", user: { key: "1" }, storage });
  clients.push(client);
  await settle();
  return client;
}

test("with no stream, flags are polled about every 30 seconds", async () => {
  const client = await start();
  expect(client.getStatus()).toBe("ready");
  expect(evaluations).toBe(1);

  jest.advanceTimersByTime(35_000);
  await settle();

  expect(evaluations).toBe(2);
});

test("going to the background uploads pending checks as react-native", async () => {
  const client = await start();
  client.isEnabled("missing-flag", false);

  appState.change("background");
  await settle();

  expect(uploads).toHaveLength(1);
  expect(uploads[0]!.sdk).toBe("react-native");
});

test("five minutes in the background stops polling; coming back rechecks", async () => {
  await start();
  appState.change("background");
  jest.advanceTimersByTime(5 * 60_000);
  await settle();
  const before = evaluations;

  jest.advanceTimersByTime(10 * 60_000);
  await settle();
  expect(evaluations).toBe(before);

  appState.change("active");
  await settle();
  expect(evaluations).toBe(before + 1);
});

test("with storage, the last flags are cached by default", async () => {
  const storage = memoryStorage();
  await start(storage);

  expect(storage.items.has("flagbearer:flags")).toBe(true);
  expect(storage.items.has("flagbearer:anon")).toBe(true);
});

test("after close, app state changes do nothing", async () => {
  const client = await start();
  client.close();

  expect(appState.listeners.size).toBe(0);
});
