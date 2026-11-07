"""The Python SDK behind the same GET /flags contract as node-server.ts."""

import json
import os
import signal
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

import flagbearer

key = os.environ.get("FLAGBEARER_SERVER_KEY")
if not key:
    raise SystemExit("The integration fixture requires FLAGBEARER_SERVER_KEY.")
port = int(os.environ.get("PORT", "0"))

# One client for the whole server, not one per HTTP request.
flags = flagbearer.init(
    key, url=os.environ.get("FLAGBEARER_URL") or flagbearer.DEFAULT_URL, debug=True
)

try:
    flags.sync()
    print("Initial Flagbearer ruleset loaded.", flush=True)
except Exception as error:  # noqa: BLE001
    print(f"Initial sync failed; serving defaults while the SDK recovers. {error}", flush=True)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def json(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        url = urlsplit(self.path)
        # Uploads pending exposures now, so a test sees whether the server took them.
        if url.path == "/flush":
            try:
                flags.flush()
            except Exception as error:  # noqa: BLE001
                return self.json(502, {"error": str(error)})
            return self.json(200, {"flushed": True})
        if url.path != "/flags":
            return self.json(404, {"error": "Not found."})
        query = {name: values[0] for name, values in parse_qs(url.query).items()}
        flag = query.get("flag")
        fallback = query.get("default", "false")
        if not flag or fallback not in ("true", "false"):
            return self.json(
                400, {"error": "Provide flag, and optionally default=true or default=false."}
            )
        user = {
            name: query[param]
            for name, param in (("key", "userKey"), ("email", "email"))
            if param in query
        }
        # Local evaluation: this request does not call Flagbearer's API.
        enabled = flags.is_enabled(flag, user, fallback == "true")
        self.json(200, {"flag": flag, "user": user, "enabled": enabled})


server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
server.daemon_threads = True


def shutdown(*_args):
    flags.close()
    sys.exit(0)


signal.signal(signal.SIGTERM, shutdown)
signal.signal(signal.SIGINT, shutdown)
print(f"Python SDK fixture listening on http://127.0.0.1:{server.server_address[1]}", flush=True)
server.serve_forever()
