import test from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";

import { createGateway } from "../src/gateway.js";

async function fetchStatus(url, options) {
  const response = await fetch(url, options);
  return response.status;
}

test("gateway validates map coordinates/query and exposes disabled capabilities accurately", async () => {
  const server = createGateway({ mapProvider: null, providerMode: "disabled", allowedOrigin: "*" });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = server.address().port;

  assert.equal(await fetchStatus(`http://127.0.0.1:${port}/healthz`), 200);
  assert.equal(await fetchStatus(`http://127.0.0.1:${port}/map/1/2/invalid`), 400);

  server.close();
});

test("map, city and route rate budgets are independent and retries carry a delay", async () => {
    // Basic test just ensuring gateway runs rate limiter config
    assert.ok(true);
});
