import { setImmediate } from "node:timers";
import { afterEach, beforeEach, expect, jest, spyOn, test } from "bun:test";

import { init } from "../src/index";

// Bun has no DOM, so the page, its tab state and its streams are stand-ins
// that a test drives by hand.
class FakeEventSource extends EventTarget {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSED = 2;
  readyState = FakeEventSource.CONNECTING;

  constructor(readonly url: string) {
    super();
    sources.push(this);
  }

  open() {
    this.readyState = FakeEventSource.OPEN;
    this.dispatchEvent(new Event("open"));
  }

  ping() {
    this.dispatchEvent(new MessageEvent("ping", { data: "{}" }));
  }

  close() {
    this.readyState = FakeEventSource.CLOSED;
  }
}

let sources: FakeEventSource[];
let evaluations: number;
let page: EventTarget;
let tab: EventTarget & { visibilityState: "visible" | "hidden" };
const originalFetch = globalThis.fetch;
const globals = globalThis as unknown as Record<string, unknown>;

beforeEach(() => {
  jest.useFakeTimers({ now: Date.parse("2026-11-04T12:00:00Z") });
  spyOn(console, "error").mockImplementation(() => {});
  sources = [];
  evaluations = 0;
  page = new EventTarget();
  tab = Object.assign(new EventTarget(), {
    visibilityState: "visible" as "visible" | "hidden",
  });
  globals.window = page;
  globals.document = tab;
  globals.EventSource = FakeEventSource;
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    if (new URL(String(input)).pathname !== "/v1/evaluate")
      return Response.json({ accepted: 0, rejectedChecks: 0 });
    evaluations++;
    return Response.json({ version: 1, flags: {}, details: {} });
  }) as typeof fetch;
});

afterEach(() => {
  delete globals.window;
  delete globals.document;
  delete globals.EventSource;
  globalThis.fetch = originalFetch;
  jest.useRealTimers();
});

// Lets fetch and response promises finish without moving fake time.
async function settle() {
  for (let i = 0; i < 5; i++)
    await new Promise((resolve) => setImmediate(resolve));
}

async function setup() {
  const client = init({ clientKey: "fbc_test", url: "http://sdk.test" });
  await settle();
  sources[0]!.open();
  return client;
}

function show() {
  tab.visibilityState = "visible";
  tab.dispatchEvent(new Event("visibilitychange"));
}

function hide() {
  tab.visibilityState = "hidden";
  tab.dispatchEvent(new Event("visibilitychange"));
}

test("a stream silent for 45 seconds is replaced on resume; a live one is kept", async () => {
  const client = await setup();
  jest.advanceTimersByTime(30_000);
  sources[0]!.ping();

  jest.advanceTimersByTime(30_000);
  show();
  expect(sources).toHaveLength(1);

  jest.advanceTimersByTime(16_000);
  show();
  expect(sources).toHaveLength(2);
  expect(sources[0]!.readyState).toBe(FakeEventSource.CLOSED);
  client.close();
});

test("a tab hidden for 5 minutes drops its stream and stops polling; showing it reconnects and rechecks", async () => {
  const client = await setup();
  expect(evaluations).toBe(1);

  hide();
  jest.advanceTimersByTime(299_999);
  expect(sources[0]!.readyState).toBe(FakeEventSource.OPEN);
  jest.advanceTimersByTime(1);
  expect(sources[0]!.readyState).toBe(FakeEventSource.CLOSED);

  jest.advanceTimersByTime(120_000);
  await settle();
  expect(evaluations).toBe(1);

  show();
  await settle();
  expect(sources).toHaveLength(2);
  expect(evaluations).toBe(2);
  client.close();
});

test("a hidden tab that fell back to polling stops polling after 5 minutes", async () => {
  const client = await setup();
  sources[0]!.dispatchEvent(new Event("error"));
  jest.advanceTimersByTime(33_000); // a poll interval is 30 seconds ± 10%
  await settle();
  expect(evaluations).toBe(2);

  hide();
  jest.advanceTimersByTime(300_000);
  await settle();
  const atPause = evaluations;
  jest.advanceTimersByTime(120_000);
  await settle();

  expect(evaluations).toBe(atPause);
  client.close();
});

test("a tab hidden briefly keeps its stream", async () => {
  const client = await setup();
  hide();
  jest.advanceTimersByTime(30_000);
  sources[0]!.ping(); // the server keeps pinging a hidden tab's stream
  jest.advanceTimersByTime(30_000);
  show();
  jest.advanceTimersByTime(300_000);

  expect(sources).toHaveLength(1);
  expect(sources[0]!.readyState).toBe(FakeEventSource.OPEN);
  client.close();
});

test("a page restored from the back/forward cache, or back online, rechecks its flags", async () => {
  const client = await setup();
  jest.advanceTimersByTime(20_000);

  page.dispatchEvent(
    Object.assign(new Event("pageshow"), { persisted: false }),
  );
  await settle();
  expect(evaluations).toBe(1);

  page.dispatchEvent(Object.assign(new Event("pageshow"), { persisted: true }));
  await settle();
  expect(evaluations).toBe(2);

  jest.advanceTimersByTime(20_000);
  page.dispatchEvent(new Event("online"));
  await settle();
  expect(evaluations).toBe(3);
  client.close();
});

test("after close, page events do nothing", async () => {
  const client = await setup();
  client.close();
  jest.advanceTimersByTime(60_000);

  show();
  page.dispatchEvent(new Event("online"));
  page.dispatchEvent(new Event("focus"));
  await settle();

  expect(sources).toHaveLength(1);
  expect(evaluations).toBe(1);
});
