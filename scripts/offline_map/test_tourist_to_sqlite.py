"""Small pure geometry/category regressions for Road DNA tourist importer.

No Italian PBF, network, Android build or GitHub Actions required.
"""
import unittest

from tourist_to_sqlite import NodeRefFilter, classify, polygon_from_member_lines


class TouristIndexTests(unittest.TestCase):
    def test_disk_reference_bloom_has_no_false_negatives(self):
        refs = [1, 2, 1234, 10_000_000_001, 99_999_999_999]
        bloom = NodeRefFilter()
        for ref in refs:
            bloom.add(ref)
        for ref in refs:
            self.assertTrue(bloom.maybe(ref))
        self.assertFalse(bloom.maybe(55_555_555_555))

    def test_secondary_water_tag(self):
        self.assertEqual(classify({"natural": "water", "water": "lake"}), ["lago"])
        self.assertEqual(classify({"natural": "water", "water": "reservoir"}), [])
        self.assertEqual(classify({"amenity": "public_bath", "bath:type": "thermal"}),
                         ["terme_stabilimento"])

    def test_relations_stitch_disconnected_member_ways(self):
        outer = [
            [(7, 45), (8, 45), (8, 46)],
            [(8, 46), (7, 46), (7, 45)],
        ]
        geom = polygon_from_member_lines(outer, [])
        self.assertIsNotNone(geom)
        self.assertAlmostEqual(geom.area, 1.0)
        self.assertTrue(geom.contains(geom.representative_point()))

    def test_relation_with_hole_not_navigable_at_center(self):
        outer = [[(7, 45), (9, 45), (9, 47), (7, 47), (7, 45)]]
        inner = [[(7.5, 45.5), (8.5, 45.5), (8.5, 46.5),
                  (7.5, 46.5), (7.5, 45.5)]]
        geom = polygon_from_member_lines(outer, inner)
        self.assertIsNotNone(geom)
        self.assertAlmostEqual(geom.area, 3.0)
        self.assertTrue(geom.contains(geom.representative_point()))
        from shapely.geometry import Point
        self.assertFalse(geom.contains(Point(8, 46)))

    def test_unclosed_relation_is_rejected(self):
        self.assertIsNone(polygon_from_member_lines(
            [[(7, 45), (8, 45), (8, 46)]], []))


if __name__ == "__main__":
    unittest.main()
