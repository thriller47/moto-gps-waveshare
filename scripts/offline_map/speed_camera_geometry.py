"""Conservative local road projection for the experimental camera index."""
from collections import defaultdict
import math
import osmium

CELL = 0.002
MAX_OFFSET = 30.0
ROAD_TYPES = set("motorway trunk primary secondary tertiary unclassified residential living_street motorway_link trunk_link primary_link secondary_link tertiary_link service".split())


def cell(lat, lon):
    return math.floor(lat / CELL), math.floor(lon / CELL)


def project(lat, lon, a, b):
    scale = 111195.0
    sx = scale * math.cos(math.radians(lat))
    ax, ay = (a[1]-lon)*sx, (a[0]-lat)*scale
    bx, by = (b[1]-lon)*sx, (b[0]-lat)*scale
    dx, dy = bx-ax, by-ay
    norm = dx*dx+dy*dy
    if norm < 0.01:
        return None
    t = max(0.0, min(1.0, -(ax*dx+ay*dy)/norm))
    x, y = ax+t*dx, ay+t*dy
    return (math.hypot(x,y), lat+y/scale, lon+x/sx,
            math.degrees(math.atan2(dx,dy)) % 360)


class Geometry(osmium.SimpleHandler):
    def __init__(self, nodes):
        super().__init__()
        self.grid = defaultdict(list)
        self.candidates = defaultdict(dict)
        for ref, (lat, lon, tags) in nodes.items():
            self.grid[cell(lat,lon)].append((ref,lat,lon))

    def way(self, obj):
        tags = obj.tags
        if tags.get("highway") not in ROAD_TYPES:
            return
        if any(tags.get(k) in ("private","no") for k in ("access","motor_vehicle","motorcycle")):
            return
        if tags.get("area") == "yes":
            return
        grade = (tags.get("bridge") not in (None,"no") or
                 tags.get("tunnel") not in (None,"no") or
                 tags.get("layer") not in (None,"0"))
        oneway = tags.get("oneway")
        if oneway is None and (tags.get("highway") == "motorway" or tags.get("junction") == "roundabout"):
            oneway = "yes"
        for a,b in zip(obj.nodes, list(obj.nodes)[1:]):
            if not a.location.valid() or not b.location.valid():
                continue
            p,q = (a.lat,a.lon),(b.lat,b.lon)
            lo = cell(min(p[0],q[0])-0.0004,min(p[1],q[1])-0.0006)
            hi = cell(max(p[0],q[0])+0.0004,max(p[1],q[1])+0.0006)
            for i in range(lo[0],hi[0]+1):
                for j in range(lo[1],hi[1]+1):
                    for ref,lat,lon in self.grid.get((i,j),()):
                        result = project(lat,lon,p,q)
                        if result is None or result[0] > MAX_OFFSET:
                            continue
                        old = self.candidates[ref].get(obj.id)
                        if old is None or result[0] < old[0][0]:
                            self.candidates[ref][obj.id] = (result,oneway,grade)


def resolve_geometry(tags, candidates, way, direction, confidence, reason):
    empty = (None,None,None,None)
    if confidence == "medium":
        item = candidates.get(way)
        if item:
            d,lat,lon,bearing = item[0]
            return way,direction,confidence,reason,(lat,lon,bearing,d)
        return way,direction,"low","missing_road_geometry",empty
    if reason == "direction_conflict" or not candidates:
        return way,direction,confidence,reason,empty
    ranked = sorted(candidates.items(),key=lambda item:item[1][0][0])
    best,(projection,oneway,grade) = ranked[0]
    d,lat,lon,bearing = projection
    # Do not infer the meaning of compass camera orientation or relative
    # forward/backward tags on a detached node.
    if grade or any(item[1][2] for item in ranked):
        return way,None,"low","grade_separation_candidate",empty
    if len(ranked)>1 and ranked[1][1][0][0]-d < 12:
        return way,None,"low","parallel_or_junction_ambiguity",empty
    if way is not None and way != best:
        return way,None,"low","topology_geometry_conflict",empty
    if d > 15 or oneway not in ("yes","1","true","-1"):
        return best,None,"low","geometry_direction_unresolved",(lat,lon,bearing,d)
    if tags.get("direction") or tags.get("camera:direction"):
        return best,None,"low","geometry_direction_tag_unresolved",(lat,lon,bearing,d)
    return best,("backward" if oneway=="-1" else "forward"),"medium","geometry_unique_oneway",(lat,lon,bearing,d)
