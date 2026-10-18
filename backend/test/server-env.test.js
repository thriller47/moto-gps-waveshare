import assert from "node:assert/strict";
import test from "node:test";

test("providerMode environment resolution defaults to osm", () => {
  const resolveProviderMode = (env) =>
    String(env.MOTO_PROVIDER ?? env.PROVIDER_MODE ?? "osm")
      .trim()
      .toLowerCase();

  assert.equal(resolveProviderMode({}), "osm");
  assert.equal(resolveProviderMode({ MOTO_PROVIDER: "amap" }), "amap");
  assert.equal(resolveProviderMode({ PROVIDER_MODE: "osm" }), "osm");
  assert.equal(resolveProviderMode({ MOTO_PROVIDER: "OSM", PROVIDER_MODE: "amap" }), "osm");
  assert.equal(resolveProviderMode({ MOTO_PROVIDER: "fixture" }), "fixture");
});
