import unittest
from speed_camera_candidates import candidate_rows, duplicate_pairs


class CandidateTests(unittest.TestCase):
    def test_unknown_not_both(self):
        rows = list(candidate_rows(1, {}, {2: ((4,45,7,90),None,False)}, [],
                                   2,None,"low","geometry_direction_unresolved"))
        self.assertEqual(rows[0][9:], ("unknown",None))

    def test_oneway_not_resolved(self):
        rows = list(candidate_rows(1, {}, {2: ((4,45,7,90),"-1",False)}, [],
                                   None,None,"low","parallel_or_junction_ambiguity"))
        self.assertEqual(rows[0][9:], ("road_constraint","backward"))

    def test_duplicate_hint(self):
        self.assertEqual(len(list(duplicate_pairs({1:(45,7,{}),2:(45,7.00001,{})}))),1)


if __name__ == "__main__":
    unittest.main()
