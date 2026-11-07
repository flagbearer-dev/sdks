import { expect, test } from "bun:test";

import type { EvaluationUser, FlagRule } from "@flagbearer/sdk-core/types";

import fixtures from "../../../fixtures/evaluation.json";
import { bucket, evaluateFlag } from "../src/evaluate-flags";

type Case = {
  name: string;
  flag: FlagRule | null;
  user: EvaluationUser;
  default: boolean;
  expect: { value: boolean; reason: string };
};

for (const item of fixtures.buckets) {
  test(`bucket(${item.flag}, ${item.user.slice(0, 20)})`, () => {
    expect(bucket(item.flag, item.user)).toBe(item.bucket);
  });
}

for (const item of fixtures.cases as Case[]) {
  test(item.name, () => {
    const result = evaluateFlag(
      item.flag ?? undefined,
      item.user,
      7,
      item.default,
    );
    expect(result).toEqual({ ...item.expect, configurationVersion: 7 });
  });
}
