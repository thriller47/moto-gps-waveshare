#!/usr/bin/env bash
# Build an Android-importable Valhalla graph TAR from Geofabrik OSM PBF.
# Requires native Valhalla CLI tools (Linux/WSL2); does not need routing.earth.
set -euo pipefail

usage() {
  echo "Usage: $0 INPUT.osm.pbf OUTPUT_DIR [--zstd] [--threads N]"
  echo "Requires valhalla_build_config, valhalla_build_tiles, valhalla_build_extract."
  exit 2
}
[[ $# -ge 2 ]] || usage
PBF=$(realpath "$1")
OUT=$2
COMPRESSION=""
THREADS=2
shift 2
while [[ $# -gt 0 ]]; do
  case "$1" in
    --zstd) [[ -z "$COMPRESSION" ]] || usage; COMPRESSION="--zstd"; shift ;;
    --threads) [[ $# -ge 2 && "$2" =~ ^[1-9][0-9]*$ ]] || usage; THREADS=$2; shift 2 ;;
    *) usage ;;
  esac
done
[[ -f "$PBF" && "$PBF" == *.osm.pbf ]] || { echo "Missing .osm.pbf: $PBF" >&2; exit 2; }
for cmd in valhalla_build_config valhalla_build_tiles valhalla_build_extract python3; do
  command -v "$cmd" >/dev/null || { echo "Missing tool: $cmd" >&2; exit 2; }
done
if [[ "$COMPRESSION" == "--zstd" ]]; then
  command -v zstd >/dev/null || { echo "Install zstd for --zstd" >&2; exit 2; }
fi
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd -P)
TILES="$OUT/valhalla_tiles"
TAR="$OUT/valhalla_tiles.tar"
CONFIG="$OUT/valhalla.json"
# Prevent accidentally mixing an old graph with a new extract.
if [[ -e "$TAR" || -e "$CONFIG" || -e "$TILES" ]]; then
  echo "Output directory already contains a previous build; choose an empty directory." >&2
  exit 2
fi
mkdir -p "$TILES"
valhalla_build_config --mjolnir-tile-dir "$TILES" \
  --mjolnir-tile-extract "$TAR" \
  --mjolnir-concurrency "$THREADS" \
  --mjolnir-timezone "$TILES/timezones.sqlite" \
  --mjolnir-admin "$TILES/admins.sqlite" > "$CONFIG"
# Timezone/admin tools are recommended by Valhalla, but optional if not
# installed; graph compilation and extract creation remain mandatory.
if command -v valhalla_build_timezones >/dev/null; then
  valhalla_build_timezones > "$TILES/timezones.sqlite"
else
  echo "WARNING: valhalla_build_timezones not found; time-dependent routing may be reduced." >&2
fi
if command -v valhalla_build_admins >/dev/null; then
  valhalla_build_admins -c "$CONFIG" "$PBF"
else
  echo "WARNING: valhalla_build_admins not found; admin-related attributes may be reduced." >&2
fi
valhalla_build_tiles -c "$CONFIG" "$PBF"
valhalla_build_extract -c "$CONFIG" -v
[[ -s "$TAR" ]] || { echo "No Valhalla TAR produced" >&2; exit 1; }
python3 - "$TAR" <<'PY'
import sys, tarfile
path = sys.argv[1]
with tarfile.open(path, "r:") as archive:
    tiles = sum(m.isfile() and m.name.endswith(".gph") for m in archive)
if tiles == 0:
    raise SystemExit("Invalid TAR: no .gph routing tiles")
print(f"Validated {tiles} .gph tiles in {path}")
PY
if [[ "$COMPRESSION" == "--zstd" ]]; then
  zstd -T2 -6 "$TAR" -o "$TAR.zst"
  echo "Android import: $TAR.zst"
else
  echo "Android import: $TAR"
fi
echo "Verify offline routing on the Android device: a valid TAR alone is not proof of engine compatibility."
