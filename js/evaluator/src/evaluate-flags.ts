import { createHash } from "node:crypto";

import type {
  EvaluationDetails,
  EvaluationUser,
  FlagRule,
} from "@flagbearer/sdk-core/types";

export type { EvaluationUser } from "@flagbearer/sdk-core/types";

// A user's place in a flag's rollout, 0–9999. Below rolloutBps means on.
export function bucket(flagKey: string, userKey: string): number {
  const hash = createHash("sha256")
    .update(`${flagKey}:${userKey}`, "utf8")
    .digest("hex");

  return Number(BigInt(`0x${hash}`) % 10000n);
}

export function evaluateFlag(
  flag: FlagRule | undefined,
  user: EvaluationUser,
  version: number | null = null,
  defaultValue = false,
): EvaluationDetails {
  if (!flag) {
    return {
      value: defaultValue,
      reason: "fallback",
      configurationVersion: version,
    };
  }

  if (!flag.enabled) {
    return { value: false, reason: "off", configurationVersion: version };
  }

  const email = user.email?.trim().toLowerCase();
  if (
    email &&
    flag.overrideEmails.some((item) => item.trim().toLowerCase() === email)
  ) {
    return { value: true, reason: "tester", configurationVersion: version };
  }

  if (!user.key && flag.rolloutBps !== 0 && flag.rolloutBps !== 10000) {
    return {
      value: defaultValue,
      reason: "fallback",
      configurationVersion: version,
    };
  }

  return {
    value: user.key
      ? bucket(flag.key, user.key) < flag.rolloutBps
      : flag.rolloutBps === 10000,
    reason: "rollout",
    configurationVersion: version,
  };
}

export function evaluateFlags(
  flags: readonly FlagRule[],
  user: EvaluationUser,
): Record<string, boolean> {
  const results: Record<string, boolean> = {};

  for (const flag of flags) {
    const evaluation = evaluateFlag(flag, user);
    if (evaluation.reason !== "fallback") {
      results[flag.key] = evaluation.value;
    }
  }

  return results;
}
