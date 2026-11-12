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

// Bun has no XMLHttpRequest, so the stream is a stand-in the test feeds by
// hand, the way React Native grows responseText as chunks arrive.
class FakeRequest {
  status = 0;
  responseText = "";
  aborted = false;
  headers: Record<string, string> = {};
  onprogress: (() => void) | null = null;
  onloadend: (() => void) | null = null;
  open() {}
  setRequestHeader() {}
  send() {
    requests.push(this);
  }
  abort() {
    this.aborted = true;
  }
  getResponseHeader(name: string) {
    return this.headers[name] ?? null;
  }
  receive(text: string) {
    this.status = 200;
    this.responseText += text;
    this.onprogress?.();
  }
  end(status: number, headers: Record<string, string> = {}) {
    this.status = status;
    this.headers = headers;
    this.onloadend?.();
  }
}

function version(number: number) {
  return `event: version\ndata: {"version":${number}}\n\n`;
}

let requests: FakeRequest[];
let serverVersion: number;
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
    return Response.json({ version: serverVersion, flags: { "new-checkout": true }, details: {} });
  }) as typeof fetch;
  serverVersion = 1;
  requests = [];
});

afterEach(() => {
  for (const client of clients.splice(0)) client.close();
  delete (globalThis as { XMLHttpRequest?: unknown }).XMLHttpRequest;
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

async function startStreaming() {
  (globalThis as { XMLHttpRequest?: unknown }).XMLHttpRequest = FakeRequest;
  const client = await start();
  expect(requests).toHaveLength(1);
  return client;
}

async function advance(ms: number) {
  jest.advanceTimersByTime(ms);
  await settle();
}

test("a change announced on the stream is fetched within seconds, and polling stops", async () => {
  await startStreaming();
  requests[0]!.receive(`retry: 5000\n\n${version(1)}`);
  await settle();

  await advance(35_000);
  expect(evaluations).toBe(1);

  serverVersion = 2;
  requests[0]!.receive(version(2));
  await advance(2000);
  expect(evaluations).toBe(2);
});

test("a change that arrives in two pieces is still fetched", async () => {
  await startStreaming();
  requests[0]!.receive(version(1));
  serverVersion = 2;

  requests[0]!.receive("event: version\ndata: {\"ver");
  await advance(2000);
  expect(evaluations).toBe(1);

  requests[0]!.receive("sion\":2}\n\n");
  await advance(2000);
  expect(evaluations).toBe(2);
});

test("when the stream ends, polling resumes and the stream reopens", async () => {
  await startStreaming();
  requests[0]!.receive(`retry: 5000\n\n${version(1)}`);
  await settle();

  requests[0]!.end(200);
  await advance(5000);
  expect(requests).toHaveLength(2);

  await advance(30_000);
  expect(evaluations).toBe(2);

  // Reopening rechecks, in case a change was announced while it was down.
  requests[1]!.receive(version(1));
  await settle();
  expect(evaluations).toBe(3);
});

test("a stream the server refuses isn't reopened, and polling goes on", async () => {
  await startStreaming();
  requests[0]!.end(401);

  await advance(35_000);
  await advance(35_000);
  expect(requests).toHaveLength(1);
  expect(evaluations).toBe(3);
});

test("a stream turned away for now waits out Retry-After", async () => {
  await startStreaming();
  requests[0]!.end(429, { "Retry-After": "60" });

  await advance(59_000);
  expect(requests).toHaveLength(1);

  await advance(1000);
  expect(requests).toHaveLength(2);
});

test("a stream silent for 45 seconds is dropped and reopened", async () => {
  await startStreaming();
  requests[0]!.receive(`retry: 5000\n\n${version(1)}`);
  await advance(30_000);
  requests[0]!.receive("event: ping\ndata: {}\n\n");

  await advance(44_000);
  expect(requests[0]!.aborted).toBe(false);

  await advance(1000);
  expect(requests[0]!.aborted).toBe(true);
  await advance(5000);
  expect(requests).toHaveLength(2);
});

test("a response held back until it's complete never stops polling", async () => {
  await startStreaming();

  await advance(35_000);
  expect(evaluations).toBe(2);
});

test("closing the client closes the stream for good", async () => {
  const client = await startStreaming();
  client.close();

  expect(requests[0]!.aborted).toBe(true);
  await advance(60_000);
  expect(requests).toHaveLength(1);
});
