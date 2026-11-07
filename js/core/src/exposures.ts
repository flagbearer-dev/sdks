import type {
  CompactExposure,
  EvaluationDetails,
  EvaluationUser,
} from "./types.js";

const WINDOW_MS = 30_000;
// One upload per bucket; sending more often only splits a bucket into more uploads.
const FLUSH_MS = 30_000;
const MAX_RECORDS = 1000;
const BATCH_SIZE = 100;

// One upload's contents, fixed when sealed, so a retry sends exactly the same
// batch and the server can recognize it by batchId.
type SealedBatch = {
  batchId: string;
  user: EvaluationUser;
  events: CompactExposure[];
};

export function createExposureReporter(options: {
  url: URL;
  key: string;
  sdk: string;
  sdkVersion: string;
  appVersion?: string;
}) {
  const reporterId = crypto.randomUUID();
  const active = new Map<
    string,
    { user: EvaluationUser; event: CompactExposure }
  >();
  const sealed: SealedBatch[] = [];
  let sealedEvents = 0;
  let droppedChecks = 0;
  let reportedDroppedChecks = 0;
  let inFlight: Promise<void> | undefined;
  let closed = false;
  let retryAt = 0;
  let failures = 0;

  function record(
    flagKey: string,
    user: EvaluationUser,
    details: EvaluationDetails,
  ) {
    if (closed) return;
    try {
      if (
        !flagKey ||
        flagKey.length > 256 ||
        (user.key && user.key.length > 256)
      ) {
        droppedChecks++;
        return;
      }
      const now = Date.now();
      const bucket = new Date(
        Math.floor(now / WINDOW_MS) * WINDOW_MS,
      ).toISOString();
      const email =
        user.email && user.email.length <= 254
          ? user.email.trim().toLowerCase() || undefined
          : undefined;
      const userKey = user.key || undefined;
      const key = JSON.stringify([
        flagKey,
        userKey,
        email,
        details.value,
        details.reason,
        details.configurationVersion,
        bucket,
      ]);
      const existing = active.get(key);
      if (existing && existing.event.checkCount < 1_000_000_000) {
        existing.event.checkCount++;
        existing.event.lastCheckedAt = new Date(now).toISOString();
        return;
      }
      if (existing || active.size + sealedEvents >= MAX_RECORDS) {
        droppedChecks++;
        return;
      }
      active.set(key, {
        user: { key: userKey, email },
        event: {
          flagKey,
          result: details.value,
          reason: details.reason,
          configurationVersion: details.configurationVersion,
          bucket,
          checkCount: 1,
          firstCheckedAt: new Date(now).toISOString(),
          lastCheckedAt: new Date(now).toISOString(),
        },
      });
    } catch {
      droppedChecks++;
    }
  }

  // identify() can switch users mid-session, so a batch never mixes users.
  function seal() {
    const open = new Map<string, SealedBatch>();
    for (const [key, { user, event }] of active) {
      const userId = JSON.stringify([user.key, user.email]);
      let batch = open.get(userId);
      if (!batch || batch.events.length >= BATCH_SIZE) {
        batch = { batchId: crypto.randomUUID(), user, events: [] };
        open.set(userId, batch);
        sealed.push(batch);
      }
      batch.events.push(event);
      sealedEvents++;
      active.delete(key);
    }
  }

  function settle(batch: SealedBatch) {
    if (sealed[0] !== batch) return;
    sealed.shift();
    sealedEvents -= batch.events.length;
  }

  async function send() {
    seal();
    while (sealed.length > 0 || droppedChecks > reportedDroppedChecks) {
      // Only lost checks to report: an empty batch carries the count.
      const batch = sealed[0] ?? {
        batchId: crypto.randomUUID(),
        user: {},
        events: [],
      };
      const checks = batch.events.reduce(
        (sum, item) => sum + item.checkCount,
        0,
      );
      const dropped = droppedChecks;
      const response = await fetch(options.url, {
        method: "POST",
        credentials: "omit",
        redirect: "error",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${options.key}`,
        },
        body: JSON.stringify({
          batchId: batch.batchId,
          reporterId,
          user: batch.user,
          events: batch.events,
          droppedChecks: dropped,
          sdk: options.sdk,
          sdkVersion: options.sdkVersion,
          appVersion: options.appVersion?.slice(0, 100),
        }),
        signal: AbortSignal.timeout(10_000),
        keepalive: batch.events.length < 10,
      });
      if (!response.ok) {
        const retryAfter = Number(response.headers.get("Retry-After"));
        if (Number.isFinite(retryAfter) && retryAfter > 0) {
          retryAt = Math.max(
            retryAt,
            Date.now() + Math.min(retryAfter, 3600) * 1000,
          );
        }
        if (
          response.status >= 400 &&
          response.status < 500 &&
          response.status !== 429
        ) {
          droppedChecks += checks;
          settle(batch);
          retryAt = Date.now() + 60_000;
        }
        throw new Error(`Exposure upload failed (${response.status})`);
      }
      const receipt: unknown = await response.json();
      if (
        typeof receipt !== "object" ||
        receipt === null ||
        !("accepted" in receipt) ||
        typeof receipt.accepted !== "number" ||
        !Number.isSafeInteger(receipt.accepted) ||
        receipt.accepted < 0 ||
        receipt.accepted > batch.events.length ||
        !("rejectedChecks" in receipt) ||
        typeof receipt.rejectedChecks !== "number" ||
        !Number.isSafeInteger(receipt.rejectedChecks) ||
        receipt.rejectedChecks < 0 ||
        receipt.rejectedChecks > checks
      ) {
        throw new Error("Invalid exposure upload receipt");
      }
      droppedChecks += receipt.rejectedChecks;
      settle(batch);
      reportedDroppedChecks = Math.max(reportedDroppedChecks, dropped);
    }
    failures = 0;
  }

  function flush(): Promise<void> {
    if (inFlight) return inFlight.then(() => flush());
    inFlight = send()
      .catch((error) => {
        failures++;
        retryAt = Math.max(
          retryAt,
          Date.now() + Math.min(60_000, 1000 * 2 ** Math.min(failures, 6)),
        );
        throw error;
      })
      .finally(() => {
        inFlight = undefined;
      });
    return inFlight;
  }

  const timer = setInterval(() => {
    if (!closed && Date.now() >= retryAt) void flush().catch(() => {});
  }, FLUSH_MS);
  (timer as unknown as { unref?: () => void }).unref?.();

  function close() {
    closed = true;
    clearInterval(timer);
    return flush();
  }

  return { record, flush, close };
}
