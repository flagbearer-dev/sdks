from __future__ import annotations

import logging
import random
import threading
import time
from collections.abc import Callable
from concurrent.futures import Future
from dataclasses import dataclass

from ._evaluate import Ruleset
from ._http import RefreshError, backoff
from ._log import DebugLog

logger = logging.getLogger("flagbearer")


@dataclass(frozen=True)
class Timings:
    """Every delay the client uses, in seconds. Tests shorten them."""

    request_timeout: float = 5.0
    poll_interval: float = 30.0
    retry_base: float = 1.0
    max_retry: float = 30.0
    max_version_refresh_delay: float = 2.0
    stream_connect_timeout: float = 5.0
    stream_idle_timeout: float = 45.0
    exposure_flush_interval: float = 30.0
    exposure_timeout: float = 10.0


class RefreshCoordinator:
    """Decides when to fetch the ruleset. One worker thread makes every fetch,
    so there is never more than one in flight."""

    def __init__(
        self,
        fetch: Callable[[], Ruleset | None],
        apply: Callable[[Ruleset], None],
        on_permanent_failure: Callable[[], None],
        timings: Timings,
        debug: DebugLog,
    ):
        self._fetch = fetch
        self._apply = apply
        self._on_permanent_failure = on_permanent_failure
        self._timings = timings
        self._debug = debug
        self._cond = threading.Condition()

        self._stopped = False
        self._blocked = False
        self._streaming = False
        self._version: int | None = None
        self._wanted: int | None = None
        self._attempt = 0
        # Nothing is fetched before this time: SDK backoff or a server's Retry-After.
        self._not_before = 0.0
        self._retry_at: float | None = None
        self._poll_at: float | None = None
        self._version_at: float | None = None
        self._requested = False
        self._in_flight = False
        self._waiters: list[Future] = []

        self._ensure_polling()
        self._thread = threading.Thread(target=self._run, name="flagbearer-refresh", daemon=True)
        self._thread.start()

    # Called from other threads.

    def sync(self) -> None:
        future: Future = Future()
        with self._cond:
            if self._stopped:
                raise RuntimeError("Flagbearer client is closed")
            self._blocked = False
            self._ensure_polling()
            self._waiters.append(future)
            if not self._in_flight:
                self._retry_at = None
                self._requested = True
            else:
                self._debug("Joining active refresh")
            self._cond.notify_all()
        future.result()

    def refresh_in_background(self) -> None:
        with self._cond:
            self._background_locked()

    def on_version(self, version: int) -> None:
        with self._cond:
            if self._stopped:
                return
            self._debug("Stream version received", version=version, currentVersion=self._version)
            self._wanted = max(self._wanted or 0, version)
            if self._behind() and self._version_at is None and not self._in_flight:
                delay = random.random() * self._timings.max_version_refresh_delay
                self._version_at = time.monotonic() + delay
                self._cond.notify_all()

    def set_streaming(self, connected: bool, refresh: bool = True) -> None:
        with self._cond:
            if self._stopped:
                return
            if self._streaming != connected:
                self._debug(
                    "Stream connected" if connected else "Stream disconnected; using polling"
                )
            self._streaming = connected
            if connected:
                self._clear_polling()
                if refresh:
                    self._background_locked()
            else:
                self._ensure_polling()
            self._cond.notify_all()

    def close(self) -> None:
        with self._cond:
            if self._stopped:
                return
            self._stopped = True
            self._debug("Coordinator closed")
            waiters, self._waiters = self._waiters, []
            self._cond.notify_all()
        for waiter in waiters:
            if not waiter.done():
                waiter.set_exception(RuntimeError("Flagbearer client is closed"))

    # Helpers below expect self._cond to be held.

    def _behind(self) -> bool:
        return self._wanted is not None and (self._version is None or self._version < self._wanted)

    def _background_locked(self) -> None:
        if self._stopped or self._blocked or self._retry_at is not None or self._in_flight:
            return
        self._requested = True
        self._cond.notify_all()

    def _ensure_polling(self) -> None:
        if self._stopped or self._blocked or self._streaming or self._poll_at is not None:
            return
        interval = self._timings.poll_interval * (0.9 + random.random() * 0.2)
        self._debug("Polling scheduled", intervalMs=round(interval * 1000))
        self._poll_at = time.monotonic() + interval

    def _clear_polling(self) -> None:
        if self._poll_at is not None:
            self._debug("Polling disabled")
        self._poll_at = None

    def _schedule_retry(self, error: BaseException | None) -> None:
        delay = backoff(self._attempt, self._timings.max_retry, self._timings.retry_base)
        self._attempt += 1
        if isinstance(error, RefreshError):
            delay = max(delay, error.retry_after)
        self._not_before = time.monotonic() + delay
        self._retry_at = self._not_before
        self._debug("Refresh retry scheduled", delayMs=round(delay * 1000), attempt=self._attempt)

    def _next_due(self, now: float) -> float | None:
        """Seconds until the worker has something to do, 0 if now, None if never."""
        if self._requested:
            return max(0.0, self._not_before - now)
        times = [t for t in (self._retry_at, self._poll_at, self._version_at) if t is not None]
        return max(0.0, min(times) - now) if times else None

    def _take_due(self, now: float) -> bool:
        """Turns any timer that has fired into a fetch request."""
        if self._version_at is not None and self._version_at <= now:
            self._version_at = None
            if self._behind():
                self._background_locked()
        if self._retry_at is not None and self._retry_at <= now:
            self._retry_at = None
            self._background_locked()
        if self._poll_at is not None and self._poll_at <= now:
            self._poll_at = None
            self._background_locked()
            self._ensure_polling()
        return self._requested and now >= self._not_before

    # The worker thread.

    def _run(self) -> None:
        while True:
            with self._cond:
                while not self._stopped:
                    now = time.monotonic()
                    if self._take_due(now):
                        break
                    self._cond.wait(self._next_due(now))
                if self._stopped:
                    return
                self._requested = False
                self._in_flight = True
                self._version_at = None
                waiters, self._waiters = self._waiters, []

            error: BaseException | None = None
            try:
                self._refresh()
            except BaseException as caught:  # noqa: BLE001 - every failure goes to the waiters
                error = caught

            with self._cond:
                self._in_flight = False
                # sync() calls that arrived during this fetch join it.
                waiters += self._waiters
                self._waiters = []
                stopped = self._stopped
            for waiter in waiters:
                if error is None:
                    waiter.set_result(None)
                else:
                    waiter.set_exception(error)
            if error is not None and not stopped and not waiters:
                logger.warning("Flagbearer sync failed: %s", error)

    def _refresh(self) -> None:
        """One refresh, repeated while a stream announcement is still ahead of us."""
        while True:
            self._debug("Refresh started")
            try:
                result = self._fetch()
            except BaseException as error:
                with self._cond:
                    if self._stopped:
                        raise
                    if isinstance(error, RefreshError) and not error.retryable:
                        self._debug("Automatic refresh stopped after permanent failure")
                        self._blocked = True
                        self._streaming = False
                        self._retry_at = None
                        self._clear_polling()
                        permanent = True
                    else:
                        self._schedule_retry(error)
                        permanent = False
                if permanent:
                    self._on_permanent_failure()
                raise

            with self._cond:
                if self._stopped:
                    return
                if result is None:
                    self._debug("Snapshot unchanged", version=self._version)
                    if self._behind():
                        # The backend is still behind: retry later rather than spin.
                        self._schedule_retry(None)
                        return
                    self._attempt = 0
                    self._not_before = 0.0
                    return

                previous = self._version
                if previous is None or result.version >= previous:
                    self._version = result.version
                    self._apply(result)
                    self._debug(
                        "Snapshot applied", version=result.version, previousVersion=previous
                    )
                else:
                    self._debug(
                        "Older snapshot ignored", version=result.version, currentVersion=previous
                    )

                if not self._behind():
                    self._attempt = 0
                    self._not_before = 0.0
                    return
                if previous is not None and result.version <= previous:
                    self._schedule_retry(None)
                    return
            # An announcement arrived during the request: fetch again now.
