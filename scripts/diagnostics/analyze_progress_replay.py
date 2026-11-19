#!/usr/bin/env python3
"""Audit private Moto Chat GPS JSONL replays with full-route matcher evidence.

Requires a NEW Android build: 17 Nov legacy replays have no route_full or
progress_debug events. No network, third-party packages or GPS upload.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import sys
import zipfile
from pathlib import Path

METERS_PER_DEGREE = 111195.0
FIELDS = (
    "session_t_ms", "route_token", "generation", "match_mode",
    "matched_progress_m", "previous_progress_m", "scored_candidate_m",
    "raw_remaining_m", "guarded_remaining_m", "guard_delta_m",
    "plan_progress_m", "nearest_cross_m", "nearest_route_progress_m",
    "reported_cross_m", "fix_step_m", "next_type", "next_id", "turn_m",
)


def read_records(path: Path):
    """Return (session millisecond, event) across ordered JSONL archive parts."""
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            names = sorted(n for n in archive.namelist() if n.endswith(".jsonl"))
            if not names:
                raise ValueError("Archivio ZIP senza parti JSONL")
            for name in names:
                with archive.open(name) as handle:
                    yield from _read_lines(
                        (b.decode("utf-8") for b in handle), name)
    else:
        with path.open(encoding="utf-8") as handle:
            yield from _read_lines(handle, str(path))


def _read_lines(lines, label: str):
    offset_ms = 0
    for lineno, line in enumerate(lines, 1):
        if not line.strip():
            continue
        try:
            event = json.loads(line)
        except ValueError as exc:
            raise ValueError(f"{label}:{lineno}: JSON non valido") from exc
        if not isinstance(event, dict):
            continue
        if event.get("type") == "meta":
            offset_ms = int(event.get("session_elapsed_ms", 0))
        yield offset_ms + int(event.get("t_ms", 0)), event


def meters(a, b) -> float:
    lat = (a[0] + b[0]) * 0.5
    return math.hypot(
        (a[0] - b[0]) * METERS_PER_DEGREE,
        (a[1] - b[1]) * METERS_PER_DEGREE *
        math.cos(math.radians(lat)),
    )


def route_cumulative(points):
    totals = [0.0]
    for a, b in zip(points, points[1:]):
        totals.append(totals[-1] + meters(a, b))
    return totals


def nearest_route_position(points, cumulative, lat: float, lon: float):
    """Global closest point for DIAGNOSIS, not an alternative route matcher."""
    if len(points) < 2:
        return math.nan, math.nan
    scale_x = METERS_PER_DEGREE * math.cos(math.radians(lat))
    best_cross = math.inf
    best_along = math.nan
    for i in range(len(points) - 1):
        a, b = points[i], points[i + 1]
        ax = (a[1] - lon) * scale_x
        ay = (a[0] - lat) * METERS_PER_DEGREE
        dx = (b[1] - a[1]) * scale_x
        dy = (b[0] - a[0]) * METERS_PER_DEGREE
        length2 = dx * dx + dy * dy
        fraction = max(0.0, min(1.0, -(ax * dx + ay * dy) / length2)) \
            if length2 > 1e-6 else 0.0
        distance = math.hypot(ax + fraction * dx, ay + fraction * dy)
        if distance < best_cross:
            best_cross = distance
            best_along = cumulative[i] + fraction * (
                cumulative[i + 1] - cumulative[i])
    return best_cross, best_along


def _number(event, key):
    value = event.get(key)
    return float(value) if isinstance(value, (int, float)) else math.nan


def analyze(events):
    """Audit recorded decisions without pretending to re-run old Android code."""
    events = list(events)
    routes = {}
    for _, event in events:
        if event.get("type") != "route_full":
            continue
        points = event.get("points", [])
        if len(points) < 2:
            continue
        key = (event.get("route_token"), event.get("generation"))
        routes[key] = (points, route_cumulative(points))

    rows = []
    missing_route = 0
    frozen_m = 0.0
    peak_frozen_m = 0.0
    prev_fix = None
    prev_match = None
    prev_key = None
    held = 0
    for t_ms, event in events:
        if event.get("type") != "progress_debug":
            continue
        key = (event.get("route_token"), event.get("generation"))
        found = routes.get(key)
        if found is None:
            missing_route += 1
            continue
        lat, lon = _number(event, "lat"), _number(event, "lon")
        if not all(math.isfinite(n) for n in (lat, lon)):
            continue
        cross, nearest_m = nearest_route_position(
            found[0], found[1], lat, lon)
        matched = _number(event, "matched_progress_m")
        raw = _number(event, "raw_remaining_m")
        guarded = _number(event, "guarded_remaining_m")
        if event.get("match_mode") == "hold_offroute":
            held += 1
        if prev_key == key and prev_fix is not None:
            traveled = meters((lat, lon), prev_fix)
            if math.isfinite(matched) and math.isfinite(prev_match) and \
                    abs(matched - prev_match) <= 3.0:
                frozen_m += traveled
                peak_frozen_m = max(peak_frozen_m, frozen_m)
            else:
                frozen_m = 0.0
        else:
            frozen_m = 0.0
        prev_key, prev_fix, prev_match = key, (lat, lon), matched
        row = {
            "session_t_ms": t_ms,
            "route_token": key[0],
            "generation": key[1],
            "match_mode": event.get("match_mode", ""),
            "matched_progress_m": matched,
            "previous_progress_m": _number(event, "previous_progress_m"),
            "scored_candidate_m": _number(event, "scored_candidate_m"),
            "raw_remaining_m": raw,
            "guarded_remaining_m": guarded,
            "guard_delta_m": guarded - raw,
            "plan_progress_m": _number(event, "plan_progress_m"),
            "nearest_cross_m": cross,
            "nearest_route_progress_m": nearest_m,
            "reported_cross_m": _number(event, "nearest_cross_m"),
            "fix_step_m": _number(event, "fix_step_m"),
            "next_type": event.get("next_type", ""),
            "next_id": event.get("next_id", 0),
            "turn_m": event.get("turn_m", 0),
        }
        rows.append(row)

    return rows, {
        "routes": len(routes),
        "missing_route": missing_route,
        "held": held,
        "peak_frozen_m": peak_frozen_m,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("replay", type=Path,
                        help="nuovo replay .jsonl oppure .zip multi-parte")
    parser.add_argument("--csv", type=Path,
                        help="CSV privato; default: accanto all'input")
    args = parser.parse_args(argv)
    rows, stats = analyze(read_records(args.replay))
    if not rows:
        parser.error(
            "Replay non analizzabile: servono eventi route_full e progress_debug "
            "dell'APK sperimentale aggiornato. I replay del 17/11 antecedenti "
            "alla patch contengono soltanto finestre BLE.")
    output = args.csv or args.replay.with_suffix(".progress.csv")
    if output.resolve() == args.replay.resolve():
        parser.error("Non sovrascrivere il replay originale")
    with output.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)
    print(f"Rotte complete: {stats['routes']}; "
          f"decisioni: {len(rows)}; "
          f"decisioni senza geometria: {stats['missing_route']}; "
          f"hold_offroute: {stats['held']}")
    print(f"Massima distanza GPS percorsa durante il progresso fermo "
          f"(indicatore esplorativo): {stats['peak_frozen_m']:.1f} m")
    print(f"CSV privato: {output}")
    print("ATTENZIONE: replay originale contiene coordinate GPS private. "
          "Nessun file è stato caricato online.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
