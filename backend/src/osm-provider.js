import { createHash } from "node:crypto";

function distanceMetres(left, right) {
  const toRadians = (degrees) => (degrees * Math.PI) / 180;
  const latitude1 = toRadians(left.latitude_deg);
  const latitude2 = toRadians(right.latitude_deg);
  const latitudeDelta = latitude2 - latitude1;
  const longitudeDelta = toRadians(right.longitude_deg - left.longitude_deg);
  const halfLatitude = Math.sin(latitudeDelta / 2);
  const halfLongitude = Math.sin(longitudeDelta / 2);
  const haversine =
    halfLatitude * halfLatitude +
    Math.cos(latitude1) * Math.cos(latitude2) * halfLongitude * halfLongitude;
  return Math.round(6_371_000 * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine)));
}

function mapOsmManeuver(type, modifier) {
  const t = String(type ?? "").toLowerCase();
  const m = String(modifier ?? "").toLowerCase();

  if (t === "arrive") return "arrive";
  if (t === "roundabout" || t === "rotary" || t === "roundabout turn") return "roundabout";
  if (t === "off ramp" || t === "exit roundabout" || t === "exit rotary") return "exit";

  if (m === "uturn") return m.includes("right") ? "u_turn_right" : "u_turn_left";
  if (m === "sharp right") return "sharp_right";
  if (m === "right") return "right";
  if (m === "slight right") return "slight_right";
  if (m === "straight") return "continue";
  if (m === "slight left") return "slight_left";
  if (m === "left") return "left";
  if (m === "sharp left") return "sharp_left";

  if (t === "depart" || t === "continue" || t === "new name") return "continue";
  return "unknown";
}

function transformOsmRoute(osrmRoute, { generatedAtMs = Date.now() } = {}) {
  const coordinates = osrmRoute.geometry?.coordinates ?? [];
  const polyline = coordinates.map(([longitude_deg, latitude_deg]) => ({
    longitude_deg: Number(longitude_deg),
    latitude_deg: Number(latitude_deg),
  }));

  if (polyline.length < 2) {
    const error = new Error("OSRM route polyline is invalid");
    error.code = "INVALID_POLYLINE";
    error.retryable = false;
    throw error;
  }

  const maneuvers = [];
  let routeOffsetM = 0;
  const steps = osrmRoute.legs?.[0]?.steps ?? [];

  for (const [index, step] of steps.entries()) {
    const stepDistanceM = Math.round(step.distance ?? 0);
    const roadName = String(step.name ?? "").trim().slice(0, 96);
    const instruction = String(
      step.maneuver?.instruction ?? (roadName ? `Turn onto ${roadName}` : "Continue"),
    ).slice(0, 256);
    const roundaboutExit = Math.min(32, Math.max(0, Number(step.maneuver?.exit ?? 0)));

    maneuvers.push({
      id: index + 1,
      type: mapOsmManeuver(step.maneuver?.type, step.maneuver?.modifier),
      route_offset_m: routeOffsetM + stepDistanceM,
      road_name: roadName,
      instruction: instruction,
      roundabout_exit: roundaboutExit,
    });
    routeOffsetM += stepDistanceM;
  }

  const totalDistanceM = Math.round(osrmRoute.distance ?? routeOffsetM);
  const totalDurationS = Math.round(osrmRoute.duration ?? 0);
  const routeId = `osm-${createHash("sha256")
    .update(JSON.stringify({ totalDistanceM, totalDurationS, polyline }))
    .digest("hex")
    .slice(0, 20)}`;

  return {
    schema_version: 1,
    route_id: routeId,
    provider: "osm",
    coordinate_system: "WGS84",
    generated_at_ms: Math.max(0, Math.trunc(generatedAtMs)),
    total_distance_m: totalDistanceM,
    total_duration_s: totalDurationS,
    polyline,
    maneuvers,
    traffic: [],
  };
}

export function createOsmProvider({
  nominatimBaseUrl = "https://nominatim.openstreetmap.org",
  osrmBaseUrl = "https://router.project-osrm.org",
  userAgent = "moto-gps-waveshare/1.0",
  fetchImpl = globalThis.fetch,
} = {}) {
  nominatimBaseUrl = (nominatimBaseUrl || "https://nominatim.openstreetmap.org").replace(/\/+$/, "");
  osrmBaseUrl = (osrmBaseUrl || "https://router.project-osrm.org").replace(/\/+$/, "");
  userAgent = userAgent || "moto-gps-waveshare/1.0";

  async function fetchJson(url) {
    let response;
    try {
      response = await fetchImpl(url, {
        headers: {
          "User-Agent": userAgent,
          Accept: "application/json",
        },
      });
    } catch {
      const error = new Error("External OSM service unavailable");
      error.code = "PROVIDER_UNAVAILABLE";
      error.retryable = true;
      throw error;
    }

    if (!response.ok) {
      const error = new Error(`External OSM service failed with status ${response.status}`);
      error.code = "UPSTREAM_ERROR";
      error.retryable = true;
      throw error;
    }

    try {
      return await response.json();
    } catch {
      const error = new Error("External OSM service returned invalid JSON");
      error.code = "INVALID_RESPONSE";
      error.retryable = true;
      throw error;
    }
  }

  return {
    async searchPlaces(query) {
      const keywords = String(query?.keywords ?? "").trim();
      const region = String(query?.region ?? "").trim();
      const searchText = region ? `${keywords}, ${region}` : keywords;

      const url = `${nominatimBaseUrl}/search?q=${encodeURIComponent(
        searchText,
      )}&format=json&addressdetails=1&limit=10`;

      const data = await fetchJson(url);
      if (!Array.isArray(data)) {
        throw new Error("Invalid response from Nominatim");
      }

      const places = data.map((item) => {
        const lon = parseFloat(item.lon);
        const lat = parseFloat(item.lat);
        const point = {
          coordinate_system: "WGS84",
          longitude_deg: lon,
          latitude_deg: lat,
        };

        const city =
          item.address?.city ||
          item.address?.town ||
          item.address?.municipality ||
          item.address?.state ||
          "";
        const district =
          item.address?.suburb || item.address?.district || item.address?.county || "";

        const place = {
          id: String(item.place_id),
          name: item.name || item.display_name?.split(",")[0] || keywords,
          address: item.display_name ?? "",
          city,
          district,
          display_area: [city, district].filter(Boolean).join(" · "),
          location: point,
        };

        if (query?.origin) {
          place.distance_m = distanceMetres(query.origin, point);
        }

        return place;
      });

      return {
        protocol_version: 1,
        query: keywords,
        places,
      };
    },

    async searchCities(query) {
      const keywords = String(query?.keywords ?? "").trim();
      const url = `${nominatimBaseUrl}/search?q=${encodeURIComponent(
        keywords,
      )}&format=json&addressdetails=1&limit=10`;

      const data = await fetchJson(url);
      if (!Array.isArray(data)) {
        throw new Error("Invalid response from Nominatim");
      }

      const cities = data.map((item) => {
        let bounds = [0, 0, 0, 0];
        if (Array.isArray(item.boundingbox) && item.boundingbox.length === 4) {
          const south = parseFloat(item.boundingbox[0]);
          const north = parseFloat(item.boundingbox[1]);
          const west = parseFloat(item.boundingbox[2]);
          const east = parseFloat(item.boundingbox[3]);
          bounds = [west, south, east, north];
        } else {
          const lon = parseFloat(item.lon);
          const lat = parseFloat(item.lat);
          bounds = [lon - 0.05, lat - 0.05, lon + 0.05, lat + 0.05];
        }

        return {
          id: String(item.place_id),
          name: item.name || item.display_name?.split(",")[0] || keywords,
          detail: item.addresstype || item.type || "city",
          bounds_wgs84: bounds,
        };
      });

      return { cities };
    },

    async planRoute(body) {
      const origin = body?.origin;
      const destination = body?.destination;

      if (!origin || !destination) {
        const error = new Error("Origin and destination are required");
        error.code = "INVALID_REQUEST";
        error.retryable = false;
        throw error;
      }

      const coords = `${origin.longitude_deg},${origin.latitude_deg};${destination.longitude_deg},${destination.latitude_deg}`;
      const url = `${osrmBaseUrl}/route/v1/driving/${coords}?overview=full&geometries=geojson&steps=true`;

      const data = await fetchJson(url);

      if (!data.routes || !Array.isArray(data.routes) || data.routes.length === 0) {
        const error = new Error("No route found between origin and destination");
        error.code = "ROUTE_NOT_FOUND";
        error.retryable = false;
        throw error;
      }

      return transformOsmRoute(data.routes[0]);
    },

    async planRouteOptions(body) {
      const origin = body?.origin;
      const destination = body?.destination;

      if (!origin || !destination) {
        const error = new Error("Origin and destination are required");
        error.code = "INVALID_REQUEST";
        error.retryable = false;
        throw error;
      }

      const coords = `${origin.longitude_deg},${origin.latitude_deg};${destination.longitude_deg},${destination.latitude_deg}`;
      const url = `${osrmBaseUrl}/route/v1/driving/${coords}?overview=full&geometries=geojson&steps=true&alternatives=true`;

      const data = await fetchJson(url);

      if (!data.routes || !Array.isArray(data.routes) || data.routes.length === 0) {
        const error = new Error("No route found between origin and destination");
        error.code = "ROUTE_NOT_FOUND";
        error.retryable = false;
        throw error;
      }

      return data.routes.slice(0, 3).map((osrmRoute) => transformOsmRoute(osrmRoute));
    },
  };
}
