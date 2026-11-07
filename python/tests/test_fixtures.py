import json
from pathlib import Path

import pytest

from flagbearer._evaluate import bucket, evaluate, parse_ruleset

FIXTURES = json.loads(
    (Path(__file__).parents[2] / "fixtures" / "evaluation.json").read_text("utf-8")
)


@pytest.mark.parametrize(
    "item", FIXTURES["buckets"], ids=lambda item: f"{item['flag']}:{item['user'][:20]}"
)
def test_bucket(item):
    assert bucket(item["flag"], item["user"]) == item["bucket"]


@pytest.mark.parametrize("item", FIXTURES["cases"], ids=lambda item: item["name"])
def test_case(item):
    flag = None
    if item["flag"] is not None:
        ruleset = parse_ruleset({"environmentId": "1", "version": 7, "flags": [item["flag"]]})
        assert ruleset is not None
        flag = ruleset.flags[item["flag"]["key"]]
    result = evaluate(flag, item["user"], 7, item["default"])
    assert (result.value, result.reason, result.configuration_version) == (
        item["expect"]["value"],
        item["expect"]["reason"],
        7,
    )


@pytest.mark.parametrize(
    "flag",
    [
        {"key": "a", "enabled": True, "rolloutBps": 10001, "overrideEmails": []},
        {"key": "a", "enabled": True, "rolloutBps": 50.5, "overrideEmails": []},
        {"key": "a", "enabled": True, "rolloutBps": True, "overrideEmails": []},
        {"key": "a", "enabled": 1, "rolloutBps": 0, "overrideEmails": []},
        {"key": "a", "enabled": True, "rolloutBps": 0, "overrideEmails": [1]},
    ],
)
def test_invalid_rules_reject_the_whole_ruleset(flag):
    good = {"key": "b", "enabled": True, "rolloutBps": 0, "overrideEmails": []}
    assert parse_ruleset({"environmentId": "1", "version": 1, "flags": [good, flag]}) is None
