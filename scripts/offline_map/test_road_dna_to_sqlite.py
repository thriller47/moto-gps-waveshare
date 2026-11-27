import unittest
import sqlite3
from road_dna_to_sqlite import eligible, analyze, chunks, road_tags, coverage, SCHEMA, EXTRA_TAGS

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
    def test_osm_attributes_preserve_unknown_and_units(self):
        tags = dict(zip(EXTRA_TAGS, ("bad", "3.5 m", "2", "IT:urban", "no", "10%", "yes", "no")))
        self.assertEqual(road_tags(tags), tuple(tags[t] for t in EXTRA_TAGS))
        self.assertEqual(road_tags({"smoothness": "  "}), (None,)*len(EXTRA_TAGS))

    def test_coverage_counts_sections_and_length(self):
        with sqlite3.connect(":memory:") as db:
            db.executescript(SCHEMA)
            columns = ("osm_way,part,name,highway,surface,lat,lon,a_lat,a_lon,b_lat,b_lon,"
                       "length_m,bends,hairpins,relief_m,curve_score,cell_lat,cell_lon,"
                       + ",".join(EXTRA_TAGS))
            base = (123,0,"Test","secondary","asphalt",45,7,45,7,45.01,7.01)
            suffix = (4,1,None,.8,900,140)
            sql = f"INSERT INTO roads({columns}) VALUES ({','.join('?' for _ in range(18+len(EXTRA_TAGS)))})"
            db.execute(sql, base+(1000,)+suffix+road_tags({"smoothness":"bad","lit":"no"}))
            db.execute(sql, base+(3000,)+suffix+road_tags({}))
            report = coverage(db)
            self.assertEqual(report["sections"],2)
            self.assertEqual(report["length_m"],4000)
            stats = report["tags"]["smoothness"]
            self.assertEqual(stats["section_coverage_pct"],50.0)
            self.assertEqual(stats["length_coverage_pct"],25.0)
            self.assertEqual(stats["values"]["bad"]["length_m"],1000)
            self.assertEqual(stats["values"]["(missing)"]["sections"],1)
            self.assertEqual(report["tags"]["lit"]["values"]["no"]["sections"],1)
            self.assertEqual(report["tags"]["surface"]["length_coverage_pct"],100.0)

    def test_chunks_share_boundary(self):
        points=[(45+i*.002,7) for i in range(30)]
        parts=list(chunks(points))
        self.assertGreater(len(parts),1)
        for a,b in zip(parts,parts[1:]):
            self.assertEqual(a[-1],b[0])

if __name__=="__main__":
    unittest.main()
