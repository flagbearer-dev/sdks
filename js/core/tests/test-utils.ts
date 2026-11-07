import { setImmediate } from "node:timers";

import type { Clock } from "../src/clock";

// Let complete fetch/run/finally promise chains settle without advancing fake time.
export function settlePromises(): Promise<void> {
  return new Promise((resolve) => setImmediate(resolve));
}

export function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((onResolve, onReject) => {
    resolve = onResolve;
    reject = onReject;
  });
  return { promise, resolve, reject };
}

export function createFakeClock() {
  let now = 0;
  let nextId = 0;
  const timers = new Map<number, { at: number; callback: () => void }>();

  const clock: Clock = {
    now: () => now,
    setTimeout(callback, delayMs) {
      const id = nextId++;
      timers.set(id, { at: now + delayMs, callback });
      return () => {
        timers.delete(id);
      };
    },
  };

  async function advanceBy(ms: number) {
    if (!Number.isFinite(ms) || ms < 0)
      throw new Error("Invalid clock advancement");
    await settlePromises();
    const target = now + ms;

    while (true) {
      const next = [...timers.entries()]
        .filter(([, timer]) => timer.at <= target)
        .sort(([aId, a], [bId, b]) => a.at - b.at || aId - bId)[0];
      if (!next) break;
      const [id, timer] = next;
      timers.delete(id);
      now = timer.at;
      timer.callback();
      await settlePromises();
    }

    now = target;
    await settlePromises();
  }

  return { ...clock, advanceBy, pendingCount: () => timers.size };
}
