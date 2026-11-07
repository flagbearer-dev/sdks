import {
  afterEach,
  beforeEach,
  describe,
  expect,
  mock,
  spyOn,
  test,
} from "bun:test";

import {
  checkResponse,
  createRefreshCoordinator,
  RefreshError,
} from "../src/refresh";
import { createFakeClock, deferred, settlePromises } from "./test-utils";

type Snapshot = { version: number; value: string };
type Request = ReturnType<typeof deferred<Snapshot | null>> & {
  signal: AbortSignal;
};

const cleanups: Array<() => void> = [];
let random: ReturnType<typeof spyOn<typeof Math, "random">>;
let logError: ReturnType<typeof spyOn<typeof console, "error">>;

beforeEach(() => {
  random = spyOn(Math, "random").mockReturnValue(0.5); // Retry jitter multiplier = 1.
  logError = spyOn(console, "error").mockImplementation(() => {});
});

afterEach(() => {
  for (const close of cleanups.splice(0)) close();
  random.mockRestore();
  logError.mockRestore();
});

function setup({ background = false }: { background?: boolean } = {}) {
  const clock = createFakeClock();
  const requests: Request[] = [];
  const applied: Snapshot[] = [];
  const onPermanentFailure = mock(() => {});
  const coordinator = createRefreshCoordinator({
    clock,
    background,
    fetchSnapshot(signal) {
      const request = { ...deferred<Snapshot | null>(), signal };
      requests.push(request);
      return request.promise;
    },
    applySnapshot: (snapshot) => {
      applied.push(snapshot);
    },
    onPermanentFailure,
  });
  cleanups.push(coordinator.close);
  return { coordinator, clock, requests, applied, onPermanentFailure };
}

describe("fetch", () => {
  test("fetches when asked, not immediately on construction", async () => {
    const { coordinator, requests, applied, clock } = setup();
    expect(requests).toHaveLength(0);

    const pending = coordinator.sync();
    expect(requests).toHaveLength(1);
    requests[0]!.resolve({ version: 0, value: "initial" });
    await pending;

    expect(applied).toEqual([{ version: 0, value: "initial" }]);
    expect(clock.pendingCount()).toBe(0); // The request timeout was cancelled.
  });

  test("overlapping sync calls share a promise without an extra fetch", async () => {
    const { coordinator, requests, applied } = setup();
    const first = coordinator.sync();
    const second = coordinator.sync();
    coordinator.refreshInBackground();
    expect(second).toBe(first);
    expect(requests).toHaveLength(1);

    requests[0]!.resolve({ version: 1, value: "one response" });
    await Promise.all([first, second]);
    await settlePromises();

    expect(requests).toHaveLength(1);
    expect(applied).toHaveLength(1);
  });

  test("sync makes a fresh request after the previous batch finishes", async () => {
    const { coordinator, requests } = setup();
    const first = coordinator.sync();
    requests[0]!.resolve({ version: 1, value: "first" });
    await first;

    const second = coordinator.sync();
    expect(requests).toHaveLength(2);
    requests[1]!.resolve({ version: 2, value: "second" });
    await second;
  });
});

describe("update", () => {
  test("applies equal/newer versions and ignores older responses", async () => {
    const { coordinator, requests, applied } = setup();
    const responses = [
      { version: 2, value: "initial" },
      { version: 2, value: "same version, new payload" },
      { version: 5, value: "newer" },
      { version: 3, value: "stale" },
      { version: 0, value: "even older" },
      { version: 6, value: "latest" },
    ];

    for (const response of responses) {
      const pending = coordinator.sync();
      requests.at(-1)!.resolve(response);
      await pending;
    }

    expect(applied).toEqual([
      responses[0]!,
      responses[1]!,
      responses[2]!,
      responses[5]!,
    ]);
  });

  for (const version of [-1, 0.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
    test(`rejects invalid version ${version} without applying it`, async () => {
      const { coordinator, requests, applied, onPermanentFailure } = setup();
      const pending = coordinator.sync();
      requests[0]!.resolve({ version, value: "invalid" });

      await expect(pending).rejects.toThrow("Invalid snapshot version");
      expect(applied).toEqual([]);
      expect(onPermanentFailure).toHaveBeenCalledTimes(1);
    });
  }
});

describe("catch up", () => {
  test("remembers the highest version announced during a fetch", async () => {
    const { coordinator, requests, applied } = setup();
    const pending = coordinator.sync();
    coordinator.onVersion(3);
    coordinator.onVersion(5);
    coordinator.onVersion(4);
    expect(requests).toHaveLength(1); // Announcements don't start parallel requests.

    requests[0]!.resolve({ version: 2, value: "intermediate" });
    await settlePromises();
    expect(applied).toEqual([{ version: 2, value: "intermediate" }]);
    expect(requests).toHaveLength(2);

    requests[1]!.resolve({ version: 5, value: "caught up" });
    await pending;
    expect(applied.at(-1)).toEqual({ version: 5, value: "caught up" });
    expect(requests).toHaveLength(2);
  });

  test("doesn't refetch if the current response already satisfies the announcement", async () => {
    const { coordinator, requests } = setup();
    const pending = coordinator.sync();
    coordinator.onVersion(5);
    requests[0]!.resolve({ version: 6, value: "ahead" });
    await pending;

    coordinator.onVersion(5);
    coordinator.onVersion(6);
    await settlePromises();
    expect(requests).toHaveLength(1);
  });

  test("a newer announcement starts a refresh after an earlier request completed", async () => {
    const { coordinator, clock, requests, applied } = setup();
    const pending = coordinator.sync();
    requests[0]!.resolve({ version: 1, value: "initial" });
    await pending;

    coordinator.onVersion(2);
    await clock.advanceBy(999); // Random spread: half of the two-second maximum.
    expect(requests).toHaveLength(1);
    await clock.advanceBy(1);
    expect(requests).toHaveLength(2);
    requests[1]!.resolve({ version: 2, value: "updated" });
    await settlePromises();
    expect(applied.at(-1)?.version).toBe(2);
  });

  test("an unchanged response keeps the current snapshot", async () => {
    const { coordinator, requests, applied } = setup();
    const first = coordinator.sync();
    requests[0]!.resolve({ version: 1, value: "initial" });
    await first;

    const second = coordinator.sync();
    requests[1]!.resolve(null);
    await second;
    expect(applied).toEqual([{ version: 1, value: "initial" }]);
  });

  test("retries when unchanged but behind an announced version", async () => {
    const { coordinator, clock, requests, applied } = setup({
      background: true,
    });
    coordinator.setStreaming({ connected: true, refresh: false });
    const pending = coordinator.sync();
    requests[0]!.resolve({ version: 1, value: "initial" });
    await pending;

    coordinator.onVersion(2);
    await clock.advanceBy(1000);
    requests[1]!.resolve(null);
    await settlePromises();
    await clock.advanceBy(1000);
    expect(requests).toHaveLength(3);
    requests[2]!.resolve({ version: 2, value: "caught up" });
    await settlePromises();
    expect(applied.at(-1)?.version).toBe(2);
  });

  test("backs off when the server isn't progressing toward the announced version", async () => {
    const { coordinator, clock, requests, applied } = setup({
      background: true,
    });
    coordinator.setStreaming({ connected: true, refresh: false });
    const pending = coordinator.sync();
    requests[0]!.resolve({ version: 1, value: "initial" });
    await pending;

    coordinator.onVersion(3);
    await clock.advanceBy(1000);
    requests[1]!.resolve({ version: 1, value: "still behind" });
    await settlePromises();
    expect(requests).toHaveLength(2);

    await clock.advanceBy(999);
    expect(requests).toHaveLength(2);
    await clock.advanceBy(1);
    expect(requests).toHaveLength(3);
    requests[2]!.resolve({ version: 3, value: "caught up" });
    await settlePromises();
    expect(applied.at(-1)?.version).toBe(3);
    expect(clock.pendingCount()).toBe(0);
  });
});

describe("recover", () => {
  test("retries temporary failures with capped backoff, then resets after success", async () => {
    const { coordinator, clock, requests, applied } = setup({
      background: true,
    });
    coordinator.setStreaming({ connected: true, refresh: false }); // Isolate retries from fallback polling.
    const first = coordinator.sync();
    requests[0]!.reject(new TypeError("Network unavailable"));
    await expect(first).rejects.toThrow("Network unavailable");

    const delays = [1000, 2000, 4000, 8000, 16000, 30000, 30000];
    for (let index = 0; index < delays.length; index++) {
      const delay = delays[index]!;
      await clock.advanceBy(delay - 1);
      expect(requests).toHaveLength(index + 1);
      await clock.advanceBy(1);
      expect(requests).toHaveLength(index + 2);
      if (index < delays.length - 1) {
        requests.at(-1)!.reject(new RefreshError("Server unavailable", true));
        await settlePromises();
      }
    }

    requests.at(-1)!.resolve({ version: 1, value: "recovered" });
    await settlePromises();
    expect(applied).toEqual([{ version: 1, value: "recovered" }]);
    expect(clock.pendingCount()).toBe(0);

    const next = coordinator.sync();
    requests.at(-1)!.reject(new RefreshError("Another failure", true));
    await expect(next).rejects.toThrow("Another failure");
    const count = requests.length;
    await clock.advanceBy(999);
    expect(requests).toHaveLength(count);
    await clock.advanceBy(1);
    expect(requests).toHaveLength(count + 1); // Back to the first 1-second delay.
    requests.at(-1)!.resolve({ version: 2, value: "recovered again" });
    await settlePromises();
  });

  test("honors Retry-After even when manual sync is requested during cooldown", async () => {
    const { coordinator, clock, requests } = setup({ background: true });
    const first = coordinator.sync();
    requests[0]!.reject(new RefreshError("Rate limited", true, 90_000));
    await expect(first).rejects.toThrow("Rate limited");

    const next = coordinator.sync();
    await clock.advanceBy(89_999);
    expect(requests).toHaveLength(1);
    await clock.advanceBy(1);
    expect(requests).toHaveLength(2);
    requests[1]!.resolve({ version: 1, value: "after cooldown" });
    await next;
  });

  test("aborts a request after five seconds and retries the failure", async () => {
    const { coordinator, clock, requests, applied } = setup({
      background: true,
    });
    const pending = coordinator.sync();
    const request = requests[0]!;
    // A real fetch rejects when its signal aborts; this controlled fetch does too.
    request.signal.addEventListener(
      "abort",
      () => request.reject(request.signal.reason),
      { once: true },
    );
    const failure = pending.catch((error: unknown) => error);

    await clock.advanceBy(4999);
    expect(request.signal.aborted).toBe(false);
    await clock.advanceBy(1);
    expect(request.signal.aborted).toBe(true);
    expect(await failure).toBeInstanceOf(DOMException);
    expect(((await failure) as DOMException).name).toBe("TimeoutError");
    expect(applied).toEqual([]);

    await clock.advanceBy(1000);
    expect(requests).toHaveLength(2);
    requests[1]!.resolve({ version: 1, value: "after timeout" });
    await settlePromises();
    expect(applied.at(-1)?.version).toBe(1);
  });

  test("polls every 30 seconds while disconnected, and pauses while streaming", async () => {
    const { coordinator, clock, requests } = setup({ background: true });
    coordinator.setStreaming({ connected: false });

    await clock.advanceBy(29_999);
    expect(requests).toHaveLength(0);
    await clock.advanceBy(1);
    expect(requests).toHaveLength(1);
    requests[0]!.resolve({ version: 1, value: "first poll" });
    await settlePromises();

    await clock.advanceBy(30_000);
    expect(requests).toHaveLength(2);
    requests[1]!.resolve({ version: 2, value: "second poll" });
    await settlePromises();

    coordinator.setStreaming({ connected: true, refresh: false });
    expect(clock.pendingCount()).toBe(0);
    await clock.advanceBy(90_000);
    expect(requests).toHaveLength(2);

    coordinator.setStreaming({ connected: false });
    await clock.advanceBy(30_000);
    expect(requests).toHaveLength(3);
    requests[2]!.resolve({ version: 3, value: "poll after disconnect" });
    await settlePromises();
  });

  test("varies the poll interval by up to ten percent", async () => {
    random.mockReturnValue(0);
    const { clock, requests } = setup({ background: true });

    await clock.advanceBy(26_999);
    expect(requests).toHaveLength(0);
    await clock.advanceBy(1);
    expect(requests).toHaveLength(1);
    requests[0]!.resolve({ version: 1, value: "first poll" });
    await settlePromises();
  });

  test("poll ticks don't start parallel requests", async () => {
    const { coordinator, clock, requests } = setup({ background: true });
    const pending = coordinator.sync();
    // Deliberately leave the adapter pending, even after its abort signal fires.
    await clock.advanceBy(60_000);
    expect(requests).toHaveLength(1);
    coordinator.close();
    requests[0]!.resolve({ version: 1, value: "late" });
    await pending;
  });

  test("reconnection refreshes immediately without leaving a polling timer", async () => {
    const { coordinator, clock, requests } = setup({ background: true });
    coordinator.setStreaming({ connected: true });
    expect(requests).toHaveLength(1);
    requests[0]!.resolve({ version: 1, value: "connected" });
    await settlePromises();

    coordinator.setStreaming({ connected: false });
    coordinator.setStreaming({ connected: true });
    expect(requests).toHaveLength(2);
    requests[1]!.resolve({ version: 2, value: "reconnected" });
    await settlePromises();
    await clock.advanceBy(90_000);
    expect(requests).toHaveLength(2);
    expect(clock.pendingCount()).toBe(0);
  });

  test("permanent failures retain data, block automatic work, and allow manual recovery", async () => {
    const { coordinator, clock, requests, applied, onPermanentFailure } = setup(
      { background: true },
    );
    const initial = coordinator.sync();
    requests[0]!.resolve({ version: 1, value: "last good" });
    await initial;

    const failed = coordinator.sync();
    requests[1]!.reject(new RefreshError("Unauthorized", false));
    await expect(failed).rejects.toThrow("Unauthorized");
    expect(onPermanentFailure).toHaveBeenCalledTimes(1);
    expect(applied).toEqual([{ version: 1, value: "last good" }]);
    expect(clock.pendingCount()).toBe(0);

    coordinator.refreshInBackground();
    coordinator.onVersion(2);
    await clock.advanceBy(120_000);
    expect(requests).toHaveLength(2);

    const recovery = coordinator.sync();
    requests[2]!.resolve({ version: 2, value: "recovered" });
    await recovery;
    expect(applied.at(-1)?.version).toBe(2);
    await clock.advanceBy(30_000);
    expect(requests).toHaveLength(4); // Manual recovery re-enables polling.
    requests[3]!.resolve({ version: 2, value: "polled" });
    await settlePromises();
  });

  test("background false disables automatic retry and polling timers", async () => {
    const { coordinator, clock, requests } = setup();
    const pending = coordinator.sync();
    requests[0]!.reject(new RefreshError("Unavailable", true));
    expect(pending).rejects.toThrow("Unavailable");
    coordinator.setStreaming({ connected: false });
    await clock.advanceBy(120_000);
    expect(requests).toHaveLength(1);
    expect(clock.pendingCount()).toBe(0);
  });
});

describe("resume", () => {
  test("rechecks only when the last check is at least 15 seconds old", async () => {
    const { coordinator, clock, requests } = setup({ background: true });
    coordinator.setStreaming({ connected: true, refresh: false });
    coordinator.refreshInBackground();
    requests[0]!.resolve({ version: 1, value: "first" });
    await settlePromises();

    await clock.advanceBy(14_999);
    coordinator.resume();
    expect(requests).toHaveLength(1);

    await clock.advanceBy(1);
    coordinator.resume();
    expect(requests).toHaveLength(2);
    requests[1]!.resolve(null);
    await settlePromises();

    // An unchanged (204) answer counts as a check too.
    await clock.advanceBy(10_000);
    coordinator.resume();
    expect(requests).toHaveLength(2);
  });

  test("waking cuts the SDK's own backoff short", async () => {
    const { coordinator, requests } = setup({ background: true });
    coordinator.refreshInBackground();
    requests[0]!.reject(new RefreshError("Network down", true));
    await settlePromises();

    coordinator.resume();
    await settlePromises();
    expect(requests).toHaveLength(2);
  });

  test("waking never cuts a server's Retry-After short", async () => {
    const { coordinator, clock, requests } = setup({ background: true });
    coordinator.setStreaming({ connected: true, refresh: false });
    coordinator.refreshInBackground();
    requests[0]!.reject(new RefreshError("Too many requests", true, 60_000));
    await settlePromises();

    coordinator.resume();
    await clock.advanceBy(59_999);
    coordinator.resume();
    expect(requests).toHaveLength(1);

    await clock.advanceBy(1);
    expect(requests).toHaveLength(2);
  });

  test("pause stops polling until resume", async () => {
    const { coordinator, clock, requests } = setup({ background: true });
    coordinator.pause();

    await clock.advanceBy(120_000);
    expect(requests).toHaveLength(0);

    coordinator.resume();
    expect(requests).toHaveLength(1);
    requests[0]!.resolve({ version: 1, value: "after pause" });
    await settlePromises();
  });

  test("after pausing a streaming page, resume polls until a stream opens again", async () => {
    const { coordinator, clock, requests } = setup({ background: true });
    coordinator.setStreaming({ connected: true, refresh: false });
    coordinator.pause();
    coordinator.resume();
    requests[0]!.resolve({ version: 1, value: "on resume" });
    await settlePromises();

    await clock.advanceBy(30_000);
    expect(requests).toHaveLength(2);
    requests[1]!.resolve(null);
    await settlePromises();
  });
});

describe("stop", () => {
  test("close aborts work, rejects future syncs, and ignores late results", async () => {
    const { coordinator, clock, requests, applied, onPermanentFailure } = setup(
      { background: true },
    );
    const pending = coordinator.sync();
    coordinator.close();
    coordinator.close(); // Idempotent.
    expect(requests[0]!.signal.aborted).toBe(true);
    expect(clock.pendingCount()).toBe(0);

    // Even an adapter that ignores cancellation cannot publish after close.
    requests[0]!.resolve({ version: 10, value: "late" });
    await pending;
    coordinator.refreshInBackground();
    coordinator.onVersion(11);
    coordinator.setStreaming({ connected: false });
    coordinator.setStreaming({ connected: true });
    await expect(coordinator.sync()).rejects.toThrow("closed");
    await clock.advanceBy(120_000);

    expect(applied).toEqual([]);
    expect(requests).toHaveLength(1);
    expect(onPermanentFailure).not.toHaveBeenCalled();
    expect(clock.pendingCount()).toBe(0);
  });

  test("close cancels both scheduled retries and polling", async () => {
    const { coordinator, clock, requests } = setup({ background: true });
    const pending = coordinator.sync();
    requests[0]!.reject(new RefreshError("Unavailable", true));
    await expect(pending).rejects.toThrow("Unavailable");
    expect(clock.pendingCount()).toBe(2);

    coordinator.close();
    expect(clock.pendingCount()).toBe(0);
    await clock.advanceBy(120_000);
    expect(requests).toHaveLength(1);
  });

  test("close cancels a manual sync waiting for its retry cooldown", async () => {
    const { coordinator, clock, requests } = setup({ background: true });
    const first = coordinator.sync();
    requests[0]!.reject(new RefreshError("Rate limited", true, 60_000));
    await expect(first).rejects.toThrow("Rate limited");

    const waiting = coordinator.sync();
    coordinator.close();
    await waiting;
    await clock.advanceBy(120_000);
    expect(requests).toHaveLength(1);
    expect(clock.pendingCount()).toBe(0);
  });
});

describe("HTTP retry classification", () => {
  for (const status of [408, 429, 500, 503]) {
    test(`HTTP ${status} is retryable`, () => {
      try {
        checkResponse(new Response(null, { status }));
        throw new Error("Expected an HTTP failure");
      } catch (error) {
        expect(error).toBeInstanceOf(RefreshError);
        expect((error as RefreshError).retryable).toBe(true);
      }
    });
  }

  for (const status of [400, 401, 403, 404]) {
    test(`HTTP ${status} is permanent`, () => {
      try {
        checkResponse(new Response(null, { status }));
        throw new Error("Expected an HTTP failure");
      } catch (error) {
        expect(error).toBeInstanceOf(RefreshError);
        expect((error as RefreshError).retryable).toBe(false);
      }
    });
  }

  test.each(["90", new Date(90_000).toUTCString()])(
    "parses Retry-After %s with the injected clock",
    (header) => {
      try {
        checkResponse(
          new Response(null, {
            status: 429,
            headers: { "Retry-After": header },
          }),
          createFakeClock(),
        );
        throw new Error("Expected an HTTP failure");
      } catch (error) {
        expect(error).toBeInstanceOf(RefreshError);
        expect((error as RefreshError).retryAfterMs).toBe(90_000);
      }
    },
  );
});
