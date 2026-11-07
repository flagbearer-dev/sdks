import json
import queue
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

import flagbearer
from flagbearer._refresh import Timings

# Short enough that retries finish within a test, long enough that nothing
# polls unless a test waits for it.
FAST = Timings(
    request_timeout=1.0,
    poll_interval=3600.0,
    retry_base=0.01,
    max_retry=0.05,
    max_version_refresh_delay=0.0,
    stream_connect_timeout=1.0,
    stream_idle_timeout=5.0,
    exposure_flush_interval=3600.0,
    exposure_timeout=1.0,
)


def rule(key, enabled=True, rollout_bps=10000, override_emails=()):
    return {
        "key": key,
        "enabled": enabled,
        "rolloutBps": rollout_bps,
        "overrideEmails": list(override_emails),
    }


class FakeSdk:
    """The SDK server's three routes. Tests set what it returns and read what it got."""

    def __init__(self):
        self.version = 1
        self.environment_id = "env-1"
        self.flags = [rule("checkout")]
        self.ruleset_body = None  # Raw bytes overriding the ruleset response.
        self.ruleset_statuses = []  # Statuses to return before succeeding.
        self.stream_status = 200
        self.exposure_statuses = []
        self.ruleset_requests = []
        self.exposures = []
        self.stream_events = queue.Queue()
        self.stream_opened = threading.Event()
        self.stream_connections = 0
        self.lock = threading.Lock()

    def announce(self, version):
        self.stream_events.put(f'event: version\ndata: {{"version":{version}}}\n\n')

    def ruleset(self):
        return {"environmentId": self.environment_id, "version": self.version, "flags": self.flags}


@pytest.fixture
def sdk():
    fake = FakeSdk()

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_args):
            pass

        def send(self, status, body=b"", content_type="application/json", headers=()):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            for name, value in headers:
                self.send_header(name, value)
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.headers.get("Authorization") != "Bearer fbs_test":
                return self.send(401, b"{}")
            if self.path == "/v1/ruleset":
                with fake.lock:
                    fake.ruleset_requests.append(self.headers.get("If-None-Match"))
                    status = fake.ruleset_statuses.pop(0) if fake.ruleset_statuses else 200
                    etag = f'"{fake.version}"'
                    body = fake.ruleset_body or json.dumps(fake.ruleset()).encode()
                if status != 200:
                    return self.send(status, b"{}", headers=[("Retry-After", "0")])
                if self.headers.get("If-None-Match") == etag:
                    return self.send(304, headers=[("ETag", etag)])
                return self.send(200, body, headers=[("ETag", etag)])
            if self.path == "/v1/stream":
                with fake.lock:
                    fake.stream_connections += 1
                if fake.stream_status != 200:
                    return self.send(fake.stream_status, b"{}")
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Cache-Control", "no-cache")
                self.end_headers()
                opening = f'retry: 5000\n\nevent: version\ndata: {{"version":{fake.version}}}\n\n'
                self.wfile.write(opening.encode())
                self.wfile.flush()
                fake.stream_opened.set()
                while True:
                    try:
                        event = fake.stream_events.get(timeout=0.05)
                    except queue.Empty:
                        if server.stopping:
                            return
                        continue
                    if event is None:
                        return
                    try:
                        self.wfile.write(event.encode())
                        self.wfile.flush()
                    except OSError:
                        return
            return self.send(404, b"{}")

        def do_POST(self):
            if self.path != "/v1/exposures":
                return self.send(404, b"{}")
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            with fake.lock:
                fake.exposures.append(body)
                status = fake.exposure_statuses.pop(0) if fake.exposure_statuses else 200
            if status != 200:
                return self.send(status, b"{}")
            receipt = {"accepted": len(body["events"]), "rejectedChecks": 0}
            return self.send(200, json.dumps(receipt).encode())

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    server.stopping = False
    thread = threading.Thread(
        target=server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True
    )
    thread.start()
    fake.url = f"http://127.0.0.1:{server.server_address[1]}"
    yield fake
    server.stopping = True
    server.shutdown()
    server.server_close()


@pytest.fixture
def make_client(sdk):
    clients = []

    def make(**options):
        client = flagbearer.Client(
            "fbs_test", url=sdk.url, _timings=options.pop("timings", FAST), **options
        )
        clients.append(client)
        return client

    yield make
    for client in clients:
        client.close()


def wait_for(predicate, timeout=3.0):
    """Polls a condition the SDK reaches on its own threads."""
    event = threading.Event()
    deadline = timeout
    step = 0.005
    while deadline > 0:
        if predicate():
            return
        event.wait(step)
        deadline -= step
    raise AssertionError("condition not met in time")
