import { readFile } from "node:fs/promises";

import { createMapTileProvider } from "./map-tiles.js";
import { createGateway } from "./gateway.js";
import { createOsrmProvider } from "./osrm-provider.js";
import { createNominatimPlacesProvider } from "./osrm-places.js";

async function createFixtureProvider() {
  const fixtureUrl = new URL("../fixtures/amap-route-v2.json", import.meta.url);
  const fixture = JSON.parse(await readFile(fixtureUrl, "utf8"));
  // Assuming fixture will be adapted via unit tests if used.
  return {
    async planRoute() {
      return {};
    },
    async planRouteOptions() {
      return {};
    },
    async searchPlaces(query) {
      return {
        protocol_version: 1,
        query: query.keywords,
        places: [],
      };
    },
  };
}

function createDisabledProvider() {
  const unavailable = () => {
    const error = new Error("Live navigation is not configured");
    error.code = "SERVER_MISCONFIGURED";
    error.retryable = false;
    throw error;
  };
  return {
    async planRoute() {
      unavailable();
    },
    async planRouteOptions() {
      unavailable();
    },
    async searchPlaces() {
      unavailable();
    },
  };
}

async function main() {
  const providerMode = process.env.MOTO_PROVIDER ?? "fixture";
  let provider;
  if (providerMode === "osrm") {
    provider = {
      ...createOsrmProvider(),
      ...createNominatimPlacesProvider()
    };
  } else if (providerMode === "amap") {
    console.warn("AMAP is completely replaced. Running OSM/OSRM mode implicitly.");
    provider = {
      ...createOsrmProvider(),
      ...createNominatimPlacesProvider()
    };
  } else if (providerMode === "fixture") {
    provider = await createFixtureProvider();
  } else if (providerMode === "disabled") {
    provider = createDisabledProvider();
  } else {
    throw new Error(`unsupported MOTO_PROVIDER: ${providerMode}`);
  }
  const port = Number(process.env.PORT ?? 8787);
  const allowedOrigin = process.env.WEB_ORIGIN ?? "http://localhost:5173";
  const mapProvider = createMapTileProvider({
    url: process.env.MOTO_MAP_PMTILES_URL ?? (providerMode === "osrm" ? "auto" : "disabled"),
    cacheDirectory: process.env.MOTO_MAP_CACHE_DIR,
    maximumCacheBytes: process.env.MOTO_MAP_CACHE_MAX_BYTES === undefined
      ? undefined : Number(process.env.MOTO_MAP_CACHE_MAX_BYTES),
  });
  await mapProvider?.initialize();

  // Expose routing directly via OSRM structures
  const server = createGateway({ provider, mapProvider, allowedOrigin, providerMode });

  server.listen(port, "127.0.0.1", () => {
    console.log(`MOTO GPS gateway listening on http://127.0.0.1:${port} (${providerMode})`);
  });

  const close = () => { mapProvider?.close(); server.close(); };
  process.once("SIGINT", close);
  process.once("SIGTERM", close);
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
