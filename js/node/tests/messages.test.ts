import { afterEach, expect, spyOn, test } from "bun:test";

import { init } from "../src/index";

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
});

test("a refused key is explained in one line each for flags and live updates, with the server's reason", async () => {
  const logged = spyOn(console, "error").mockImplementation(() => {});
  globalThis.fetch = (async () =>
    Response.json(
      {
        code: "INVALID_CREDENTIALS",
        message: "This SDK key isn't recognized.",
      },
      { status: 401 },
    )) as unknown as typeof fetch;

  const client = init({ key: "fbs_deleted", url: "http://sdk.test" });
  await expect(client.sync()).rejects.toThrow("This SDK key isn't recognized.");
  await new Promise((resolve) => setTimeout(resolve, 0));
  client.close();

  // Plain sentences, not an error object with a stack trace.
  expect(logged.mock.calls.map((call) => call.length)).toEqual([1, 1]);
  expect(logged.mock.calls.map(([message]) => message).sort()).toEqual([
    "[Flagbearer] Flags have stopped updating and keep their current or default values until this is fixed. Flagbearer request failed: 401. This SDK key isn't recognized.",
    "[Flagbearer] Live updates have stopped, so flag changes arrive by polling. Flagbearer request failed: 401. This SDK key isn't recognized.",
  ]);
});

test("a client key is refused at init, before any request", () => {
  expect(() => init({ key: "fbc_public", url: "http://sdk.test" })).toThrow(
    "needs a server key (fbs_)",
  );
});
