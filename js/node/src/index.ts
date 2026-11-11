import { evaluateFlag } from "@flagbearer/evaluator";
import {
  createExposureReporter,
  createRefreshCoordinator,
  isRuleset,
  readJsonResponse,
  RefreshError,
  type FlagRule,
} from "@flagbearer/sdk-core";

import { createVersionStream } from "./stream";
import { SDK_VERSION } from "./version";

export type SdkOptions = {
  key: string;
  url?: string;
  debug?: boolean;
  appVersion?: string;
};

export type EvaluationUser = { key?: string; email?: string };

// Empty until a file from `flagbearer generate types` fills it in. Then flag
// keys are checked and autocomplete; without one, any string works.
export interface Flags {}

export type FlagKey = keyof Flags extends never ? string : keyof Flags & string;

const DEFAULT_URL = "https://sdk.flagbearer.dev";

export function init({
  key,
  url = DEFAULT_URL,
  debug = false,
  appVersion,
}: SdkOptions) {
  if (!key || /\s/.test(key)) {
    throw new Error(
      "Flagbearer needs a server key (fbs_...) with no spaces. Create one on the environment's page in the Flagbearer dashboard.",
    );
  }
  if (key.startsWith("fbc_")) {
    throw new Error(
      "This is a Flagbearer client key (fbc_). @flagbearer/node needs a server key (fbs_). Create one on the environment's page in the Flagbearer dashboard.",
    );
  }

  const baseUrl = new URL(url);
  if (baseUrl.protocol !== "http:" && baseUrl.protocol !== "https:") {
    throw new Error("Flagbearer url must use HTTP or HTTPS");
  }

  const rulesetUrl = new URL("/v1/ruleset", baseUrl);
  const streamUrl = new URL("/v1/stream", baseUrl);
  const headers = { Authorization: `Bearer ${key}` };
  const reporter = createExposureReporter({
    url: new URL("/v1/exposures", baseUrl),
    key,
    sdk: "node",
    sdkVersion: SDK_VERSION,
    appVersion,
  });
  let configurationVersion: number | null = null;
  let rules = new Map<string, FlagRule>();
  let environmentId: string | undefined;
  let stream: ReturnType<typeof createVersionStream> | undefined;
  let hasConnected = false;
  let closed = false;
  const warnedFlags = new Set<string>();

  const coordinator = createRefreshCoordinator({
    debug,
    async fetchSnapshot(signal) {
      const response = await fetch(rulesetUrl, {
        method: "GET",
        headers:
          configurationVersion === null
            ? headers
            : { ...headers, "If-None-Match": `"${configurationVersion}"` },
        signal,
        credentials: "omit",
        cache: "no-store",
        redirect: "error",
      });
      if (response.status === 304) return null;
      const result = await readJsonResponse(response, signal);
      if (!isRuleset(result))
        throw new RefreshError("Invalid ruleset response", false);
      if (
        environmentId !== undefined &&
        result.environmentId !== environmentId
      ) {
        throw new RefreshError(
          "Ruleset environment changed unexpectedly",
          false,
        );
      }
      return result;
    },
    applySnapshot(snapshot) {
      const next = new Map(snapshot.flags.map((rule) => [rule.key, rule]));
      environmentId = snapshot.environmentId;
      configurationVersion = snapshot.version;
      rules = next;
    },
    background: true,
    onPermanentFailure: () => stream?.close(),
  });

  function evaluate(flag: string, user: EvaluationUser, defaultValue: boolean) {
    try {
      return evaluateFlag(
        rules.get(flag),
        user,
        configurationVersion,
        defaultValue,
      );
    } catch {
      return {
        value: defaultValue,
        reason: "fallback" as const,
        configurationVersion,
      };
    }
  }

  function isEnabled(
    flag: FlagKey,
    user: EvaluationUser,
    defaultValue = false,
  ): boolean {
    if (environmentId !== undefined && !rules.has(flag)) warnUnknown(flag);
    const details = evaluate(flag, user, defaultValue);
    reporter.record(flag, user, details);
    return details.value;
  }

  // Once per flag: a misspelled or deleted flag otherwise looks like one
  // that's off.
  function warnUnknown(flag: string) {
    if (warnedFlags.has(flag) || warnedFlags.size >= 100) return;
    warnedFlags.add(flag);
    console.warn(
      `[Flagbearer] No flag "${flag}" exists in this environment, so isEnabled returns its default. Check the spelling, or create the flag.`,
    );
  }

  // Every flag's value for this user, for error reports. Doesn't count as
  // checking them, so it reports no exposures.
  function allFlags(user: EvaluationUser): { [Key in FlagKey]?: boolean } {
    const values: Record<string, boolean> = {};
    for (const flag of rules.keys()) {
      values[flag] = evaluate(flag, user, false).value;
    }
    return values;
  }

  function close() {
    if (closed) return;
    closed = true;
    coordinator.close();
    stream?.close();
    void reporter.close().catch(() => {});
  }

  stream = createVersionStream({
    debug,
    url: streamUrl,
    headers,
    onVersion: coordinator.onVersion,
    onOpen() {
      coordinator.setStreaming({ connected: true, refresh: hasConnected });
      hasConnected = true;
    },
    onDisconnect: () => coordinator.setStreaming({ connected: false }),
  });
  coordinator.refreshInBackground();

  return {
    sync: coordinator.sync,
    isEnabled,
    allFlags,
    flush: reporter.flush,
    close,
  };
}

export type Client = ReturnType<typeof init>;
