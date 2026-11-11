import {
  checkResponseWithReason,
  createDebugLogger,
  isVersionEvent,
  RefreshError,
} from "@flagbearer/sdk-core";

type StreamOptions = {
  url: URL;
  headers: Record<string, string>;
  debug?: boolean;
  onVersion(version: number): void;
  onOpen(): void;
  onDisconnect(): void;
};

const CONNECT_TIMEOUT_MS = 5000;
const IDLE_TIMEOUT_MS = 45_000; // The server sends a heartbeat every 15 seconds.
const MAX_RETRY_MS = 30_000;
const MAX_EVENT_LENGTH = 65_536;

// Only the authenticated Node transport lives here; refresh/version logic is shared.
export function createVersionStream(options: StreamOptions) {
  const debug = createDebugLogger(options.debug);
  let closed = false;
  let active: AbortController | undefined;
  let timeout: ReturnType<typeof setTimeout> | undefined;
  let retry: ReturnType<typeof setTimeout> | undefined;
  let attempts = 0;

  function armTimeout(controller: AbortController, delay: number) {
    clearTimeout(timeout);
    timeout = setTimeout(() => controller.abort(), delay);
  }

  function reconnect(error: unknown) {
    const backoff = Math.min(MAX_RETRY_MS, 1000 * 2 ** Math.min(attempts++, 5));
    const jittered = Math.min(
      MAX_RETRY_MS,
      backoff * (0.8 + Math.random() * 0.4),
    );
    const delay = Math.max(
      jittered,
      error instanceof RefreshError ? error.retryAfterMs : 0,
    );
    const notBefore = Date.now() + delay;
    debug("Stream reconnect scheduled", { delayMs: Math.ceil(delay) });
    function schedule() {
      if (closed) return;
      const remaining = notBefore - Date.now();
      if (remaining <= 0) {
        retry = undefined;
        void connect();
      } else {
        retry = setTimeout(schedule, Math.min(remaining, 2_147_483_647));
      }
    }
    schedule();
  }

  async function connect() {
    if (closed) return;
    debug("Stream connecting");
    const controller = new AbortController();
    active = controller;
    let failure: unknown;

    try {
      armTimeout(controller, CONNECT_TIMEOUT_MS);
      const response = await fetch(options.url, {
        headers: { ...options.headers, Accept: "text/event-stream" },
        signal: controller.signal,
        credentials: "omit",
        cache: "no-store",
        redirect: "error",
      });
      await checkResponseWithReason(response);
      const contentType = response.headers
        .get("content-type")
        ?.split(";")[0]
        ?.trim()
        .toLowerCase();
      if (contentType !== "text/event-stream" || !response.body) {
        throw new RefreshError("Invalid Flagbearer stream response", false);
      }
      if (closed) return;
      armTimeout(controller, IDLE_TIMEOUT_MS);
      options.onOpen();

      const decoder = new TextDecoder();
      const reader = response.body.getReader();
      let buffer = "";
      let event = "message";
      let data = "";

      function line(value: string) {
        if (value === "") {
          if (event === "version" && data !== "") {
            let parsed: unknown;
            try {
              parsed = JSON.parse(data);
            } catch {
              /* Ignore malformed announcements. */
            }
            if (isVersionEvent(parsed)) options.onVersion(parsed.version);
          }
          event = "message";
          data = "";
          return;
        }
        if (value.startsWith(":")) return;
        const separator = value.indexOf(":");
        const field = separator === -1 ? value : value.slice(0, separator);
        let content = separator === -1 ? "" : value.slice(separator + 1);
        if (content.startsWith(" ")) content = content.slice(1);
        if (field === "event") event = content;
        if (field === "data") data += `${content}\n`;
        if (data.length > MAX_EVENT_LENGTH)
          throw new Error("Flagbearer stream event too large");
      }

      try {
        while (!closed) {
          const chunk = await reader.read();
          if (chunk.done) break;
          armTimeout(controller, IDLE_TIMEOUT_MS);
          attempts = 0;
          buffer += decoder.decode(chunk.value, { stream: true });

          // Handle LF, CRLF, and CR even when a line ending crosses chunks.
          let start = 0;
          for (let index = 0; index < buffer.length; index++) {
            const char = buffer[index];
            if (char !== "\r" && char !== "\n") continue;
            if (char === "\r" && index === buffer.length - 1) break;
            line(buffer.slice(start, index));
            if (char === "\r" && buffer[index + 1] === "\n") index++;
            start = index + 1;
          }
          buffer = buffer.slice(start);
          if (buffer.length > MAX_EVENT_LENGTH)
            throw new Error("Flagbearer stream line too large");
        }
      } finally {
        reader.releaseLock();
      }
    } catch (error) {
      failure = error;
    } finally {
      clearTimeout(timeout);
      controller.abort();
      active = undefined;
    }

    if (closed) return;
    options.onDisconnect();
    if (failure instanceof RefreshError && !failure.retryable) {
      // Polling remains available; the next ruleset request also checks the key.
      console.error(
        `[Flagbearer] Live updates have stopped, so flag changes arrive by polling. ${failure.message}`,
      );
      return;
    }
    reconnect(failure);
  }

  function close() {
    if (closed) return;
    closed = true;
    debug("Stream closed");
    clearTimeout(timeout);
    clearTimeout(retry);
    active?.abort();
  }

  void connect();
  return { close };
}
