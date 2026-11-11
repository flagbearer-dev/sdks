import { realClock, type Clock } from "./clock.js";
import { createDebugLogger } from "./debug.js";
import { isRecord, isVersion } from "./validation.js";

export type VersionedSnapshot = { version: number };

export type RefreshOptions<T extends VersionedSnapshot> = {
  // Resolves null when the server confirms the current version is still latest.
  fetchSnapshot(signal: AbortSignal): Promise<T | null>;
  applySnapshot(snapshot: T): void;
  background: boolean;
  onPermanentFailure(): void;
  // Called when a refresh fails, unless this coordinator has been closed.
  onRefreshFailed?(error: unknown): void;
  clock?: Clock;
  debug?: boolean;
  // resume() refetches only when the last successful check is older than this.
  resumeStaleMs?: number;
};

const REQUEST_TIMEOUT_MS = 5000;
const POLL_INTERVAL_MS = 30_000;
const MAX_RETRY_MS = 30_000;
// Spreads refetches out so a flag change doesn't hit the API all at once.
const MAX_VERSION_REFRESH_DELAY_MS = 2000;

export class RefreshError extends Error {
  constructor(
    message: string,
    readonly retryable: boolean,
    readonly retryAfterMs = 0,
  ) {
    super(message);
  }
}

function retryAfter(header: string | null, now: number): number {
  if (header === null) return 0;
  const seconds = Number(header);
  const delay =
    Number.isFinite(seconds) && seconds >= 0
      ? seconds * 1000
      : Date.parse(header) - now;
  return Number.isFinite(delay) ? Math.max(0, delay) : 0;
}

// The server's own explanation of a refused request, when it sent one.
async function serverMessage(response: Response) {
  try {
    const body: unknown = await response.json();
    return isRecord(body) && typeof body.message === "string"
      ? body.message.slice(0, 500)
      : undefined;
  } catch {
    return undefined;
  }
}

// JSON and streaming adapters share HTTP retry classification and Retry-After.
// `reason` is the server's explanation, added to the error when there is one.
export function checkResponse(
  response: Response,
  clock: Clock = realClock,
  reason?: string,
): void {
  if (!response.ok) {
    throw new RefreshError(
      `Flagbearer request failed: ${response.status}${reason ? `. ${reason}` : ""}`,
      response.status === 408 ||
        response.status === 429 ||
        response.status >= 500,
      retryAfter(response.headers.get("Retry-After"), clock.now()),
    );
  }
}

// checkResponse, with the server's explanation of a refusal when it sent one.
export async function checkResponseWithReason(
  response: Response,
  clock: Clock = realClock,
): Promise<void> {
  if (response.ok) return;
  const reason = await serverMessage(response);
  checkResponse(response, clock, reason);
}

export async function readJsonResponse(
  response: Response,
  signal: AbortSignal,
  clock: Clock = realClock,
): Promise<unknown> {
  await checkResponseWithReason(response, clock);
  try {
    return await response.json();
  } catch (error) {
    if (signal.aborted) throw signal.reason;
    if (error instanceof SyntaxError)
      throw new RefreshError("Invalid snapshot JSON", false);
    throw error;
  }
}

function wait(ms: number, signal: AbortSignal, clock: Clock): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) return reject(signal.reason);
    const cancelTimer = clock.setTimeout(() => {
      signal.removeEventListener("abort", abort);
      resolve();
    }, ms);
    function abort() {
      cancelTimer();
      signal.removeEventListener("abort", abort);
      reject(signal.reason);
    }
    signal.addEventListener("abort", abort, { once: true });
  });
}

export function createRefreshCoordinator<T extends VersionedSnapshot>(
  options: RefreshOptions<T>,
) {
  const clock = options.clock ?? realClock;
  const debug = createDebugLogger(options.debug);
  let stopped = false;
  let automaticBlocked = false;
  let streaming = false;
  let version: number | undefined;
  let wantedVersion: number | undefined;
  let refreshAgain = false;
  let inFlight: Promise<void> | undefined;
  let activeRequest: AbortController | undefined;
  let cancelRequestTimeout: (() => void) | undefined;
  let cancelRetry: (() => void) | undefined;
  let cancelPolling: (() => void) | undefined;
  let cancelVersionRefresh: (() => void) | undefined;
  let retryAttempt = 0;
  let notBefore = 0;
  // Set only from a server's Retry-After, which waking up must not cut short.
  let serverNotBefore = 0;
  let lastCheckedAt = Number.NEGATIVE_INFINITY;

  function clearRetry() {
    cancelRetry?.();
    cancelRetry = undefined;
  }

  function clearPolling() {
    if (cancelPolling) debug("Polling disabled");
    cancelPolling?.();
    cancelPolling = undefined;
  }

  function ensurePolling() {
    if (
      options.background &&
      !stopped &&
      !automaticBlocked &&
      !streaming &&
      cancelPolling === undefined
    ) {
      const intervalMs = POLL_INTERVAL_MS * (0.9 + Math.random() * 0.2);
      debug("Polling scheduled", { intervalMs: Math.ceil(intervalMs) });
      cancelPolling = clock.setTimeout(() => {
        cancelPolling = undefined;
        refreshInBackground();
        ensurePolling();
      }, intervalMs);
    }
  }

  function isBehindWantedVersion() {
    return (
      wantedVersion !== undefined &&
      (version === undefined || version < wantedVersion)
    );
  }

  function scheduleVersionRefresh() {
    if (cancelVersionRefresh !== undefined || inFlight !== undefined) return;
    cancelVersionRefresh = clock.setTimeout(() => {
      cancelVersionRefresh = undefined;
      if (isBehindWantedVersion()) refreshInBackground();
    }, Math.random() * MAX_VERSION_REFRESH_DELAY_MS);
  }

  function scheduleRetry(error: unknown) {
    if (!options.background || stopped) return;
    const backoff = Math.min(
      MAX_RETRY_MS,
      1000 * 2 ** Math.min(retryAttempt++, 5),
    );
    const jittered = Math.min(
      MAX_RETRY_MS,
      backoff * (0.8 + Math.random() * 0.4),
    );
    const delay = Math.max(
      jittered,
      error instanceof RefreshError ? error.retryAfterMs : 0,
    );
    notBefore = clock.now() + delay;
    if (error instanceof RefreshError && error.retryAfterMs > 0) {
      serverNotBefore = Math.max(
        serverNotBefore,
        clock.now() + error.retryAfterMs,
      );
    }
    clearRetry();
    debug("Refresh retry scheduled", {
      delayMs: Math.ceil(delay),
      attempt: retryAttempt,
    });
    // Chunk very long Retry-After delays rather than overflowing setTimeout.
    cancelRetry = clock.setTimeout(
      () => {
        cancelRetry = undefined;
        refreshInBackground();
      },
      Math.min(delay, 2_147_483_647),
    );
  }

  async function run() {
    const controller = new AbortController();
    activeRequest = controller;
    try {
      do {
        refreshAgain = false;
        while (clock.now() < notBefore) {
          await wait(
            Math.min(notBefore - clock.now(), 2_147_483_647),
            controller.signal,
            clock,
          );
        }
        if (stopped) return;

        debug("Refresh started");
        cancelRequestTimeout = clock.setTimeout(() => {
          controller.abort(
            new DOMException("Flagbearer refresh timed out", "TimeoutError"),
          );
        }, REQUEST_TIMEOUT_MS);

        const result = await options.fetchSnapshot(controller.signal);
        if (result !== null && !isVersion(result.version)) {
          throw new RefreshError("Invalid snapshot version", false);
        }
        cancelRequestTimeout?.();
        cancelRequestTimeout = undefined;
        if (stopped) return;
        lastCheckedAt = clock.now();

        if (result === null) {
          debug("Snapshot unchanged", { version });
          if (isBehindWantedVersion()) {
            // The backend is still behind: retry later rather than spin.
            scheduleRetry(undefined);
            return;
          }
          retryAttempt = 0;
          notBefore = 0;
          continue;
        }

        const previousVersion = version;
        if (version === undefined || result.version >= version) {
          version = result.version;
          options.applySnapshot(result);
          debug("Snapshot applied", { version, previousVersion });
        } else {
          debug("Older snapshot ignored", {
            version: result.version,
            currentVersion: version,
          });
        }
        if (stopped) return;

        // An SSE event may have arrived while this request was running.
        if (isBehindWantedVersion()) {
          if (
            previousVersion !== undefined &&
            result.version <= previousVersion
          ) {
            // The backend is still behind: retry later rather than spin.
            scheduleRetry(undefined);
            return;
          }
          refreshAgain = true;
        } else {
          retryAttempt = 0;
          notBefore = 0;
        }
      } while (refreshAgain);
    } catch (error) {
      if (stopped) return;
      options.onRefreshFailed?.(error);
      if (error instanceof RefreshError && !error.retryable) {
        // Said once, without debug on: retrying can't fix this, and silent
        // defaults look like flags that are simply off.
        console.error(
          `[Flagbearer] Flags have stopped updating and keep their current or default values until this is fixed. ${error.message}`,
        );
        automaticBlocked = true;
        streaming = false;
        clearRetry();
        clearPolling();
        options.onPermanentFailure();
      } else {
        scheduleRetry(error);
      }
      throw error;
    } finally {
      cancelRequestTimeout?.();
      cancelRequestTimeout = undefined;
      activeRequest = undefined;
    }
  }

  function begin(): Promise<void> {
    clearRetry();
    inFlight = run().finally(() => {
      inFlight = undefined;
    });
    return inFlight;
  }

  function refreshInBackground() {
    if (
      stopped ||
      automaticBlocked ||
      cancelRetry !== undefined ||
      inFlight !== undefined
    )
      return;
    void begin().catch((error) => {
      // A permanent failure has already been explained by run().
      if (error instanceof RefreshError && !error.retryable) return;
      console.error("Flagbearer sync failed", error);
    });
  }

  function sync(): Promise<void> {
    if (stopped)
      return Promise.reject(new Error("Flagbearer client is closed"));
    if (inFlight) {
      debug("Joining active refresh");
      // Share the active batch; only newer version announcements need a follow-up.
      return inFlight;
    }
    automaticBlocked = false;
    ensurePolling();
    return begin();
  }

  function onVersion(nextVersion: number) {
    if (stopped) return;
    debug("Stream version received", {
      version: nextVersion,
      currentVersion: version,
    });
    wantedVersion = Math.max(wantedVersion ?? 0, nextVersion);
    if (isBehindWantedVersion()) scheduleVersionRefresh();
  }

  function setStreaming({
    connected,
    refresh = true,
  }: {
    connected: boolean;
    refresh?: boolean;
  }) {
    if (stopped) return;
    if (streaming !== connected) {
      debug(
        connected ? "Stream connected" : "Stream disconnected; using polling",
      );
    }
    streaming = connected;
    if (connected) {
      clearPolling();
      if (refresh) refreshInBackground();
    } else {
      ensurePolling();
    }
  }

  // A page that comes back (tab shown, laptop woken, network back) rechecks
  // its flags unless they were checked moments ago.
  function resume() {
    if (stopped || automaticBlocked) return;
    if (clock.now() - lastCheckedAt < (options.resumeStaleMs ?? 15_000)) {
      ensurePolling();
      return;
    }
    // Waking cancels the SDK's own backoff, never the server's Retry-After.
    if (clock.now() >= serverNotBefore) {
      clearRetry();
      notBefore = 0;
      retryAttempt = 0;
    }
    clearPolling();
    refreshInBackground();
    ensurePolling();
  }

  // A long-hidden page has closed its stream and stops polling until resume().
  function pause() {
    if (stopped) return;
    streaming = false;
    clearPolling();
  }

  function close() {
    if (stopped) return;
    stopped = true;
    debug("Coordinator closed");
    clearRetry();
    clearPolling();
    cancelVersionRefresh?.();
    cancelVersionRefresh = undefined;
    cancelRequestTimeout?.();
    cancelRequestTimeout = undefined;
    activeRequest?.abort();
  }

  ensurePolling();
  return {
    sync,
    refreshInBackground,
    onVersion,
    setStreaming,
    resume,
    pause,
    close,
  };
}
