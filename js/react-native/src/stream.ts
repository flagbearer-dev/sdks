import type { Platform } from "@flagbearer/sdk-core";

type OpenStream = NonNullable<Platform["openStream"]>;

// The server pings every 15 seconds, so this is three missed pings.
const SILENT_MS = 45_000;
const MAX_RETRY_MS = 60_000;

function retryAfterMs(header: string | null) {
  if (header === null) return 0;
  const seconds = Number(header);
  const delay =
    Number.isFinite(seconds) && seconds >= 0
      ? seconds * 1000
      : Date.parse(header) - Date.now();
  return Number.isFinite(delay) ? Math.max(0, delay) : 0;
}

// React Native has no EventSource and its fetch can't read a response as it
// arrives, but its XMLHttpRequest can: responseText grows with each chunk.
// This reads the server's stream of flag changes from it, and reconnects when
// the stream ends, as a browser's EventSource does on its own.
export const openStream: OpenStream = (url, handlers) => {
  let state: "connecting" | "open" | "closed" = "connecting";
  let request: XMLHttpRequest | undefined;
  // How much of responseText has been read.
  let read = 0;
  let failures = 0;
  // The wait before reconnecting. The server sets it, spread out so its
  // streams don't all come back at once.
  let retryMs = 5000 + Math.random() * 10_000;
  let retryTimer: ReturnType<typeof setTimeout> | undefined;
  let silentTimer: ReturnType<typeof setTimeout> | undefined;

  function drop() {
    clearTimeout(silentTimer);
    silentTimer = undefined;
    if (!request) return;
    request.onprogress = null;
    request.onloadend = null;
    request.abort();
    request = undefined;
  }

  function ended(status: number, retryAfter: string | null) {
    drop();
    // Refused outright, like a revoked key: asking again won't help.
    if (status >= 400 && status < 500 && status !== 408 && status !== 429) {
      state = "closed";
      handlers.error();
      return;
    }
    state = "connecting";
    handlers.error();
    const backoff = Math.min(MAX_RETRY_MS, retryMs * 2 ** Math.min(failures, 4));
    failures++;
    retryTimer = setTimeout(
      connect,
      Math.max(backoff, retryAfterMs(retryAfter)),
    );
  }

  function watchSilence() {
    clearTimeout(silentTimer);
    silentTimer = setTimeout(() => ended(0, null), SILENT_MS);
  }

  function onEvent(block: string) {
    let name = "message";
    let data = "";
    for (const line of block.split("\n")) {
      const colon = line.indexOf(":");
      if (colon <= 0) continue;
      const value = line.slice(colon + 1).trim();
      const field = line.slice(0, colon);
      if (field === "event") name = value;
      else if (field === "data") data = value;
      else if (field === "retry" && /^\d+$/.test(value)) retryMs = Number(value);
    }
    if (name === "version") handlers.version(data);
    else if (name === "ping") handlers.ping();
  }

  function connect() {
    retryTimer = undefined;
    read = 0;
    const current = new XMLHttpRequest();
    request = current;
    current.open("GET", url);
    current.setRequestHeader("Accept", "text/event-stream");
    current.setRequestHeader("Cache-Control", "no-cache");
    current.onprogress = () => {
      if (current.status !== 200) return;
      const text = current.responseText;
      let end = text.indexOf("\n\n", read);
      if (end === -1) return;
      watchSilence();
      // Open only once something has arrived. Where a device holds the
      // response back until it's complete, the stream never opens and the SDK
      // keeps polling.
      if (state !== "open") {
        state = "open";
        failures = 0;
        handlers.open();
      }
      while (end !== -1 && request === current) {
        const block = text.slice(read, end);
        read = end + 2;
        onEvent(block);
        end = text.indexOf("\n\n", read);
      }
    };
    current.onloadend = () => {
      ended(current.status, current.getResponseHeader("Retry-After"));
    };
    watchSilence();
    current.send();
  }

  connect();
  return {
    get state() {
      return state;
    },
    close() {
      state = "closed";
      clearTimeout(retryTimer);
      retryTimer = undefined;
      drop();
    },
  };
};
