import {
  createDebugLogger,
  createExposureReporter,
  createRefreshCoordinator,
  isEvaluationResponse,
  isVersionEvent,
  readJsonResponse,
  RefreshError,
  type EvaluationDetails,
} from "@flagbearer/sdk-core";

import { SDK_VERSION } from "./version";

export type User = { key?: string; email?: string };

export type ClientOptions = {
  clientKey: string;
  url?: string;
  user?: User;
  debug?: boolean;
  appVersion?: string;
};

export type FlagsSnapshot = Readonly<Record<string, boolean>>;

// loading: no flags yet for the current user, so isEnabled returns defaults.
// ready: the current user's flags have arrived. They stay ready through later
// failed refreshes, keeping the last flags.
// failed: fetching the current user's flags failed before any arrived.
export type FlagsStatus = "loading" | "ready" | "failed";

const EMPTY_FLAGS: FlagsSnapshot = Object.freeze({});
const DEFAULT_URL = "https://sdk.flagbearer.dev";
// The server pings every 15 seconds, so this is three missed pings. A stream
// that slept with the laptop can look open while nothing arrives.
const STREAM_SILENT_MS = 45_000;
// A tab hidden this long drops its stream and stops polling until it's shown.
const IDLE_HIDDEN_MS = 5 * 60_000;
const ANONYMOUS_ID_KEY = "flagbearer:anon";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// A random ID kept in this browser, so a signed-out visitor counts as one
// person per day however often they reload. It's never used for targeting.
// Where storage is blocked there's no ID, and the server falls back to the
// visitor's IP address and browser.
function loadAnonymousId(): string | undefined {
  try {
    const saved = localStorage.getItem(ANONYMOUS_ID_KEY);
    // The server rejects a malformed ID, which would block flags, so replace it.
    if (saved && UUID.test(saved)) return saved;
    const id = crypto.randomUUID();
    localStorage.setItem(ANONYMOUS_ID_KEY, id);
    return id;
  } catch {
    return undefined;
  }
}

export function init({
  clientKey,
  url = DEFAULT_URL,
  user = {},
  debug = false,
  appVersion,
}: ClientOptions) {
  const log = createDebugLogger(debug);
  const baseUrl = new URL(url);

  if (baseUrl.protocol !== "http:" && baseUrl.protocol !== "https:") {
    throw new Error("Flagbearer url must use HTTP or HTTPS");
  }

  const evaluateUrl = new URL("/v1/evaluate", baseUrl);
  const streamUrl = new URL("/v1/stream", baseUrl);
  streamUrl.searchParams.set("clientKey", clientKey);

  const reporter = createExposureReporter({
    url: new URL("/v1/exposures", baseUrl),
    key: clientKey,
    sdk: "web",
    sdkVersion: SDK_VERSION,
    appVersion,
  });
  let currentUser = { ...user };
  let configurationVersion: number | null = null;
  let details: Record<string, EvaluationDetails> = {};
  let snapshot = EMPTY_FLAGS;
  let status: FlagsStatus = "loading";
  let closed = false;
  const listeners = new Set<() => void>();
  const isBrowser = typeof window !== "undefined";
  const anonymousId = isBrowser ? loadAnonymousId() : undefined;
  let stream: EventSource | undefined;
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

  function createCoordinator(nextUser: User) {
    const currentUser = { ...nextUser };
    return createRefreshCoordinator({
      debug,
      async fetchSnapshot(signal) {
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
            anonymousId,
          }),
        });
        if (response.status === 204) return null;
        const result = await readJsonResponse(response, signal);
        if (!isEvaluationResponse(result)) {
          throw new RefreshError("Invalid flag evaluation response", false);
        }

        return result;
      },
      background: isBrowser,
      applySnapshot: (result) => {
        configurationVersion = result.version;
        details = result.details ?? {};
        publish(result.flags);
        setStatus("ready");
      },
      onRefreshFailed: () => {
        if (status === "loading") setStatus("failed");
      },
      onPermanentFailure: disconnectStream,
    });
  }

  let coordinator = createCoordinator(user);

  function sync(): Promise<void> {
    return coordinator.sync();
  }

  async function identify(nextUser: User): Promise<void> {
    if (closed) throw new Error("Flagbearer client is closed");
    // The same user again changes nothing, so their flags stay as they are.
    if (
      nextUser.key === currentUser.key &&
      nextUser.email === currentUser.email
    )
      return;
    coordinator.close();
    currentUser = { ...nextUser };
    details = {};
    configurationVersion = null;
    coordinator = createCoordinator(nextUser);
    coordinator.setStreaming({
      connected: stream !== undefined && stream.readyState === EventSource.OPEN,
      refresh: false,
    });
    connectStream();
    setStatus("loading");
    publish(EMPTY_FLAGS);
    if (!closed) await sync();
  }

  function onVersion(event: Event) {
    if (!(event instanceof MessageEvent) || closed) return;
    onStreamMessage();
    let data: unknown;
    try {
      data = JSON.parse(event.data);
    } catch {
      return;
    }
    if (isVersionEvent(data)) {
      coordinator.onVersion(data.version);
    }
  }

  function onStreamMessage() {
    lastStreamMessageAt = Date.now();
  }

  function onOpen() {
    onStreamMessage();
    coordinator.setStreaming({ connected: true, refresh: hasConnected });
    hasConnected = true;
  }

  function onError() {
    coordinator.setStreaming({ connected: false });
  }

  function connectStream() {
    if (closed || stream || !isBrowser || typeof EventSource === "undefined")
      return;
    try {
      log("Stream connecting");
      // A new connection gets the full silence allowance before it's replaced.
      onStreamMessage();
      stream = new EventSource(streamUrl.toString());
    } catch (error) {
      coordinator.setStreaming({ connected: false });
      console.error("Flagbearer stream unavailable; using polling", error);
      return;
    }
    stream.addEventListener("open", onOpen);
    stream.addEventListener("error", onError);
    stream.addEventListener("version", onVersion);
    stream.addEventListener("ping", onStreamMessage);
  }

  function disconnectStream() {
    if (!stream) return;
    stream.removeEventListener("open", onOpen);
    stream.removeEventListener("error", onError);
    stream.removeEventListener("version", onVersion);
    stream.removeEventListener("ping", onStreamMessage);
    stream.close();
    stream = undefined;
    log("Stream closed");
  }

  function close() {
    if (closed) return;
    closed = true;
    coordinator.close();
    disconnectStream();
    listeners.clear();
    clearTimeout(hiddenTimer);
    if (isBrowser) {
      window.removeEventListener("pagehide", onPageHide);
      window.removeEventListener("pageshow", onPageShow);
      window.removeEventListener("online", onResume);
      window.removeEventListener("focus", onResume);
      document.removeEventListener("visibilitychange", onVisibilityChange);
    }
    void reporter.close().catch(() => {});
  }

  function onPageHide() {
    void reporter.flush().catch(() => {});
  }

  // Every way a page comes back: tab shown, restored from the back/forward
  // cache, network back, window focused.
  function onResume() {
    if (closed) return;
    clearTimeout(hiddenTimer);
    hiddenTimer = undefined;
    const silent = Date.now() - lastStreamMessageAt > STREAM_SILENT_MS;
    if (!stream || stream.readyState === EventSource.CLOSED || silent) {
      disconnectStream();
      connectStream();
    }
    coordinator.resume();
  }

  function onHidden() {
    clearTimeout(hiddenTimer);
    hiddenTimer = setTimeout(() => {
      hiddenTimer = undefined;
      disconnectStream();
      coordinator.pause();
    }, IDLE_HIDDEN_MS);
  }

  function onVisibilityChange() {
    if (document.visibilityState === "visible") onResume();
    else onHidden();
  }

  function onPageShow(event: PageTransitionEvent) {
    if (event.persisted) onResume();
  }

  if (isBrowser) {
    window.addEventListener("pagehide", onPageHide);
    window.addEventListener("pageshow", onPageShow);
    window.addEventListener("online", onResume);
    window.addEventListener("focus", onResume);
    document.addEventListener("visibilitychange", onVisibilityChange);
    // Opened in a background tab: start the hidden countdown now.
    if (document.visibilityState === "hidden") onHidden();
  }
  connectStream();
  coordinator.refreshInBackground();

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

export type Client = ReturnType<typeof init>;
