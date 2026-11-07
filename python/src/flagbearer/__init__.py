"""Flagbearer server SDK: download an environment's rules once, check flags locally."""

from __future__ import annotations

import json
import socket
import threading
from typing import TypedDict

from ._evaluate import Evaluation, Ruleset, evaluate, parse_ruleset
from ._exposures import ExposureReporter
from ._http import Endpoint, RefreshError, request
from ._log import DebugLog
from ._refresh import RefreshCoordinator, Timings
from ._stream import VersionStream, abort_socket
from ._version import __version__

__all__ = ["Client", "User", "init", "__version__"]

DEFAULT_URL = "https://sdk.flagbearer.dev"


class User(TypedDict, total=False):
    key: str
    email: str


class Client:
    """Create one per process with init(), and keep it for the life of the process."""

    def __init__(
        self,
        key: str,
        url: str = DEFAULT_URL,
        debug: bool = False,
        app_version: str | None = None,
        _timings: Timings | None = None,
    ):
        _timings = _timings or Timings()
        if not isinstance(key, str) or not key or any(char.isspace() for char in key):
            raise ValueError("A nonempty server SDK key is required")
        endpoint = Endpoint(url)
        self._endpoint = endpoint
        self._headers = {"Authorization": f"Bearer {key}"}
        self._timings = _timings
        self._ruleset: Ruleset | None = None
        self._lock = threading.Lock()
        self._active: socket.socket | None = None
        self._has_connected = False
        self._closed = False
        self._stream: VersionStream | None = None
        log = DebugLog(debug)

        self._reporter = ExposureReporter(
            endpoint,
            key,
            __version__,
            app_version,
            _timings.exposure_flush_interval,
            _timings.exposure_timeout,
        )
        self._coordinator = RefreshCoordinator(
            fetch=self._fetch,
            apply=self._apply,
            on_permanent_failure=self._stop_stream,
            timings=_timings,
            debug=log,
        )
        self._stream = VersionStream(
            endpoint,
            self._headers,
            _timings,
            log,
            on_version=self._coordinator.on_version,
            on_open=self._on_open,
            on_disconnect=lambda: self._coordinator.set_streaming(False),
        )
        self._coordinator.refresh_in_background()

    def sync(self) -> None:
        """Waits for the latest rules. Raises when the request fails; the client keeps
        retrying in the background either way."""
        self._coordinator.sync()

    def is_enabled(self, flag: str, user: User | None = None, default: bool = False) -> bool:
        """Checks a flag using only the rules in memory. Never raises or blocks on the network."""
        user = user if user is not None else {}
        ruleset = self._ruleset
        version = ruleset.version if ruleset is not None else None
        try:
            rule = ruleset.flags.get(flag) if ruleset is not None else None
            details = evaluate(rule, user, version, default)
        except Exception:  # noqa: BLE001 - a bad input falls back to the default
            details = Evaluation(default, "fallback", version)
        self._reporter.record(flag, user, details)
        return details.value

    def flush(self) -> None:
        """Uploads pending exposure reports. Raises when the upload fails."""
        self._reporter.flush()

    def close(self) -> None:
        """Stops streaming and background work, then uploads pending exposures.
        Safe to call more than once. Rules in memory stay usable."""
        if self._closed:
            return
        self._closed = True
        self._coordinator.close()
        self._stop_stream()
        with self._lock:
            active = self._active
        if active is not None:
            abort_socket(active)
        try:
            self._reporter.close()
        except Exception:  # noqa: BLE001 - shutdown continues if the last upload fails
            pass

    def __enter__(self) -> Client:
        return self

    def __exit__(self, *_exc: object) -> None:
        self.close()

    def _on_open(self) -> None:
        self._coordinator.set_streaming(True, refresh=self._has_connected)
        self._has_connected = True

    def _stop_stream(self) -> None:
        if self._stream is not None:
            self._stream.close()

    def _set_active(self, sock: socket.socket | None) -> None:
        with self._lock:
            self._active = sock

    def _fetch(self) -> Ruleset | None:
        current = self._ruleset
        headers = dict(self._headers)
        if current is not None:
            headers["If-None-Match"] = f'"{current.version}"'
        try:
            status, _headers, body = request(
                self._endpoint,
                "GET",
                "/v1/ruleset",
                headers,
                timeout=self._timings.request_timeout,
                on_connection=self._set_active,
            )
        finally:
            self._set_active(None)
        if status == 304:
            return None
        try:
            payload = json.loads(body)
        except ValueError as error:
            raise RefreshError("Invalid snapshot JSON", False) from error
        ruleset = parse_ruleset(payload)
        if ruleset is None:
            raise RefreshError("Invalid ruleset response", False)
        if current is not None and ruleset.environment_id != current.environment_id:
            raise RefreshError("Ruleset environment changed unexpectedly", False)
        return ruleset

    def _apply(self, ruleset: Ruleset) -> None:
        # One assignment, so a check sees one whole version or the other.
        self._ruleset = ruleset


def init(
    key: str,
    url: str = DEFAULT_URL,
    debug: bool = False,
    app_version: str | None = None,
) -> Client:
    """Starts a client: fetches the rules and opens the stream in the background."""
    return Client(key, url=url, debug=debug, app_version=app_version)
