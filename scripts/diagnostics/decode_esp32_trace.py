#!/usr/bin/env python3
"""Decode the raw 256KiB ESP32 MotoGPS diagnostic partition into CSV."""
import argparse
import csv
import struct
from pathlib import Path

STRUCT = struct.Struct("<IIIHH5iI24s")
LABELS = {1:"BOOT",2:"LINK",3:"NAV_SNAPSHOT",4:"MAP_SCENE",5:"ESP_RENDER"}

def decode(data):
    result = []
    for offset in range(0, len(data) - STRUCT.size + 1, STRUCT.size):
        packet = data[offset:offset + STRUCT.size]
        magic, seq, uptime, event, version, a,b,c,d,e,checksum,_ = STRUCT.unpack(packet)
        if magic != 0x4D4F544F or version != 1:
            continue
        h = 2166136261
        for byte in packet[:36]:
            h = ((h ^ byte) * 16777619) & 0xffffffff
        if h == checksum:
            result.append((seq,uptime,LABELS.get(event,str(event)),a,b,c,d,e))
    return sorted(result)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("binary", type=Path)
    args = parser.parse_args()
    rows = decode(args.binary.read_bytes())
    output = args.binary.with_suffix(".csv")
    with output.open("w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["sequence","esp_uptime_ms","event","a","b","c","d","e"])
        writer.writerows(rows)
    print(f"{len(rows)} records saved to {output}")
    print("NAV_SNAPSHOT: id, turn_m, phone_heading_cdeg, speed_deci_kph, flags_and_maneuver")
    print("MAP_SCENE: revision, roads, points, buildings")
    print("ESP_RENDER: maneuver_enum, turn_m, route_points, road_polylines, zoom_active")

if __name__ == "__main__":
    main()
