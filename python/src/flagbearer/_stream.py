from __future__ import annotations

import codecs
import json
import logging
import socket
import threading
from collections.abc import Callable, Mapping

from ._evaluate import parse_version
from ._http import Endpoint, RefreshError, backoff, check_status
from ._log import DebugLog
from ._refresh import Timings

logger = logging.getLogger("flagbearer")

MAX_EVENT_LENGTH = 65_536


class EventParser:
    """Server-Sent Events, fed text in arbitrary pieces. Calls on_version for
    each well-formed `version` event and ignores everything else."""

    def __init__(self, on_version: Callable[[int], None]):
        self._on_version = on_version
        self._buffer = ""
        self._event = "message"
        self._data = ""

    def feed(self, text: str) -> None:
        self._buffer += text
        buffer = self._buffer
        start = 0
        index = 0
        while index < len(buffer):
            char = buffer[index]
            if char not in "\r\n":
                index += 1
                continue
            # A CR at the end might be the first half of a CRLF in the next piece.
            if char == "\r" and index == len(buffer) - 1:
                break
            self._line(buffer[start:index])
            if char == "\r" and buffer[index + 1] == "\n":
                index += 1
            index += 1
            start = index
        self._buffer = buffer[start:]
        if len(self._buffer) > MAX_EVENT_LENGTH:
            raise ValueError("Flagbearer stream line too large")

    def _line(self, line: str) -> None:
        if line == "":
            if self._event == "version" and self._data:
                try:
                    parsed = json.loads(self._data[:-1])
                except ValueError:
                    parsed = None
                version = parse_version(parsed.get("version")) if isinstance(parsed, dict) else None
                if version is not None:
                    self._on_version(version)
            self._event = "message"
            self._data = ""
            return
        if line.startswith(":"):
            return
        field, _, content = line.partition(":")
        if content.startswith(" "):
            content = content[1:]
        if field == "event":
            self._event = content
        elif field == "data":
            self._data += content + "\n"
            if len(self._data) > MAX_EVENT_LENGTH:
                raise ValueError("Flagbearer stream event too large")


class VersionStream:
    """Holds GET /v1/stream open on its own thread and reconnects with backoff."""

    def __init__(
        self,
        endpoint: Endpoint,
        headers: Mapping[str, str],
        timings: Timings,
        debug: DebugLog,
        on_version: Callable[[int], None],
        on_open: Callable[[], None],
        on_disconnect: Callable[[], None],
    ):
        self._endpoint = endpoint
        self._headers = {**headers, "Accept": "text/event-stream"}
        self._timings = timings
        self._debug = debug
        self._on_version = on_version
        self._on_open = on_open
        self._on_disconnect = on_disconnect
        self._closed = threading.Event()
        self._lock = threading.Lock()
        self._sock: socket.socket | None = None
        self._attempts = 0
        self._thread = threading.Thread(target=self._run, name="flagbearer-stream", daemon=True)
        self._thread.start()

    def close(self) -> None:
        if self._closed.is_set():
            return
        self._closed.set()
        self._debug("Stream closed")
        with self._lock:
            sock = self._sock
        if sock is not None:
            abort_socket(sock)

    def _run(self) -> None:
        while not self._closed.is_set():
            failure = self._connect()
            if self._closed.is_set():
                return
            self._on_disconnect()
            if isinstance(failure, RefreshError) and not failure.retryable:
                # Polling remains available; the next ruleset request also checks the key.
                logger.error("Flagbearer stream stopped; using polling: %s", failure)
                return
            delay = backoff(self._attempts, self._timings.max_retry, self._timings.retry_base)
            self._attempts += 1
            if isinstance(failure, RefreshError):
                delay = max(delay, failure.retry_after)
            self._debug("Stream reconnect scheduled", delayMs=round(delay * 1000))
            self._closed.wait(delay)

    def _connect(self) -> BaseException | None:
        self._debug("Stream connecting")
        connection = self._endpoint.connect(self._timings.stream_connect_timeout)
        try:
            connection.connect()
            # http.client drops connection.sock once a response without a length
            # starts, so keep our own reference to set the idle timeout and abort.
            sock = connection.sock
            with self._lock:
                self._sock = sock
            if self._closed.is_set():
                return None
            connection.request("GET", self._endpoint.path("/v1/stream"), headers=self._headers)
            response = connection.getresponse()
            check_status(response)
            content_type = (response.getheader("Content-Type") or "").split(";")[0].strip().lower()
            if content_type != "text/event-stream":
                raise RefreshError("Invalid Flagbearer stream response", False)
            if self._closed.is_set():
                return None
            # From here the timeout is the idle limit: heartbeats arrive every 15 seconds.
            sock.settimeout(self._timings.stream_idle_timeout)
            self._on_open()

            decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
            parser = EventParser(self._on_version)
            while not self._closed.is_set():
                chunk = response.read1(16_384)
                if not chunk:
                    break
                self._attempts = 0
                parser.feed(decoder.decode(chunk))
            return None
        except Exception as error:  # noqa: BLE001 - every failure reconnects
            return error
        finally:
            with self._lock:
                sock, self._sock = self._sock, None
            if sock is not None:
                abort_socket(sock)
            connection.close()


def abort_socket(sock: socket.socket) -> None:
    """Wakes any read blocked on the socket, from any thread, then closes it."""
    try:
        sock.shutdown(socket.SHUT_RDWR)
    except OSError:
        pass
    try:
        sock.close()
    except OSError:
        pass
