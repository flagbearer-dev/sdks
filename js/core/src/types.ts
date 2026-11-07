export type EvaluationUser = { key?: string; email?: string };

export type FlagRule = {
  key: string;
  enabled: boolean;
  rolloutBps: number;
  overrideEmails: string[];
};

export type Ruleset = {
  environmentId: string;
  version: number;
  flags: FlagRule[];
};

export type EvaluationRequest = {
  clientKey: string;
  includeDetails?: boolean;
  knownVersion?: number;
  userKey?: string;
  userEmail?: string;
  // A random ID the browser keeps, for counting signed-out visitors. Never
  // used for targeting.
  anonymousId?: string;
};

export type EvaluationResponse = {
  version: number;
  flags: Record<string, boolean>;
  details?: Record<string, EvaluationDetails>;
};

export type VersionEvent = { version: number };

export type EvaluationReason = "off" | "tester" | "rollout" | "fallback";
export type EvaluationDetails = {
  value: boolean;
  reason: EvaluationReason;
  configurationVersion: number | null;
};
// One aggregated flag check, as uploaded. Who made it is sent once per batch.
export type CompactExposure = {
  flagKey: string;
  result: boolean;
  reason: EvaluationReason;
  configurationVersion: number | null;
  bucket: string;
  checkCount: number;
  firstCheckedAt: string;
  lastCheckedAt: string;
};
