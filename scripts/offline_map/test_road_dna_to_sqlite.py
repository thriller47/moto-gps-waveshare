import unittest
from road_dna_to_sqlite import eligible, analyze, chunks

class RoadDnaTest(unittest.TestCase):
    def test_access_surface_and_roundabouts(self):
        self.assertTrue(eligible({"highway":"secondary"}))
        for tags in ({"access":"private"},{"motorcycle":"no"},{"surface":"gravel"},
                     {"junction":"roundabout"},{"oneway":"yes"},{"access:conditional":"no @ (winter)"}):
            self.assertFalse(eligible({"highway":"secondary",**tags}))
    def test_straight_not_indexed(self):
        self.assertIsNone(analyze([(45+i*.002,7) for i in range(8)]))
    def test_curved_section_uses_osm_vertices(self):
        points=[(45,7),(45.002,7),(45.003,7.001),(45.004,7.003),
                (45.006,7.003),(45.008,7.001),(45.010,7.001)]
        result=analyze(points)
        self.assertIsNotNone(result)
        self.assertGreater(result[1],0)
        self.assertIn(result[4],points)
        self.assertIn(result[5],points)
    def test_chunks_share_boundary(self):
        points=[(45+i*.002,7) for i in range(30)]
        parts=list(chunks(points))
        self.assertGreater(len(parts),1)
        for a,b in zip(parts,parts[1:]):
            self.assertEqual(a[-1],b[0])

if __name__=="__main__":
    unittest.main()
