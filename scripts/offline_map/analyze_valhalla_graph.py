#!/usr/bin/env python3
"""Compare Valhalla .tar or .tar.zst archives without extracting tile files.

Reads GraphTileHeader v3 counts from each .gph member, reports by hierarchy
level, and refuses to treat unknown binary layouts as valid measurements.
Requires the zstd command for .zst files. Uses Python standard library only.
"""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import tarfile

MASK = (1 << 21) - 1
VERSION = re.compile(rb"^3\.[0-9]+(?:\.[0-9]+)?$")
# v3 GraphTileHeader layout on x86_64 little-endian:
# flags/graphid 8, base_ll 8, version 16, dataset_id 8, counts bitfield 8.
HEADER_BYTES = 48


def header_counts(raw, size):
    if len(raw) != HEADER_BYTES or size < 512:
        return None
    version = raw[16:32].split(b"\0", 1)[0]
    if not VERSION.fullmatch(version):
        return None
    bits = struct.unpack_from("<Q", raw, 40)[0]
    return version.decode("ascii"), bits & MASK, (bits >> 21) & MASK


def inspect(filename):
    path = Path(filename)
    if not path.is_file():
        raise FileNotFoundError(path)
    proc = None
    stream = None
    try:
        if path.name.lower().endswith(".zst"):
            proc = subprocess.Popen(["zstd", "-dc", "--", str(path)],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            stream = proc.stdout
        else:
            stream = path.open("rb")
        levels = defaultdict(lambda: dict(tiles=0, nodes=0, directed_edges=0, unknown=0))
        versions = defaultdict(int)
        duplicates = []
        seen = set()
        with tarfile.open(fileobj=stream, mode="r|") as archive:
            for member in archive:
                if not member.isfile() or not member.name.endswith(".gph"):
                    continue
                # Valhalla's conventional path is level/AAA/BBB/CCC.gph.
                match = re.search(r"(?:^|/)([0-9]+)/(?:(?:[0-9]+)/)*[0-9]+\.gph$", member.name)
                level = match.group(1) if match else "unclassified"
                if member.name in seen:
                    duplicates.append(member.name)
                    continue
                seen.add(member.name)
                bucket = levels[level]
                bucket["tiles"] += 1
                entry = archive.extractfile(member)
                result = header_counts(entry.read(HEADER_BYTES), member.size) if entry else None
                if result is None:
                    bucket["unknown"] += 1
                else:
                    version, nodes, edges = result
                    versions[version] += 1
                    bucket["nodes"] += nodes
                    bucket["directed_edges"] += edges
        if proc:
            stream.close()
            err = proc.stderr.read().decode("utf-8", "replace")
            if proc.wait() != 0:
                raise RuntimeError("Zstandard decompression failed: " + err[-500:])
        totals = {k: sum(v[k] for v in levels.values()) for k in
                  ("tiles", "nodes", "directed_edges", "unknown")}
        return {"archive": str(path), "archive_bytes": path.stat().st_size,
                "levels": dict(sorted(levels.items())), "totals": totals,
                "versions": dict(versions), "duplicates": duplicates[:10],
                "complete": totals["unknown"] == 0 and not duplicates}
    finally:
        if stream is not None and not stream.closed:
            stream.close()
        if proc is not None and proc.poll() is None:
            proc.kill()
            proc.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archives", nargs="+", help="One or two .tar / .tar.zst files")
    parser.add_argument("--json", dest="output", help="Save machine-readable report")
    args = parser.parse_args()
    reports = [inspect(path) for path in args.archives]
    for report in reports:
        print("\n" + report["archive"])
        print("Level       Tiles        Nodes    Directed edges  Unverified")
        for level, item in report["levels"].items():
            print(f"{level:>5} {item['tiles']:>11,} {item['nodes']:>12,} "
                  f"{item['directed_edges']:>17,} {item['unknown']:>11,}")
        print("TOTAL:", report["totals"])
        print("Versions:", report["versions"])
        if not report["complete"]:
            print("WARNING: unrecognized headers or duplicate paths; node/edge totals are incomplete.")
    if len(reports) == 2 and all(x["complete"] for x in reports):
        left, right = [r["totals"]["directed_edges"] for r in reports]
        print(f"Directed-edge difference (first - second): {left-right:+,}")
    if args.output:
        Path(args.output).write_text(json.dumps(reports, indent=2), encoding="utf-8")
        print("Saved:", args.output)
    return 0 if all(x["complete"] for x in reports) else 3


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, tarfile.TarError, RuntimeError) as error:
        sys.exit("ERROR: " + str(error))
