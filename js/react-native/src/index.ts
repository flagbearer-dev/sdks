import { AppState } from "react-native";

import { createClient } from "@flagbearer/sdk-core";

import { SDK_VERSION } from "./version";

// Public types are declared here, not imported, so the published declarations
// don't depend on the private core package.
export type User = { key?: string; email?: string };

// AsyncStorage fits this as-is. Other stores, like MMKV, need a small wrapper.
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
  // Keeps the anonymous ID and the last flags between launches. Without it,
  // nothing is kept.
  storage?: Storage | null;
  // Start with the flags this user last had, until fresh ones arrive. On by
  // default when there's storage.
  cacheFlags?: boolean;
};

export type FlagsSnapshot = Readonly<Record<string, boolean>>;

// loading: no flags yet for the current user, so isEnabled returns defaults.
// ready: the current user's flags have arrived, from the server or the cache.
// failed: fetching the current user's flags failed before any arrived.
export type FlagsStatus = "loading" | "ready" | "failed";

export type Client = {
  sync(): Promise<void>;
  isEnabled(flag: string, defaultValue?: boolean): boolean;
  identify(nextUser: User): Promise<void>;
  subscribe(listener: () => void): () => void;
  getSnapshot(): FlagsSnapshot;
  getStatus(): FlagsStatus;
  flush(): Promise<void>;
  close(): void;
};

// React Native has no EventSource, so flag changes arrive with the next poll,
// about every 30 seconds.
export function init(options: ClientOptions): Client {
  return createClient(options, {
    sdk: "react-native",
    sdkVersion: SDK_VERSION,
    background: true,
    cacheFlags: true,
    watchLifecycle({ resume, hide, leave }) {
      // "inactive" is brief, like the iOS app switcher, so it changes nothing.
      const subscription = AppState.addEventListener("change", (state) => {
        if (state === "active") {
          resume();
        } else if (state === "background") {
          leave();
          hide();
        }
      });
      if (AppState.currentState === "background") hide();
      return () => subscription.remove();
    },
  });
}
