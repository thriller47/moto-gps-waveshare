import unittest

from speedpack_to_sqlite import assess, continuation, cross_matches, limit_value


def road(a, b, bearing, name="SP 1", grade=False):
    return dict(a=a,b=b,ends=(a,b),projection=(5,45,7,bearing),
                grade=grade,highway="secondary",ref=None,name=name,shared={a,b})


class SpeedPackTests(unittest.TestCase):
    def test_contiguous(self):
        a,b = road(1,2,90),road(2,3,90)
        self.assertTrue(continuation(a,b))
        self.assertEqual(assess({10:a,20:b})[0],"continuity_candidate")

    def test_parallel(self):
        self.assertEqual(assess({10:road(1,2,90),20:road(3,4,90)})[0],"ambiguous")

    def test_grade(self):
        self.assertEqual(assess({10:road(1,2,90,grade=True)})[0],"grade_review")

    def test_junction(self):
        self.assertFalse(continuation(road(1,2,90),road(2,3,0)))
        self.assertEqual(assess({10:road(1,2,90),20:road(2,3,90),
                                 30:road(2,4,0)})[0],"ambiguous")

    def test_cross_matches(self):
        pairs = list(cross_matches({1:(45,7),2:(45.0001,7)},{"a":(45,7)}))
        self.assertEqual(len(pairs),2)
        self.assertEqual(sum(p[3] for p in pairs),1)

    def test_unknown_not_direction(self):
        self.assertEqual(assess({10:road(1,2,90)})[0],"single_way_candidate")
        self.assertIsNone(limit_value(True))


if __name__ == "__main__":
    unittest.main()
