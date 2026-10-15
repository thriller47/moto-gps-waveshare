import { fetchJsonWithRetry } from "./upstream-retry.js";

export function createNominatimPlacesProvider(config = {}) {
  const baseUrl = config.baseUrl || "https://nominatim.openstreetmap.org";

  return {
    async searchPlaces(query) {
      const url = `${baseUrl}/search?q=${encodeURIComponent(query.keywords)}&format=json&limit=10`;

      const response = await fetchJsonWithRetry(url, {
          method: "GET",
          headers: { "User-Agent": "MotoGPS-AndroidPort/1.0" }
      });

      const places = response.map(item => {
          return {
              id: item.place_id.toString(),
              name: item.name || item.display_name.split(",")[0],
              address: item.display_name,
              location_wgs84: {
                  longitude_e6: Math.round(parseFloat(item.lon) * 1e6),
                  latitude_e6: Math.round(parseFloat(item.lat) * 1e6)
              },
              distance_meters: 0 // Nominatim doesn't provide distance natively
          };
      });

      return {
        protocol_version: 1,
        query: query.keywords,
        places: places
      };
    }
  };
}
