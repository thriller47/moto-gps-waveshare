# MotoPack v2 + Road DNA tourist index — experimental

**Branch:** `feature/road-dna-motopack-v2`. Work in progress. No CI build has been launched. Do not install this package on a production Android app until importer and test suite are verified.

## What's implemented

- `tourist_to_sqlite.py`: streaming 3-pass scan of the Italy PBF. First collects tourist polygon relations; second indexes tourist nodes and selected ways; third resolves referenced OSM node coordinates without the huge `sparse_file_array` index.
- Polygon assembly via Shapely `polygonize` and `unary_union`, with inner rings subtracted. Unreconstructible required geometries cause a hard error instead of silently producing a misleadingly complete database.
- Output SQLite schema v1: `tourist_poi` and `tourist_metadata`, categories, OSM identity, representative location and bounds. Area representative coordinates are **not routable destinations**.
- `motopack.py`: package manifest version **2** with mandatory tourist component `road-dna/tourist-italy-v1.sqlite`, SHA-256 verification.
- `build_motopack_wsl.sh` and protected `rebuild_motopack_wsl.sh`: build into a separate output directory; do not overwrite the old known-good MotoPack.
- `add_poi_to_photon.py`: selective two-pass OSM scan avoiding the old multi-GB sparse node index.
- `OfflineTouristPlaceSearch.kt`: Android validation, atomic installation of tourist SQLite and preliminary category/bounds lookup.
- `UnifiedOfflineMapImporter.kt`: reads v2 manifest and installs the fourth component after SHA validation.

## Run lightweight tests without a full map rebuild

```bash
cd ~/MotoGPS/moto-gps-waveshare
git fetch origin
git switch feature/road-dna-motopack-v2
git pull --ff-only origin feature/road-dna-motopack-v2
source ~/valhalla-env/bin/activate
python3 -m unittest discover -s scripts/offline_map -p 'test_*.py' -v
```

**Do not execute the national build until the tests succeed.** The new generator has not yet been exercised on the user's full PBF. First debug any geometry assembly failures; in particular large lakes and protected-area boundary relations.

## Candidate national index-only test (after lightweight tests)

```bash
python3 scripts/offline_map/tourist_to_sqlite.py \
  --pbf /mnt/c/MotoGPS/PBF/italy-latest.osm.pbf \
  --output ~/motogps-build/tourist-italy-v1.sqlite
```

This command only builds a separate tourist SQLite file. It does not rebuild or overwrite the old MotoPack. The generator refuses an existing destination and fails without publishing a partial index. Memory use is bounded by `--max-refs` (3 million by default), but actual RAM and runtime remain unmeasured.

## Full rebuild (NOT yet validated end-to-end)

After the index and Android verification are complete, the WSL wrapper runs MotoMap, Photon+service POIs, Road DNA tourist index, Valhalla and MotoPack v2 in a **new** directory. No old source files are overwritten. This is a staged pipeline, not yet a recommended production command.

## Known open items

- Android Road DNA route corridor analysis and UI still need integration.
- Unsupported non-polygon relation types are reported; do not claim that every OSM tourist relation has been located.
- Proper geometries and access points require more validation than representative points and bounding boxes.
- Dedup by relation membership is implemented, but semantic duplicates with unrelated OSM identities need further validation.
- Android imports individual components atomically, not the whole MotoPack transactionally; full rollback/swap needs hardening.
- No claim of full production readiness or CI success before execution of the relevant tests.
