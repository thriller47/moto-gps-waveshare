#!/usr/bin/env python3
"""Independent Italy road DNA index (OSM ODbL; optional Skadi elevation).
Only planning hints: Valhalla remains authoritative for routing/access.
"""
import argparse
from array import array
from functools import lru_cache
import gzip
import math
import os
from pathlib import Path
import sqlite3
import sys
import zipfile

CLASSES = {"primary", "secondary", "tertiary", "unclassified", "residential"}
PAVED = {"asphalt", "paved", "concrete", "concrete:lanes", "concrete:plates",
         "paving_stones", "sett", "chipseal"}
RESTRICTED = {"no", "private", "destination", "customers", "delivery",
              "agricultural", "forestry", "permit"}
SCHEMA = """
CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE roads(
    id INTEGER PRIMARY KEY, osm_way INTEGER NOT NULL, part INTEGER NOT NULL,
    name TEXT NOT NULL, highway TEXT NOT NULL, surface TEXT NOT NULL,
    lat REAL NOT NULL, lon REAL NOT NULL, a_lat REAL NOT NULL, a_lon REAL NOT NULL,
    b_lat REAL NOT NULL, b_lon REAL NOT NULL, length_m INTEGER NOT NULL,
    bends INTEGER NOT NULL, hairpins INTEGER NOT NULL, relief_m INTEGER,
    curve_score REAL NOT NULL, cell_lat INTEGER NOT NULL, cell_lon INTEGER NOT NULL
);
"""

def eligible(tags):
    if tags.get("highway") not in CLASSES or tags.get("junction") in ("roundabout", "circular"):
        return False
    if any(tags.get(k) in RESTRICTED for k in ("access", "vehicle", "motor_vehicle", "motorcycle", "motorcar")):
        return False
    if any(tags.get(k) for k in ("access:conditional", "vehicle:conditional",
                                 "motor_vehicle:conditional", "motorcycle:conditional", "motorcar:conditional")):
        return False
    if tags.get("surface") and tags.get("surface") not in PAVED:
        return False
    if tags.get("oneway") not in (None, "", "no", "0", "false"):
        return False
    return True

def distance(a, b):
    return math.hypot((b[1]-a[1])*111195*math.cos(math.radians((a[0]+b[0])/2)),
                      (b[0]-a[0])*111195)

def chunks(points):
    part = [points[0]]
    length = 0
    for p in points[1:]:
        step = distance(part[-1], p)
        if step < 0.5:
            continue
        part.append(p)
        length += step
        if length >= 2500:
            yield part
            part, length = [p], 0
    if len(part) >= 2:
        yield part

def analyze(points):
    chain = [0.0]
    for a, b in zip(points, points[1:]):
        chain.append(chain[-1]+distance(a,b))
    length = chain[-1]
    if length < 350 or len(points) < 4:
        return None
    # Interpolate at fixed chainage; do not count node density as curvature.
    sampled = [points[0]]
    j = 1
    for m in range(40, int(length), 40):
        while j < len(chain)-1 and chain[j] < m:
            j += 1
        f = (m-chain[j-1])/max(0.001, chain[j]-chain[j-1])
        a,b = points[j-1], points[j]
        sampled.append((a[0]+(b[0]-a[0])*f,a[1]+(b[1]-a[1])*f))
    headings = []
    for a,b in zip(sampled,sampled[1:]):
        headings.append(math.degrees(math.atan2((b[1]-a[1])*math.cos(math.radians(a[0])),b[0]-a[0])))
    # Consolidate consecutive same-direction bends.
    turns, total = [], 0.0
    for a,b in zip(headings,headings[1:]):
        delta = (b-a+180)%360-180
        if abs(delta) < 3 or (total and delta*total < 0):
            if abs(total) >= 15:
                turns.append(abs(total))
            total = 0.0
        if abs(delta) >= 3:
            total += delta
    if abs(total) >= 15:
        turns.append(abs(total))
    bends = len(turns)
    if bends == 0:
        return None
    # Anchors are genuine OSM vertices, not interpolated off-road points.
    a = points[min(range(len(chain)),key=lambda i: abs(chain[i]-length*.2))]
    b = points[min(range(len(chain)),key=lambda i: abs(chain[i]-length*.8))]
    mid = points[min(range(len(chain)),key=lambda i: abs(chain[i]-length*.5))]
    if distance(a,b) < 150:
        return None
    hairpins = sum(110 <= x <= 210 for x in turns)
    score = min(1.0, (bends+0.4*hairpins)/(length/1000)/5.0)
    return length,bends,hairpins,score,a,b,mid

class Dem:
    def __init__(self, path):
        self.archive = zipfile.ZipFile(path) if path else None
    @lru_cache(maxsize=12)
    def tile(self, ilat, ilon):
        if not self.archive:
            return None
        name = f"{'N' if ilat>=0 else 'S'}{abs(ilat):02d}"
        name2 = f"{'E' if ilon>=0 else 'W'}{abs(ilon):03d}"
        try:
            raw = gzip.decompress(self.archive.read(f"{name}/{name}{name2}.hgt.gz"))
        except KeyError:
            return None
        side = math.isqrt(len(raw)//2)
        if side not in (1201,3601) or side*side*2 != len(raw):
            return None
        values = array("h")
        values.frombytes(raw)
        if sys.byteorder == "little":
            values.byteswap()
        return side,values
    def height(self,p):
        ilat,ilon = math.floor(p[0]),math.floor(p[1])
        tile = self.tile(ilat,ilon)
        if tile is None:
            return None
        side,values = tile
        y = round((1-(p[0]-ilat))*(side-1))
        x = round((p[1]-ilon)*(side-1))
        h = values[y*side+x]
        return h if -500 <= h <= 9000 else None
    def relief(self,points):
        samples = [self.height(points[round(i*(len(points)-1)/4)]) for i in range(5)]
        if any(x is None for x in samples):
            return None
        return max(samples)-min(samples)
    def close(self):
        self.tile.cache_clear()
        if self.archive:
            self.archive.close()

def build(pbf, output, elevation):
    import osmium
    output = Path(output)
    if output.exists():
        raise FileExistsError(f"Destination already exists: {output}")
    output.parent.mkdir(parents=True,exist_ok=True)
    pending = output.with_name(output.name+".part")
    if pending.exists():
        raise FileExistsError(f"Remove interrupted temporary file first: {pending}")
    dem = Dem(elevation)
    db = sqlite3.connect(pending)
    try:
        db.executescript(SCHEMA)
        db.executemany("INSERT INTO metadata VALUES (?,?)",[
            ("schema","road-dna-roads-v1"),("source",Path(pbf).name),
            ("license","OpenStreetMap contributors, ODbL"),
            ("dem","Skadi" if elevation else "none")])
        class Handler(osmium.SimpleHandler):
            def __init__(self):
                super().__init__()
                self.count = 0
                self.batch = []
            def flush(self):
                if self.batch:
                    db.executemany("INSERT INTO roads(osm_way,part,name,highway,surface,lat,lon,a_lat,a_lon,b_lat,b_lon,length_m,bends,hairpins,relief_m,curve_score,cell_lat,cell_lon) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",self.batch)
                    db.commit()
                    self.batch.clear()
            def way(self,w):
                if not eligible(w.tags) or any(not n.location.valid() for n in w.nodes):
                    return
                points = [(n.lat,n.lon) for n in w.nodes]
                if len(points)<4:
                    return
                for i,part in enumerate(chunks(points)):
                    result = analyze(part)
                    if result is None:
                        continue
                    length,bends,hairpins,score,a,b,mid = result
                    self.batch.append((w.id,i,(w.tags.get("name") or w.tags.get("ref") or "")[:120],
                        w.tags.get("highway"),w.tags.get("surface") or "unknown",
                        *mid,*a,*b,round(length),bends,hairpins,dem.relief(part) if elevation else None,
                        score,math.floor(mid[0]*20),math.floor(mid[1]*20)))
                    self.count += 1
                    if len(self.batch)>=5000:
                        self.flush()
                        if self.count%50000==0:
                            print("Indexed sections:",self.count,flush=True)
        handler=Handler()
        handler.apply_file(str(pbf),locations=True,idx="flex_mem")
        handler.flush()
        if not handler.count:
            raise ValueError("No eligible curved road sections")
        db.execute("CREATE INDEX roads_cell ON roads(cell_lat,cell_lon)")
        db.execute("INSERT INTO metadata VALUES ('rows',?)",(str(handler.count),))
        db.commit()
        if db.execute("PRAGMA quick_check").fetchone()[0]!="ok":
            raise ValueError("SQLite integrity failure")
        db.close()
        os.replace(pending,output)
        print(f"Road DNA: {handler.count:,} sections, {output.stat().st_size/1048576:.1f} MiB: {output}")
    finally:
        db.close()
        dem.close()
        if pending.exists():
            pending.unlink()

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("pbf",type=Path)
    p.add_argument("-o","--output",type=Path,required=True)
    p.add_argument("--elevation",type=Path,help="Optional existing elevation-italy.zip")
    args=p.parse_args()
    build(args.pbf,args.output,args.elevation)

if __name__=="__main__":
    main()
