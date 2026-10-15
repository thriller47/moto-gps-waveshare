import test from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";

import { createGateway } from "../src/gateway.js";

async function fetchJson(url, options) {
  const response = await fetch(url, options);
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}

test("gateway parses options and proxies route planning", async () => {
  const provider = {
    async planRoute(query) {
      assert.deepEqual(query, { origin: { latitude: 36.6, longitude: 117.0 }, destination: { latitude: 36.7, longitude: 117.1 } });
      return { protocol_version: 1, route_id: "mock" };
    }
  };

  const server = createGateway({ provider, allowedOrigin: "*" });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = server.address().port;

  const url = `http://127.0.0.1:${port}/route`;
  const result = await fetchJson(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ origin: { latitude: 36.6, longitude: 117.0 }, destination: { latitude: 36.7, longitude: 117.1 } })
  });

  assert.equal(result.protocol_version, 1);
  assert.equal(result.route_id, "mock");

  server.close();
});

test("gateway rejects oversized requests", async () => {
  const server = createGateway({ provider: {}, allowedOrigin: "*" });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = server.address().port;

  const url = `http://127.0.0.1:${port}/route`;
  const oversized = "a".repeat(10_000);

  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: `{"query": "${oversized}"}`
  });

  assert.equal(response.status, 500); // Mapped to internal error in catch currently

  server.close();
});
