#!/usr/bin/env python3
"""Package cached Tilezen/Skadi HGT.gz files for MotoPack v3 (Python stdlib)."""
import argparse
import gzip
from pathlib import Path
import re
import zipfile

PATTERN = re.compile(r"[NS][0-9]{2}/[NS][0-9]{2}[EW][0-9]{3}\.hgt\.gz")
REQUIRED = {"N45/N45E009.hgt.gz", "N40/N40E014.hgt.gz"}


def validate_source(source):
    tiles = sorted((p for p in source.rglob("*.hgt.gz")
                    if PATTERN.fullmatch(p.relative_to(source).as_posix())),
                   key=lambda p: p.relative_to(source).as_posix())
    names = {p.relative_to(source).as_posix() for p in tiles}
    if len(tiles) < 50 or not REQUIRED.issubset(names):
        raise ValueError("Skadi DEM incomplete for Italy: found " +
                         str(len(tiles)) + " files; missing sample " +
                         repr(sorted(REQUIRED - names)))
    for file in tiles:
        if file.stat().st_size < 100:
            raise ValueError("Empty DEM tile: " + str(file))
        with gzip.open(file, "rb") as stream:
            if len(stream.read(2)) != 2:
                raise ValueError("Damaged DEM gzip: " + str(file))
    return tiles


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    tiles = validate_source(args.source)
    if args.output.exists():
        raise FileExistsError("Already generated: " + str(args.output))
    pending = args.output.with_name(args.output.name + ".part")
    if pending.exists():
        raise FileExistsError("Incomplete previous DEM ZIP: " + str(pending))
    try:
        with zipfile.ZipFile(pending, "w", allowZip64=True,
                             compression=zipfile.ZIP_STORED) as archive:
            for tile in tiles:
                archive.write(tile, tile.relative_to(args.source).as_posix())
        pending.replace(args.output)
    finally:
        if pending.exists():
            pending.unlink()
    print("Prepared DEM archive:", args.output, "tiles:", len(tiles))


if __name__ == "__main__":
    main()
