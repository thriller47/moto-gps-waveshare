import { createServer } from "node:http";

import { validRequestId, validateRouteRequest } from "./validation.js";
import { validateTileCoordinates } from "./map-tiles.js";

const DEFAULT_MAX_BODY_BYTES = 8192;

class BodyReadError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
    this.retryable = false;
  }
}

async function readJsonBody(request, maximumBytes) {
  let receivedBytes = 0;
  const chunks = [];
  for await (const chunk of request) {
    receivedBytes += chunk.length;
    if (receivedBytes > maximumBytes) {
      throw new BodyReadError("REQUEST_TOO_LARGE", "request body exceeds the size limit");
    }
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw new BodyReadError("INVALID_JSON", "request body is not valid JSON");
  }
}

function setCommonHeaders(response, allowedOrigin) {
  response.setHeader("Content-Type", "application/json; charset=utf-8");
  response.setHeader("Cache-Control", "no-store");
  response.setHeader("X-Content-Type-Options", "nosniff");
  if (allowedOrigin) {
    response.setHeader("Access-Control-Allow-Origin", allowedOrigin);
  }
}

export function clientAddress(request) {
  const address = request.socket.remoteAddress;
  if (address === "127.0.0.1" || address === "::1" || address === "::ffff:127.0.0.1") {
    const realIp = request.headers["x-real-ip"];
    if (typeof realIp === "string") {
      return realIp;
    }
  }
  return address;
}

export function createRateLimiter() {
  let entries = new Map();

  function enforce(request, kind, limit, windowMs) {
    const address = clientAddress(request);
    const now = Date.now();
    let state = entries.get(address);
    if (!state) {
      if (entries.size >= 4096) {
        entries.clear();
      }
      state = {
        routes: { count: 0, reset_ms: 0 },
        tiles: { count: 0, reset_ms: 0 },
        cities: { count: 0, reset_ms: 0 }
      };
      entries.set(address, state);
    }

    let bucket = state[kind];
    if (!bucket) {
        bucket = { count: 0, reset_ms: 0 };
        state[kind] = bucket;
    }

    if (now > bucket.reset_ms) {
      bucket.count = 0;
      bucket.reset_ms = now + windowMs;
    }

    if (bucket.count >= limit) {
      return false;
    }

    bucket.count += 1;
    return true;
  }

  enforce.size = entries.size;
  Object.defineProperty(enforce, "size", {
      get: () => entries.size
  });
  return enforce;
}

export function createGateway(config) {
  const provider = config.provider;
  const mapProvider = config.mapProvider;
  const allowedOrigin = config.allowedOrigin;
  const rateLimit = createRateLimiter();

  const server = createServer(async (req, res) => {
    try {
      setCommonHeaders(res, allowedOrigin);

      if (req.method === "OPTIONS") {
        res.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        res.setHeader("Access-Control-Allow-Headers", "Content-Type");
        res.statusCode = 204;
        res.end();
        return;
      }

      const url = new URL(req.url, `http://${req.headers.host}`);
      const path = url.pathname.replace(/\/+$/, "");

      if (path === "/healthz" && req.method === "GET") {
        res.statusCode = 200;
        res.end(JSON.stringify({
            status: "ok",
            provider: config.providerMode || "unknown",
            ready_for_live_navigation: config.providerMode !== "disabled"
        }));
        return;
      }

      if (path === "/search" && req.method === "POST") {
         if (!rateLimit(req, "cities", 60, 60_000)) {
            res.statusCode = 429;
            res.end(JSON.stringify({ code: "RATE_LIMIT", message: "Too many requests" }));
            return;
         }
         const body = await readJsonBody(req, DEFAULT_MAX_BODY_BYTES);
         const result = await provider.searchPlaces(body);
         res.statusCode = 200;
         res.end(JSON.stringify(result));
         return;
      }

      if (path === "/route" && req.method === "POST") {
         if (!rateLimit(req, "routes", 30, 60_000)) {
            res.statusCode = 429;
            res.end(JSON.stringify({ code: "RATE_LIMIT", message: "Too many requests" }));
            return;
         }
         const body = await readJsonBody(req, DEFAULT_MAX_BODY_BYTES);
         const result = await provider.planRoute(body);
         res.statusCode = 200;
         res.end(JSON.stringify(result));
         return;
      }

      if (path.startsWith("/map/") && req.method === "GET") {
          const parts = path.split("/");
          if (parts.length === 5) {
              const x = parseInt(parts[2], 10);
              const y = parseInt(parts[3], 10);
              const z = parseInt(parts[4], 10);

              if (isNaN(x) || isNaN(y) || isNaN(z)) {
                  res.statusCode = 400;
                  res.end(JSON.stringify({ code: "INVALID_PARAMETERS", message: "Invalid tile coordinates" }));
                  return;
              }

              if (mapProvider) {
                 const tile = await mapProvider.getTile(z, x, y);
                 if (tile) {
                     res.statusCode = 200;
                     res.setHeader("Content-Type", "application/vnd.mapbox-vector-tile");
                     res.end(tile);
                     return;
                 }
              }
          }
      }

      res.statusCode = 404;
      res.end(JSON.stringify({ code: "NOT_FOUND", message: "Not found" }));
    } catch (error) {
      console.error("Gateway Error:", error);
      res.statusCode = 500;
      res.end(JSON.stringify({ code: "INTERNAL_ERROR", message: error.message }));
    }
  });

  return server;
}
