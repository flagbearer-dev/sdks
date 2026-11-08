import { createDebugLogger } from "./debug.js";
import { createExposureReporter } from "./exposures.js";
import { randomId } from "./platform.js";
import {
  createRefreshCoordinator,
  readJsonResponse,
  RefreshError,
} from "./refresh.js";
import type { EvaluationDetails, EvaluationResponse } from "./types.js";
import {
  isEvaluationResponse,
  isRecord,
  isVersionEvent,
} from "./validation.js";

export type User = { key?: string; email?: string };

export type FlagsSnapshot = Readonly<Record<string, boolean>>;

// loading: no flags yet for the current user, so isEnabled returns defaults.
// ready: the current user's flags have arrived, from the server or the cache.
// They stay ready through later failed refreshes, keeping the last flags.
// failed: fetching the current user's flags failed before any arrived.
export type FlagsStatus = "loading" | "ready" | "failed";

// localStorage, AsyncStorage and similar stores fit this as-is.
export type Storage = {
  getItem(key: string): string | null | Promise<string | null>;
  setItem(key: string, value: string): void | Promise<void>;
  removeItem(key: string): void | Promise<void>;
};

export type ClientOptions = {
  clientKey: string;
  url?: string;
  user?: User;
  debug?: boolean;
  appVersion?: string;
  // Where the anonymous ID and cached flags are kept. null keeps nothing.
  storage?: Storage | null;
  // Start with the flags this user last had, until fresh ones arrive.
  cacheFlags?: boolean;
};

export type StreamHandlers = {
  open(): void;
  error(): void;
  version(data: string): void;
  ping(): void;
};

export type Stream = {
  readonly state: "connecting" | "open" | "closed";
  close(): void;
};

export type LifecycleEvents = {
  // The app is back: shown, focused, online again.
  resume(): void;
  // The app is hidden or in the background.
  hide(): void;
  // The app may be about to go away, so upload what's pending.
  leave(): void;
};

// What differs between web and React Native.
export type Platform = {
  sdk: string;
  sdkVersion: string;
  // Poll and retry on its own. Off outside browsers and apps.
  background: boolean;
  storage?: () => Storage | undefined;
  cacheFlags: boolean;
  watchLifecycle?: (events: LifecycleEvents) => () => void;
  openStream?: (url: string, handlers: StreamHandlers) => Stream;
};

const EMPTY_FLAGS: FlagsSnapshot = Object.freeze({});
const DEFAULT_URL = "https://sdk.flagbearer.dev";
// The server pings every 15 seconds, so this is three missed pings. A stream
// that slept with the device can look open while nothing arrives.
const STREAM_SILENT_MS = 45_000;
// Hidden this long, the client drops its stream and stops polling until it's
// back.
const IDLE_HIDDEN_MS = 5 * 60_000;
// Cached flags older than this are ignored, so a flag turned off doesn't stay
// on for long on a device that's been offline.
const MAX_CACHE_AGE_MS = 7 * 86_400_000;
const ANONYMOUS_ID_KEY = "flagbearer:anon";
const CACHE_KEY = "flagbearer:flags";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

type CachedFlags = {
  clientKey: string;
  user: string;
  savedAt: number;
  snapshot: EvaluationResponse;
};

function cacheUser(user: User) {
  return JSON.stringify([user.key ?? null, user.email ?? null]);
}

// A random ID kept on this device, so a signed-out visitor counts as one
// person per day however often they come back. It's never used for targeting.
// Without storage there's no ID, and the server falls back to the visitor's IP
// address and browser.
async function loadAnonymousId(storage: Storage | undefined) {
  if (!storage) return undefined;
  try {
    const saved = await storage.getItem(ANONYMOUS_ID_KEY);
    // The server rejects a malformed ID, which would block flags, so replace it.
    if (saved && UUID.test(saved)) return saved;
    const id = randomId();
    await storage.setItem(ANONYMOUS_ID_KEY, id);
    return id;
  } catch {
    return undefined;
  }
}

export function createClient(
  {
    clientKey,
    url = DEFAULT_URL,
    user = {},
    debug = false,
    appVersion,
    storage: storageOption,
    cacheFlags,
  }: ClientOptions,
  platform: Platform,
) {
  const log = createDebugLogger(debug);
  // Built as strings: React Native's URL doesn't support everything browsers do.
  const origin = /^https?:\/\/[^/?#]+/i.exec(url)?.[0];
  if (!origin) throw new Error("Flagbearer url must use HTTP or HTTPS");

  const evaluateUrl = `${origin}/v1/evaluate`;
  const streamUrl = `${origin}/v1/stream?clientKey=${encodeURIComponent(clientKey)}`;

  const reporter = createExposureReporter({
    url: `${origin}/v1/exposures`,
    key: clientKey,
    sdk: platform.sdk,
    sdkVersion: platform.sdkVersion,
    appVersion,
  });
  const storage =
    storageOption === null
      ? undefined
      : (storageOption ?? platform.storage?.());
  const caching = (cacheFlags ?? platform.cacheFlags) && storage !== undefined;
  const anonymousId = loadAnonymousId(storage);
  let currentUser = { ...user };
  let configurationVersion: number | null = null;
  let details: Record<string, EvaluationDetails> = {};
  let snapshot = EMPTY_FLAGS;
  let status: FlagsStatus = "loading";
  let closed = false;
  const listeners = new Set<() => void>();
  let stream: Stream | undefined;
  let hasConnected = false;
  let lastStreamMessageAt = 0;
  let hiddenTimer: ReturnType<typeof setTimeout> | undefined;

  // Plain data: reading it doesn't report exposures. isEnabled does.
  function getSnapshot(): FlagsSnapshot {
    return snapshot;
  }

  function subscribe(listener: () => void) {
    if (!closed) listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  }

  function publish(next: FlagsSnapshot) {
    const keys = Object.keys(next);
    if (
      keys.length === Object.keys(snapshot).length &&
      keys.every(
        (key) => Object.hasOwn(snapshot, key) && snapshot[key] === next[key],
      )
    ) {
      return;
    }

    snapshot = Object.freeze({ ...next });
    notify();
  }

  function setStatus(next: FlagsStatus) {
    if (status === next) return;
    status = next;
    notify();
  }

  function notify() {
    for (const listener of [...listeners]) {
      if (closed) break;
      if (!listeners.has(listener)) continue;
      try {
        listener();
      } catch (error) {
        console.error("Flagbearer subscriber failed", error);
      }
    }
  }

  function isEnabled(flag: string, defaultValue = false): boolean {
    const value = Object.hasOwn(snapshot, flag)
      ? snapshot[flag]!
      : defaultValue;
    report(flag, value);
    return value;
  }

  function report(flag: string, value: boolean) {
    const evaluation = Object.hasOwn(details, flag) ? details[flag] : undefined;
    if (!evaluation && Object.hasOwn(snapshot, flag)) return;
    reporter.record(
      flag,
      currentUser,
      evaluation
        ? { ...evaluation, value }
        : {
            value,
            reason: "fallback",
            configurationVersion,
          },
    );
  }

  async function readCache(forUser: User) {
    if (!caching) return undefined;
    try {
      const saved = await storage!.getItem(CACHE_KEY);
      if (!saved) return undefined;
      const cached: unknown = JSON.parse(saved);
      if (
        !isRecord(cached) ||
        cached.clientKey !== clientKey ||
        cached.user !== cacheUser(forUser) ||
        typeof cached.savedAt !== "number" ||
        Date.now() - cached.savedAt > MAX_CACHE_AGE_MS ||
        !isEvaluationResponse(cached.snapshot)
      ) {
        return undefined;
      }
      return cached.snapshot;
    } catch {
      return undefined;
    }
  }

  async function writeCache(forUser: User, result: EvaluationResponse) {
    if (!caching) return;
    const cached: CachedFlags = {
      clientKey,
      user: cacheUser(forUser),
      savedAt: Date.now(),
      snapshot: result,
    };
    try {
      await storage!.setItem(CACHE_KEY, JSON.stringify(cached));
    } catch {
      // A full or blocked store only loses the cache.
    }
  }

  function createCoordinator(nextUser: User) {
    const currentUser = { ...nextUser };
    let fresh = false;
    const coordinator = createRefreshCoordinator({
      debug,
      async fetchSnapshot(signal) {
        const anonymous = await anonymousId;
        const response = await fetch(evaluateUrl, {
          method: "POST",
          credentials: "omit",
          headers: { "Content-Type": "application/json" },
          signal,
          body: JSON.stringify({
            clientKey,
            includeDetails: true,
            knownVersion: configurationVersion ?? undefined,
            userKey: currentUser.key,
            userEmail: currentUser.email,
            anonymousId: anonymous,
          }),
        });
        if (response.status === 204) return null;
        const result = await readJsonResponse(response, signal);
        if (!isEvaluationResponse(result)) {
          throw new RefreshError("Invalid flag evaluation response", false);
        }

        return result;
      },
      background: platform.background,
      applySnapshot: (result) => {
        fresh = true;
        configurationVersion = result.version;
        details = result.details ?? {};
        publish(result.flags);
        setStatus("ready");
        void writeCache(currentUser, result);
      },
      onRefreshFailed: () => {
        if (status === "loading") setStatus("failed");
      },
      onPermanentFailure: disconnectStream,
    });

    // The last flags this user had, until the server answers. Fresh flags
    // always win, even if the cache is slow to read.
    void readCache(currentUser).then((cached) => {
      if (!cached || fresh || closed || active !== coordinator) return;
      log("Cached flags applied");
      details = cached.details ?? {};
      publish(cached.flags);
      setStatus("ready");
    });

    return coordinator;
  }

  let active = createCoordinator(user);

  function sync(): Promise<void> {
    return active.sync();
  }

  async function identify(nextUser: User): Promise<void> {
    if (closed) throw new Error("Flagbearer client is closed");
    // The same user again changes nothing, so their flags stay as they are.
    if (
      nextUser.key === currentUser.key &&
      nextUser.email === currentUser.email
    )
      return;
    active.close();
    currentUser = { ...nextUser };
    details = {};
    configurationVersion = null;
    active = createCoordinator(nextUser);
    active.setStreaming({
      connected: stream?.state === "open",
      refresh: false,
    });
    connectStream();
    setStatus("loading");
    publish(EMPTY_FLAGS);
    if (!closed) await sync();
  }

  function onVersion(data: string) {
    if (closed) return;
    onStreamMessage();
    let parsed: unknown;
    try {
      parsed = JSON.parse(data);
    } catch {
      return;
    }
    if (isVersionEvent(parsed)) {
      active.onVersion(parsed.version);
    }
  }

  function onStreamMessage() {
    lastStreamMessageAt = Date.now();
  }

  function onOpen() {
    onStreamMessage();
    active.setStreaming({ connected: true, refresh: hasConnected });
    hasConnected = true;
  }

  function onError() {
    active.setStreaming({ connected: false });
  }

  function connectStream() {
    if (closed || stream || !platform.openStream) return;
    try {
      log("Stream connecting");
      // A new connection gets the full silence allowance before it's replaced.
      onStreamMessage();
      stream = platform.openStream(streamUrl, {
        open: onOpen,
        error: onError,
        version: onVersion,
        ping: onStreamMessage,
      });
    } catch (error) {
      active.setStreaming({ connected: false });
      console.error("Flagbearer stream unavailable; using polling", error);
    }
  }

  function disconnectStream() {
    if (!stream) return;
    stream.close();
    stream = undefined;
    log("Stream closed");
  }

  function close() {
    if (closed) return;
    closed = true;
    active.close();
    disconnectStream();
    listeners.clear();
    clearTimeout(hiddenTimer);
    unwatch?.();
    void reporter.close().catch(() => {});
  }

  function onLeave() {
    void reporter.flush().catch(() => {});
  }

  function onResume() {
    if (closed) return;
    clearTimeout(hiddenTimer);
    hiddenTimer = undefined;
    const silent = Date.now() - lastStreamMessageAt > STREAM_SILENT_MS;
    if (!stream || stream.state === "closed" || silent) {
      disconnectStream();
      connectStream();
    }
    active.resume();
  }

  function onHidden() {
    clearTimeout(hiddenTimer);
    hiddenTimer = setTimeout(() => {
      hiddenTimer = undefined;
      disconnectStream();
      active.pause();
    }, IDLE_HIDDEN_MS);
  }

  const unwatch = platform.watchLifecycle?.({
    resume: onResume,
    hide: onHidden,
    leave: onLeave,
  });
  connectStream();
  active.refreshInBackground();

  return {
    sync,
    isEnabled,
    identify,
    subscribe,
    getSnapshot,
    getStatus: () => status,
    flush: reporter.flush,
    close,
  };
}

export type Client = ReturnType<typeof createClient>;
