"""MotoMap v4 converter contract; no PBF file or pyosmium required."""
import importlib.util
from pathlib import Path
import sys
import types
import unittest


class DummyHandler:
    pass


# Import the real converter while replacing only its external PBF reader.
previous = sys.modules.get("osmium")
sys.modules["osmium"] = types.SimpleNamespace(SimpleHandler=DummyHandler)
spec = importlib.util.spec_from_file_location(
    "pbf_to_motomap", Path(__file__).with_name("pbf_to_motomap.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
if previous is None:
    del sys.modules["osmium"]
else:
    sys.modules["osmium"] = previous


class FakeNode:
    def __init__(self, latitude, longitude, node_id):
        self.lat = latitude
        self.lon = longitude
        self.ref = node_id
        self.location = self

    def valid(self):
        return True


class FakeWay:
    def __init__(self, tags, nodes):
        self.id = 34567
        self.tags = tags
        self.nodes = nodes


class MotoMapV4Tests(unittest.TestCase):
    def test_crossing_keeps_distinct_osm_node_ids(self):
        bridge = FakeWay(
            {"highway": "primary", "bridge": "yes"},
            [FakeNode(45.0, 8.0, 100), FakeNode(45.001, 8.0, 101)])
        motorway = FakeWay(
            {"highway": "motorway"},
            [FakeNode(45.0005, 7.999, 200), FakeNode(45.0005, 8.001, 201)])
        top = module.rows_for_way(bridge)[0][1]
        below = module.rows_for_way(motorway)[0][1]
        self.assertTrue(top.startswith("1,90,1,34567,0|"))
        self.assertTrue(below.startswith("0,110,0,34567,0|"))
        self.assertIn("45000000,8000000,2s", top)
        self.assertIn("45001000,8000000,2t", top)
        self.assertIn("5k", below)  # 200 = 5k in base36
        self.assertNotIn("2s", below)

    def test_explicit_roundabout_and_circular_tags_are_preserved(self):
        nodes = [FakeNode(45.0, 8.0, 100),
                 FakeNode(45.0001, 8.0001, 101)]
        self.assertTrue(module.rows_for_way(FakeWay(
            {"highway": "residential", "junction": "roundabout"}, nodes
        ))[0][1].startswith("3,50,0,34567,1|"))
        self.assertTrue(module.rows_for_way(FakeWay(
            {"highway": "secondary", "junction": "circular"}, nodes
        ))[0][1].startswith("2,70,0,34567,2|"))
        self.assertTrue(module.rows_for_way(FakeWay(
            {"highway": "residential", "junction": "not_a_roundabout"}, nodes
        ))[0][1].startswith("3,50,0,34567,0|"))

    def test_distinct_osm_ids_at_same_rounded_position_are_kept(self):
        way = FakeWay({"highway": "residential"}, [
            FakeNode(45.0, 8.0, 100),
            FakeNode(45.0, 8.0, 101),
            FakeNode(45.001, 8.0, 102),
        ])
        line = module.rows_for_way(way)[0][1]
        self.assertIn("45000000,8000000,2s;45000000,8000000,2t", line)
        self.assertEqual(3, len(line.strip().split("|")[2].split(";")))

    def test_explicit_layer_takes_precedence(self):
        self.assertEqual(-2, module.road_level(
            {"bridge": "yes", "layer": "-2"}))
        self.assertEqual(-1, module.road_level({"tunnel": "yes"}))
        self.assertEqual(0, module.road_level({}))

    def test_two_ways_sharing_node_keep_identical_id(self):
        way_a = FakeWay({"highway": "residential"}, [
            FakeNode(45.0, 8.0, 17), FakeNode(45.001, 8.0, 18)])
        way_b = FakeWay({"highway": "residential"}, [
            FakeNode(45.001, 8.0, 18), FakeNode(45.001, 8.001, 19)])
        self.assertIn("45001000,8000000,i", module.rows_for_way(way_a)[0][1])
        self.assertIn("45001000,8000000,i", module.rows_for_way(way_b)[0][1])


if __name__ == "__main__":
    unittest.main()
