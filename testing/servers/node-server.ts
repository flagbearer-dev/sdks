import { createServer, type ServerResponse } from "node:http";

import { init } from "@flagbearer/node";

const key = process.env.FLAGBEARER_SERVER_KEY;
if (!key) {
  throw new Error("The integration fixture requires FLAGBEARER_SERVER_KEY.");
}

const port = Number(process.env.PORT ?? 0);
if (!Number.isInteger(port) || port < 0 || port > 65535) {
  throw new Error("PORT must be an integer between 0 and 65535.");
}

// One client for the whole server, not one per HTTP request.
const flags = init({
  key,
  debug: true,
  url: process.env.FLAGBEARER_URL,
});

try {
  await flags.sync();
  console.log("Initial Flagbearer ruleset loaded.");
} catch (error) {
  console.warn(
    "Initial sync failed; serving defaults while the SDK recovers.",
    error,
  );
}

function json(response: ServerResponse, status: number, body: unknown) {
  response.writeHead(status, {
    "Content-Type": "application/json",
    "Cache-Control": "no-store",
  });
  response.end(JSON.stringify(body));
}

const server = createServer(async (request, response) => {
  if (request.method !== "GET") {
    response.setHeader("Allow", "GET");
    json(response, 405, { error: "Use GET." });
    return;
  }

  const url = new URL(request.url ?? "/", "http://localhost");
  // Uploads pending exposures now, so a test sees whether the server took them.
  if (url.pathname === "/flush") {
    try {
      await flags.flush();
      json(response, 200, { flushed: true });
    } catch (error) {
      json(response, 502, { error: String(error) });
    }
    return;
  }
  if (url.pathname !== "/flags") {
    json(response, 404, { error: "Not found." });
    return;
  }

  const flag = url.searchParams.get("flag");
  const fallback = url.searchParams.get("default") ?? "false";
  if (!flag || (fallback !== "true" && fallback !== "false")) {
    json(response, 400, {
      error: "Provide flag, and optionally default=true or default=false.",
    });
    return;
  }

  const user = {
    key: url.searchParams.get("userKey") ?? undefined,
    email: url.searchParams.get("email") ?? undefined,
  };

  // Synchronous local evaluation: this request does not call Flagbearer's API.
  const enabled = flags.isEnabled(flag, user, fallback === "true");
  json(response, 200, { flag, user, enabled });
});

server.on("error", (error) => {
  flags.close();
  console.error("Node SDK fixture failed:", error);
  process.exitCode = 1;
});

server.listen(port, "127.0.0.1", () => {
  const address = server.address();
  if (address && typeof address !== "string") {
    console.log(
      `Node SDK fixture listening on http://127.0.0.1:${address.port}`,
    );
  }
});

function shutdown() {
  flags.close();
  server.close();
}

process.once("SIGINT", shutdown);
process.once("SIGTERM", shutdown);
