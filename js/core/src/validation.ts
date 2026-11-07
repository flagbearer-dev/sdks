import type {
  EvaluationRequest,
  EvaluationResponse,
  EvaluationUser,
  FlagRule,
  Ruleset,
  VersionEvent,
} from "./types.js";

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function isVersion(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

export function isEvaluationUser(value: unknown): value is EvaluationUser {
  return (
    isRecord(value) &&
    (value.key === undefined || typeof value.key === "string") &&
    (value.email === undefined || typeof value.email === "string")
  );
}

export function isFlagRule(value: unknown): value is FlagRule {
  if (
    !isRecord(value) ||
    typeof value.key !== "string" ||
    typeof value.enabled !== "boolean" ||
    typeof value.rolloutBps !== "number" ||
    !Number.isInteger(value.rolloutBps) ||
    value.rolloutBps < 0 ||
    value.rolloutBps > 10000 ||
    !Array.isArray(value.overrideEmails)
  )
    return false;

  for (const email of value.overrideEmails) {
    if (typeof email !== "string") return false;
  }
  return true;
}

export function isRuleset(value: unknown): value is Ruleset {
  if (
    !isRecord(value) ||
    typeof value.environmentId !== "string" ||
    !isVersion(value.version) ||
    !Array.isArray(value.flags)
  )
    return false;

  for (const flag of value.flags) {
    if (!isFlagRule(flag)) return false;
  }
  return true;
}

function isShortString(value: unknown, maxLength: number): value is string {
  return typeof value === "string" && value.length <= maxLength;
}

// Limits match exposure records, so evaluation and exposures accept the same users.
export function isEvaluationRequest(
  value: unknown,
): value is EvaluationRequest {
  return (
    isRecord(value) &&
    isShortString(value.clientKey, 256) &&
    (value.includeDetails === undefined ||
      typeof value.includeDetails === "boolean") &&
    (value.knownVersion === undefined || isVersion(value.knownVersion)) &&
    (value.userKey === undefined || isShortString(value.userKey, 256)) &&
    (value.userEmail === undefined || isShortString(value.userEmail, 254)) &&
    (value.anonymousId === undefined ||
      (typeof value.anonymousId === "string" &&
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
          value.anonymousId,
        )))
  );
}

export function isEvaluationResponse(
  value: unknown,
): value is EvaluationResponse {
  return (
    isRecord(value) &&
    isVersion(value.version) &&
    isRecord(value.flags) &&
    Object.values(value.flags).every((flag) => typeof flag === "boolean") &&
    (value.details === undefined ||
      (isRecord(value.details) &&
        Object.values(value.details).every(
          (detail) =>
            isRecord(detail) &&
            typeof detail.value === "boolean" &&
            ["off", "tester", "rollout", "fallback"].includes(
              String(detail.reason),
            ) &&
            (detail.configurationVersion === null ||
              isVersion(detail.configurationVersion)),
        )))
  );
}

export function isVersionEvent(value: unknown): value is VersionEvent {
  return isRecord(value) && isVersion(value.version);
}
