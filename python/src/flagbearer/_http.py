from __future__ import annotations

import http.client
import random
import ssl
import time
from collections.abc import Mapping
from email.utils import parsedate_to_datetime
from urllib.parse import urlsplit

MAX_RETRY_S = 30.0


class RefreshError(Exception):
    def __init__(self, message: str, retryable: bool, retry_after: float = 0.0):
        super().__init__(message)
        self.retryable = retryable
        self.retry_after = retry_after


def retry_after(header: str | None, now: float) -> float:
    """Seconds to wait from a Retry-After header, in seconds or as an HTTP date."""
    if header is None:
        return 0.0
    try:
        seconds = float(header)
        return max(0.0, seconds) if seconds == seconds and seconds != float("inf") else 0.0
    except ValueError:
        pass
    try:
        return max(0.0, parsedate_to_datetime(header).timestamp() - now)
    except (TypeError, ValueError, OverflowError):
        return 0.0


def check_status(response: http.client.HTTPResponse) -> None:
    status = response.status
    if 200 <= status < 300:
        return
    # A 3xx lands here too: requests never follow redirects.
    raise RefreshError(
        f"Flagbearer request failed: {status}",
        status in (408, 429) or status >= 500,
        retry_after(response.getheader("Retry-After"), time.time()),
    )


def backoff(attempt: int, cap: float = MAX_RETRY_S, base: float = 1.0) -> float:
    delay = min(cap, base * 2 ** min(attempt, 5))
    return min(cap, delay * (0.8 + random.random() * 0.4))


class Endpoint:
    """One SDK server address. http.client never follows redirects or reads proxy settings."""

    def __init__(self, url: str):
        parts = urlsplit(url)
        if parts.scheme not in ("http", "https") or not parts.hostname:
            raise ValueError("Flagbearer url must use HTTP or HTTPS")
        self.scheme = parts.scheme
        self.host = parts.hostname
        self.port = parts.port
        self.prefix = parts.path.rstrip("/")
        self._ssl = ssl.create_default_context() if parts.scheme == "https" else None

    def connect(self, timeout: float) -> http.client.HTTPConnection:
        if self._ssl is not None:
            return http.client.HTTPSConnection(
                self.host, self.port, timeout=timeout, context=self._ssl
            )
        return http.client.HTTPConnection(self.host, self.port, timeout=timeout)

    def path(self, path: str) -> str:
        return f"{self.prefix}{path}"


def request(
    endpoint: Endpoint,
    method: str,
    path: str,
    headers: Mapping[str, str],
    body: bytes | None = None,
    timeout: float = 5.0,
    on_connection=None,
    check: bool = True,
) -> tuple[int, Mapping[str, str], bytes]:
    """Sends one request and reads the whole response. Network errors are
    retryable. With check, statuses other than 2xx and 304 raise RefreshError."""
    connection = endpoint.connect(timeout)
    try:
        connection.connect()
        if on_connection is not None:
            on_connection(connection.sock)
        connection.request(method, endpoint.path(path), body=body, headers=dict(headers))
        response = connection.getresponse()
        data = response.read()
        response_headers = {key.lower(): value for key, value in response.getheaders()}
        if check and not (200 <= response.status < 300) and response.status != 304:
            check_status(response)
        return response.status, response_headers, data
    except RefreshError:
        raise
    except (OSError, http.client.HTTPException) as error:
        raise RefreshError(f"Flagbearer request failed: {error}", True) from error
    finally:
        connection.close()
