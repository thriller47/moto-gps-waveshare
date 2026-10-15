import { fetchJsonWithRetry } from "./upstream-retry.js";

// Generates route geometries via OSRM directly in WGS84
export function createOsrmProvider(config = {}) {
  const baseUrl = config.baseUrl || "https://router.project-osrm.org";

  async function route(query) {
    const origin = query.origin;
    const dest = query.destination;

    // OSRM coordinates are lon,lat
    const url = `${baseUrl}/route/v1/driving/${origin.longitude},${origin.latitude};${dest.longitude},${dest.latitude}?overview=full&geometries=geojson&steps=true`;

    const response = await fetchJsonWithRetry(url, { method: "GET" });

    if (response.code !== "Ok" || !response.routes || response.routes.length === 0) {
        throw new Error("No route found from OSRM");
    }

    const route = response.routes[0];
    const generatedAtMs = Date.now();

    const resultOptions = {
        protocol_version: 1,
        generated_at_ms: generatedAtMs,
        provider_name: "osrm",
        distance_meters: Math.round(route.distance),
        duration_seconds: Math.round(route.duration),
        taxi_cost: 0,
        paths: [{
            distance_meters: Math.round(route.distance),
            duration_seconds: Math.round(route.duration),
            traffic_light_count: 0,
            strategy_name: "recommended"
        }]
    };

    const resultRoute = {
        protocol_version: 1,
        generated_at_ms: generatedAtMs,
        provider_name: "osrm",
        route_id: `osrm_${generatedAtMs}`,
        distance_meters: Math.round(route.distance),
        duration_seconds: Math.round(route.duration),
        traffic_light_count: 0,
        geometry_wgs84: [],
        segments: [],
        traffic: []
    };

    // Parse steps for maneuvers
    let currentDistance = 0;

    if (route.legs && route.legs.length > 0) {
        const steps = route.legs[0].steps;
        for (const step of steps) {
            const geom = step.geometry.coordinates; // Array of [lon, lat]

            const segment = {
                instruction: step.maneuver.modifier ? `Turn ${step.maneuver.modifier}` : "Continue",
                road_name: step.name || "Unknown road",
                distance_meters: Math.round(step.distance),
                duration_seconds: Math.round(step.duration),
                action: mapOsrmAction(step.maneuver.modifier),
                polyline_point_index: resultRoute.geometry_wgs84.length,
                polyline_point_count: geom.length
            };

            resultRoute.segments.push(segment);

            for (const coord of geom) {
                // GeoJSON provides lon, lat
                resultRoute.geometry_wgs84.push({
                    longitude_e6: Math.round(coord[0] * 1e6),
                    latitude_e6: Math.round(coord[1] * 1e6)
                });
            }
        }
    }

    return { resultOptions, resultRoute };
  }

  return {
    async planRoute(query) {
        return (await route(query)).resultRoute;
    },
    async planRouteOptions(query) {
        return (await route(query)).resultOptions;
    }
  };
}

function mapOsrmAction(modifier) {
    if (!modifier) return "continue";
    switch (modifier.toLowerCase()) {
        case "left": return "turn_left";
        case "right": return "turn_right";
        case "slight left": return "keep_left";
        case "slight right": return "keep_right";
        case "sharp left": return "turn_left";
        case "sharp right": return "turn_right";
        case "uturn": return "u_turn";
        default: return "continue";
    }
}
