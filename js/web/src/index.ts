import { createClient, type Platform } from "@flagbearer/sdk-core";

import { SDK_VERSION } from "./version";

// Public types are declared here, not imported, so the published declarations
// don't depend on the private core package.
export type User = { key?: string; email?: string };

// localStorage fits this as-is.
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
  // Defaults to localStorage. null keeps nothing.
  storage?: Storage | null;
  // Start with the flags this user last had, until fresh ones arrive. Off by
  // default.
  cacheFlags?: boolean;
};

export type FlagsSnapshot = Readonly<Record<string, boolean>>;

// Empty until a file from `flagbearer generate types` fills it in. Then flag
// keys are checked and autocomplete; without one, any string works.
export interface Flags {}

export type FlagKey = keyof Flags extends never ? string : keyof Flags & string;

// loading: no flags yet for the current user, so isEnabled returns defaults.
// ready: the current user's flags have arrived. They stay ready through later
// failed refreshes, keeping the last flags.
// failed: fetching the current user's flags failed before any arrived.
export type FlagsStatus = "loading" | "ready" | "failed";

export type Client = {
  sync(): Promise<void>;
  isEnabled(flag: FlagKey, defaultValue?: boolean): boolean;
  // Every flag's current value, for error reports. Doesn't count as checking
  // them.
  allFlags(): { [Key in FlagKey]?: boolean };
  identify(nextUser: User): Promise<void>;
  subscribe(listener: () => void): () => void;
  getSnapshot(): FlagsSnapshot;
  getStatus(): FlagsStatus;
  flush(): Promise<void>;
  close(): void;
};

// Reading localStorage can throw where storage is blocked.
function browserStorage(): Storage | undefined {
  try {
    return typeof localStorage === "undefined" ? undefined : localStorage;
  } catch {
    return undefined;
  }
}

function browserPlatform(): Platform {
  const isBrowser = typeof window !== "undefined";
  return {
    sdk: "web",
    sdkVersion: SDK_VERSION,
    background: isBrowser,
    storage: isBrowser ? browserStorage : undefined,
    cacheFlags: false,
    // Every way a page comes back: tab shown, restored from the back/forward
    // cache, network back, window focused.
    watchLifecycle: isBrowser
      ? ({ resume, hide, leave }) => {
          const onVisibilityChange = () => {
            if (document.visibilityState === "visible") resume();
            else hide();
          };
          const onPageShow = (event: PageTransitionEvent) => {
            if (event.persisted) resume();
          };
          window.addEventListener("pagehide", leave);
          window.addEventListener("pageshow", onPageShow);
          window.addEventListener("online", resume);
          window.addEventListener("focus", resume);
          document.addEventListener("visibilitychange", onVisibilityChange);
          // Opened in a background tab: start the hidden countdown now.
          if (document.visibilityState === "hidden") hide();
          return () => {
            window.removeEventListener("pagehide", leave);
            window.removeEventListener("pageshow", onPageShow);
            window.removeEventListener("online", resume);
            window.removeEventListener("focus", resume);
            document.removeEventListener(
              "visibilitychange",
              onVisibilityChange,
            );
          };
        }
      : undefined,
    openStream:
      isBrowser && typeof EventSource !== "undefined"
        ? (url, handlers) => {
            const source = new EventSource(url);
            const onVersion = (event: Event) => {
              if (event instanceof MessageEvent) handlers.version(event.data);
            };
            source.addEventListener("open", handlers.open);
            source.addEventListener("error", handlers.error);
            source.addEventListener("version", onVersion);
            source.addEventListener("ping", handlers.ping);
            return {
              get state() {
                if (source.readyState === EventSource.OPEN) return "open";
                if (source.readyState === EventSource.CLOSED) return "closed";
                return "connecting";
              },
              close() {
                source.removeEventListener("open", handlers.open);
                source.removeEventListener("error", handlers.error);
                source.removeEventListener("version", onVersion);
                source.removeEventListener("ping", handlers.ping);
                source.close();
              },
            };
          }
        : undefined,
  };
}

export function init(options: ClientOptions): Client {
  return createClient(options, browserPlatform());
}
