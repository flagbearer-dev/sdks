from __future__ import annotations

import json
import random
import threading
import time
import uuid
from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

from ._evaluate import Evaluation
from ._http import Endpoint, request, retry_after

WINDOW_MS = 30_000
MAX_RECORDS = 1000
BATCH_SIZE = 100
MAX_CHECK_COUNT = 1_000_000_000


def _js_length(text: str) -> int:
    """UTF-16 code units, as JavaScript and the server's limits count them."""
    return len(text.encode("utf-16-le")) // 2


def _iso(ms: int) -> str:
    moment = datetime.fromtimestamp(ms / 1000, tz=timezone.utc)
    return moment.isoformat(timespec="milliseconds").replace("+00:00", "Z")


@dataclass
class _Event:
    flag_key: str
    result: bool
    reason: str
    configuration_version: int | None
    bucket: str
    first_checked_at: str
    last_checked_at: str
    check_count: int = 1

    def body(self) -> dict[str, Any]:
        return {
            "flagKey": self.flag_key,
            "result": self.result,
            "reason": self.reason,
            "configurationVersion": self.configuration_version,
            "bucket": self.bucket,
            "checkCount": self.check_count,
            "firstCheckedAt": self.first_checked_at,
            "lastCheckedAt": self.last_checked_at,
        }


@dataclass
class _Batch:
    """One upload, fixed when sealed, so a retry sends exactly the same body and
    the server can recognize it by batch_id."""

    user: dict[str, str]
    events: list[_Event] = field(default_factory=list)
    batch_id: str = field(default_factory=lambda: str(uuid.uuid4()))


class ExposureReporter:
    def __init__(
        self,
        endpoint: Endpoint,
        key: str,
        sdk_version: str,
        app_version: str | None,
        flush_interval: float,
        timeout: float,
    ):
        self._endpoint = endpoint
        self._key = key
        self._sdk_version = sdk_version
        self._app_version = app_version[:100] if app_version else None
        self._timeout = timeout
        self._reporter_id = str(uuid.uuid4())
        self._lock = threading.Lock()
        # Only one upload runs at a time; flush() waits for the one in progress.
        self._send_lock = threading.Lock()
        self._active: dict[tuple, tuple[dict[str, str], _Event]] = {}
        self._sealed: list[_Batch] = []
        self._sealed_events = 0
        self._dropped = 0
        self._reported_dropped = 0
        self._retry_at = 0.0
        self._failures = 0
        self._closed = threading.Event()
        self._interval = flush_interval
        self._thread = threading.Thread(target=self._run, name="flagbearer-exposures", daemon=True)
        self._thread.start()

    def record(self, flag_key: str, user: Mapping[str, Any], details: Evaluation) -> None:
        if self._closed.is_set():
            return
        try:
            with self._lock:
                key = user.get("key")
                if not flag_key or _js_length(flag_key) > 256 or (key and _js_length(key) > 256):
                    self._dropped += 1
                    return
                now = int(time.time() * 1000)
                bucket = _iso(now // WINDOW_MS * WINDOW_MS)
                raw_email = user.get("email")
                email = (
                    raw_email.strip().lower() or None
                    if raw_email and _js_length(raw_email) <= 254
                    else None
                )
                user_key = key or None
                identity = (
                    flag_key,
                    user_key,
                    email,
                    details.value,
                    details.reason,
                    details.configuration_version,
                    bucket,
                )
                existing = self._active.get(identity)
                if existing and existing[1].check_count < MAX_CHECK_COUNT:
                    existing[1].check_count += 1
                    existing[1].last_checked_at = _iso(now)
                    return
                if existing or len(self._active) + self._sealed_events >= MAX_RECORDS:
                    self._dropped += 1
                    return
                who = {}
                if user_key:
                    who["key"] = user_key
                if email:
                    who["email"] = email
                self._active[identity] = (
                    who,
                    _Event(
                        flag_key,
                        details.value,
                        details.reason,
                        details.configuration_version,
                        bucket,
                        _iso(now),
                        _iso(now),
                    ),
                )
        except Exception:  # noqa: BLE001 - recording never fails a flag check
            with self._lock:
                self._dropped += 1

    def flush(self) -> None:
        """Uploads every pending check. Raises when an upload fails."""
        with self._send_lock:
            try:
                self._send()
            except Exception:
                with self._lock:
                    self._failures += 1
                    delay = min(60.0, 2 ** min(self._failures, 6))
                    self._retry_at = max(self._retry_at, time.monotonic() + delay)
                raise

    def close(self) -> None:
        if self._closed.is_set():
            return
        self._closed.set()
        self.flush()

    def _run(self) -> None:
        while not self._closed.wait(self._interval * (0.9 + random.random() * 0.2)):
            if time.monotonic() >= self._retry_at:
                try:
                    self.flush()
                except Exception:  # noqa: BLE001 - background uploads retry later
                    pass

    def _seal(self) -> None:
        # Called with self._lock held. A batch never mixes users.
        open_batches: dict[tuple, _Batch] = {}
        for who, event in self._active.values():
            user_id = (who.get("key"), who.get("email"))
            batch = open_batches.get(user_id)
            if batch is None or len(batch.events) >= BATCH_SIZE:
                batch = _Batch(user=who)
                open_batches[user_id] = batch
                self._sealed.append(batch)
            batch.events.append(event)
            self._sealed_events += 1
        self._active.clear()

    def _settle(self, batch: _Batch) -> None:
        with self._lock:
            if self._sealed and self._sealed[0] is batch:
                self._sealed.pop(0)
                self._sealed_events -= len(batch.events)

    def _send(self) -> None:
        with self._lock:
            self._seal()
        while True:
            with self._lock:
                if not self._sealed and self._dropped <= self._reported_dropped:
                    break
                # Only lost checks to report: an empty batch carries the count.
                batch = self._sealed[0] if self._sealed else _Batch(user={})
                dropped = self._dropped
            checks = sum(event.check_count for event in batch.events)
            body = {
                "batchId": batch.batch_id,
                "reporterId": self._reporter_id,
                "user": batch.user,
                "events": [event.body() for event in batch.events],
                "droppedChecks": dropped,
                "sdk": "python",
                "sdkVersion": self._sdk_version,
            }
            if self._app_version:
                body["appVersion"] = self._app_version
            status, headers, data = request(
                self._endpoint,
                "POST",
                "/v1/exposures",
                {"Authorization": f"Bearer {self._key}", "Content-Type": "application/json"},
                json.dumps(body).encode("utf-8"),
                timeout=self._timeout,
                check=False,
            )
            if not 200 <= status < 300:
                with self._lock:
                    wait = retry_after(headers.get("retry-after"), time.time())
                    if wait > 0:
                        self._retry_at = max(self._retry_at, time.monotonic() + min(wait, 3600))
                    if 400 <= status < 500 and status != 429:
                        # The server won't take this batch, so retrying can't help.
                        self._dropped += checks
                        self._retry_at = time.monotonic() + 60
                if 400 <= status < 500 and status != 429:
                    self._settle(batch)
                raise RuntimeError(f"Exposure upload failed ({status})")
            rejected = self._receipt(data, checks, len(batch.events))
            with self._lock:
                self._dropped += rejected
                self._reported_dropped = max(self._reported_dropped, dropped)
            self._settle(batch)
        with self._lock:
            self._failures = 0

    def _receipt(self, data: bytes, checks: int, events: int) -> int:
        try:
            receipt = json.loads(data)
        except ValueError:
            receipt = None
        accepted = receipt.get("accepted") if isinstance(receipt, dict) else None
        rejected = receipt.get("rejectedChecks") if isinstance(receipt, dict) else None
        if (
            not isinstance(accepted, int)
            or isinstance(accepted, bool)
            or not 0 <= accepted <= events
            or not isinstance(rejected, int)
            or isinstance(rejected, bool)
            or not 0 <= rejected <= checks
        ):
            raise ValueError("Invalid exposure upload receipt")
        return rejected
