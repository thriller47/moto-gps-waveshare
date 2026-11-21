"""Pure unit tests; no PBF, API key or HTTP requests required."""
import json
import tempfile
import unittest
from pathlib import Path

from compare_sources import (
    RouteSpatialIndex, bbox, classify, meters_from_route, parse_route, route_matches, valid,
)


class RoadDnaSourceSmokeTests(unittest.TestCase):
    def test_secondary_tags_matter(self):
        self.assertEqual(classify({"natural": "water", "water": "lake"}), {"lago"})
        self.assertEqual(classify({"natural": "water", "water": "reservoir"}), set())
        self.assertEqual(classify({"amenity": "public_bath", "bath:type": "thermal"}),
                         {"terme_stabilimento"})
        self.assertEqual(classify({"amenity": "public_bath"}), set())
        self.assertEqual(classify({"tourism": "viewpoint", "historic": "monument"}),
                         {"belvedere", "monumento"})

    def test_geojson_route_coordinates_are_lon_lat(self):
        with tempfile.TemporaryDirectory() as tmp:
            route = Path(tmp) / "giro.geojson"
            route.write_text(json.dumps({
                "type": "Feature", "geometry": {"type": "LineString",
                "coordinates": [[7.1, 45.1], [7.2, 45.2], [7.2, 45.2], [7.3, 45.3]]},
                "properties": {}}), encoding="utf-8")
            self.assertEqual(parse_route(route),
                             [[7.1, 45.1], [7.2, 45.2], [7.3, 45.3]])

    def test_moto_json_object_geometry(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "route.json"
            path.write_text(json.dumps({
                "geometry_gcj02": [
                    {"latitude_deg": 45.0, "longitude_deg": 7.0},
                    {"latitude_deg": 45.1, "longitude_deg": 7.1}
                ]}), encoding="utf-8")
            self.assertEqual(parse_route(path), [[7.0, 45.0], [7.1, 45.1]])

    def test_gpx_track_and_route(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "track.gpx"
            path.write_text(
                '<?xml version="1.0"?><gpx xmlns="http://www.topografix.com/GPX/1/1">'
                '<trk><trkseg><trkpt lat="45.0" lon="7.0"/>'
                '<trkpt lat="45.1" lon="7.1"/></trkseg></trk></gpx>',
                encoding="utf-8")
            self.assertEqual(parse_route(path), [[7.0, 45.0], [7.1, 45.1]])

    def test_close_to_segment_not_only_vertices(self):
        route = [[7.0, 45.0], [7.2, 45.0]]
        self.assertLess(meters_from_route(7.1, 45.0005, route), 60)
        self.assertGreater(meters_from_route(7.1, 45.1, route), 10000)
        routes = {"giro": route}
        boxes = {"giro": bbox(route, 2000)}
        self.assertEqual(route_matches([(7.1, 45.0005)], routes, 2000, boxes),
                         ["giro"])
        self.assertEqual(route_matches([(7.1, 45.1)], routes, 2000, boxes), [])

    def test_spatial_grid_matches_bruteforce_near_route(self):
        route = [[7.0 + i * 0.0009, 45.0 + 0.001 * (i % 11)]
                 for i in range(1500)]
        idx = RouteSpatialIndex(route, 2000)
        points = [(7.0, 45.0), (7.5, 45.015), (7.8, 45.0),
                  (8.30, 45.024), (8.50, 45.08), (7.6, 45.1)]
        for lon, lat in points:
            self.assertEqual(meters_from_route(lon, lat, route) <= 2000,
                             idx.near(lon, lat), (lon, lat))
        routes = {"giro": route}
        boxes = {"giro": bbox(route, 2000)}
        near = route_matches([(7.5, 45.015)], routes, 2000, boxes,
                             {"giro": idx})
        self.assertEqual(["giro"], near)

    def test_invalid_coordinates(self):
        self.assertFalse(valid(190, 45))
        self.assertFalse(valid(7, float("nan")))


if __name__ == "__main__":
    unittest.main()
