from __future__ import annotations

import hashlib
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any, Literal

Reason = Literal["off", "tester", "rollout", "fallback"]

MAX_SAFE_INTEGER = 2**53 - 1


@dataclass(frozen=True)
class FlagRule:
    key: str
    enabled: bool
    rollout_bps: int
    override_emails: tuple[str, ...]


@dataclass(frozen=True)
class Ruleset:
    environment_id: str
    version: int
    flags: dict[str, FlagRule]


@dataclass(frozen=True)
class Evaluation:
    value: bool
    reason: Reason
    configuration_version: int | None


def bucket(flag_key: str, user_key: str) -> int:
    """A user's place in a flag's rollout, 0-9999. Below rollout_bps means on."""
    digest = hashlib.sha256(f"{flag_key}:{user_key}".encode()).digest()
    return int.from_bytes(digest, "big") % 10000


def evaluate(
    flag: FlagRule | None,
    user: Mapping[str, Any],
    version: int | None,
    default: bool,
) -> Evaluation:
    if flag is None:
        return Evaluation(default, "fallback", version)
    if not flag.enabled:
        return Evaluation(False, "off", version)

    email = user.get("email")
    email = email.strip().lower() if email is not None else ""
    if email and any(item.strip().lower() == email for item in flag.override_emails):
        return Evaluation(True, "tester", version)

    key = user.get("key")
    if not key:
        if flag.rollout_bps not in (0, 10000):
            return Evaluation(default, "fallback", version)
        return Evaluation(flag.rollout_bps == 10000, "rollout", version)

    return Evaluation(bucket(flag.key, key) < flag.rollout_bps, "rollout", version)


def _integer(value: Any) -> int | None:
    # JSON numbers are doubles, so 42.0 is the same version as 42. bool is an int
    # subclass in Python and never a valid number here.
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float) and value.is_integer():
        return int(value)
    return None


def parse_version(value: Any) -> int | None:
    number = _integer(value)
    if number is None or number < 0 or number > MAX_SAFE_INTEGER:
        return None
    return number


def parse_ruleset(value: Any) -> Ruleset | None:
    """The ruleset in a GET /v1/ruleset body, or None if any of it is invalid."""
    if not isinstance(value, dict):
        return None
    environment_id = value.get("environmentId")
    version = parse_version(value.get("version"))
    flags = value.get("flags")
    if not isinstance(environment_id, str) or version is None or not isinstance(flags, list):
        return None

    rules: dict[str, FlagRule] = {}
    for item in flags:
        if not isinstance(item, dict):
            return None
        key = item.get("key")
        enabled = item.get("enabled")
        rollout = _integer(item.get("rolloutBps"))
        emails = item.get("overrideEmails")
        if (
            not isinstance(key, str)
            or not isinstance(enabled, bool)
            or rollout is None
            or not 0 <= rollout <= 10000
            or not isinstance(emails, list)
            or not all(isinstance(email, str) for email in emails)
        ):
            return None
        rules[key] = FlagRule(key, enabled, rollout, tuple(emails))
    return Ruleset(environment_id, version, rules)
