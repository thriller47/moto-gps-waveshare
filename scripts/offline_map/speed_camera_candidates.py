"""V3 candidate evidence. Unknown is not equivalent to both directions."""
from collections import defaultdict
import math


def candidate_rows(ref, tags, candidates, shared, selected, direction, confidence, reason):
    shared_ids = {row[0] for row in shared}
    for way, (projection, oneway, grade) in sorted(candidates.items()):
        offset, lat, lon, bearing = projection
        state, travel = "unknown", None
        if reason == "direction_conflict":
            state = "conflict"
        elif way == selected and confidence == "medium" and direction:
            state, travel = "resolved", direction
        elif tags.get("direction") or tags.get("camera:direction"):
            state = "uninterpreted"
        elif oneway in ("yes", "1", "true", "-1"):
            # Road travel constraint only; not evidence of camera orientation.
            state = "road_constraint"
            travel = "backward" if oneway == "-1" else "forward"
        yield (ref, way, lat, lon, bearing, offset, oneway, int(grade),
               int(way in shared_ids), state, travel)


def duplicate_pairs(nodes):
    # Flag proximity, never merge: nearby opposite-direction devices may be real.
    grid = defaultdict(list)
    size = 0.0002
    for ref, (lat, lon, _) in sorted(nodes.items()):
        i, j = math.floor(lat/size), math.floor(lon/size)
        for di in (-1, 0, 1):
            for dj in (-1, 0, 1):
                for other, a, b in grid[i+di,j+dj]:
                    d = 111195 * math.hypot(lat-a, (lon-b)*math.cos(math.radians(lat)))
                    if d <= 8:
                        yield other, ref, d
        grid[i,j].append((ref,lat,lon))
