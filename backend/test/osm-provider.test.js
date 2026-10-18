import assert from "node:assert/strict";
import test from "node:test";
import { createOsmProvider } from "../src/osm-provider.js";

test("searchPlaces queries Nominatim and returns protocol v1 places", async () => {
  const mockFetch = async (url) => {
    assert.match(url, /nominatim\.openstreetmap\.org\/search/);
    assert.match(url, /q=Colosseum/);
    return {
      ok: true,
      async json() {
        return [
          {
            place_id: 12345,
            name: "Colosseum",
            display_name: "Colosseum, Piazza del Colosseo, Rome, Italy",
            lon: "12.4922",
            lat: "41.8902",
            address: {
              city: "Rome",
              suburb: "Monti",
            },
          },
        ];
      },
    };
  };

  const provider = createOsmProvider({ fetchImpl: mockFetch });
  const result = await provider.searchPlaces({ keywords: "Colosseum" });

  assert.equal(result.protocol_version, 1);
  assert.equal(result.query, "Colosseum");
  assert.equal(result.places.length, 1);
  assert.equal(result.places[0].id, "12345");
  assert.equal(result.places[0].name, "Colosseum");
  assert.equal(result.places[0].location.coordinate_system, "WGS84");
  assert.equal(result.places[0].location.longitude_deg, 12.4922);
  assert.equal(result.places[0].location.latitude_deg, 41.8902);
});

test("searchCities queries Nominatim and returns city bounding box schema", async () => {
  const mockFetch = async (url) => {
    assert.match(url, /nominatim\.openstreetmap\.org\/search/);
    assert.match(url, /q=Rome/);
    return {
      ok: true,
      async json() {
        return [
          {
            place_id: 67890,
            name: "Rome",
            type: "administrative",
            boundingbox: ["41.7", "42.0", "12.3", "12.6"],
          },
        ];
      },
    };
  };

  const provider = createOsmProvider({ fetchImpl: mockFetch });
  const result = await provider.searchCities({ keywords: "Rome" });

  assert.equal(result.cities.length, 1);
  assert.equal(result.cities[0].id, "67890");
  assert.equal(result.cities[0].name, "Rome");
  assert.deepEqual(result.cities[0].bounds_wgs84, [12.3, 41.7, 12.6, 42.0]);
});

test("planRoute queries OSRM and transforms route into v1 routeBundle", async () => {
  const mockFetch = async (url) => {
    assert.match(url, /router\.project-osrm\.org\/route\/v1\/driving/);
    return {
      ok: true,
      async json() {
        return {
          code: "Ok",
          routes: [
            {
              distance: 1500.5,
              duration: 300,
              geometry: {
                coordinates: [
                  [12.49, 41.89],
                  [12.50, 41.90],
                ],
              },
              legs: [
                {
                  steps: [
                    {
                      distance: 1500.5,
                      name: "Via dei Fori Imperiali",
                      maneuver: {
                        type: "turn",
                        modifier: "right",
                        instruction: "Turn right onto Via dei Fori Imperiali",
                      },
                    },
                  ],
                },
              ],
            },
          ],
        };
      },
    };
  };

  const provider = createOsmProvider({ fetchImpl: mockFetch });
  const route = await provider.planRoute({
    origin: { coordinate_system: "WGS84", longitude_deg: 12.49, latitude_deg: 41.89 },
    destination: { coordinate_system: "WGS84", longitude_deg: 12.50, latitude_deg: 41.90 },
  });

  assert.equal(route.schema_version, 1);
  assert.equal(route.provider, "osm");
  assert.equal(route.coordinate_system, "WGS84");
  assert.equal(route.total_distance_m, 1501);
  assert.equal(route.total_duration_s, 300);
  assert.equal(route.polyline.length, 2);
  assert.equal(route.maneuvers.length, 1);
  assert.equal(route.maneuvers[0].type, "right");
});

test("planRouteOptions returns up to 3 OSRM route candidates", async () => {
  const mockFetch = async (url) => {
    assert.match(url, /alternatives=true/);
    return {
      ok: true,
      async json() {
        return {
          code: "Ok",
          routes: [
            {
              distance: 1000,
              duration: 200,
              geometry: { coordinates: [[12.49, 41.89], [12.50, 41.90]] },
              legs: [{ steps: [] }],
            },
            {
              distance: 1200,
              duration: 240,
              geometry: { coordinates: [[12.49, 41.89], [12.51, 41.91]] },
              legs: [{ steps: [] }],
            },
          ],
        };
      },
    };
  };

  const provider = createOsmProvider({ fetchImpl: mockFetch });
  const routes = await provider.planRouteOptions({
    origin: { coordinate_system: "WGS84", longitude_deg: 12.49, latitude_deg: 41.89 },
    destination: { coordinate_system: "WGS84", longitude_deg: 12.50, latitude_deg: 41.90 },
  });

  assert.equal(routes.length, 2);
  assert.equal(routes[0].provider, "osm");
  assert.equal(routes[1].provider, "osm");
});
