#!/usr/bin/env python3
"""Read-only OSM enforcement inventory; counts objects, not verified devices."""
import argparse
from collections import Counter
import json
from pathlib import Path

import osmium


class Inventory(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.counts = Counter()
        self.tags = {}
        self.roles = Counter()
        self.members = Counter()
        self.cameras = set()
        self.devices = set()
        self.locations = Counter()

    def inspect(self, kind, obj):
        tags = obj.tags
        camera = tags.get("highway") == "speed_camera"
        enforcement = tags.get("enforcement")
        relation = kind == "relation" and tags.get("type") == "enforcement"
        if not (camera or enforcement or relation):
            return
        group = kind + ":" + ("speed_camera" if camera else "enforcement:" + (enforcement or "unspecified"))
        self.counts[group] += 1
        histogram = self.tags.setdefault(group, Counter())
        for key in ("maxspeed", "direction", "camera:direction", "enforcement",
                    "maxspeed:forward", "maxspeed:backward", "maxspeed:conditional",
                    "traffic_sign", "check_date", "survey:date"):
            value = tags.get(key)
            histogram[key + "=" + (value if value else "(missing)")] += 1
        if kind == "node" and camera:
            self.cameras.add(obj.id)
            if obj.location.valid():
                self.locations[(round(obj.location.lat, 6), round(obj.location.lon, 6))] += 1
        if kind == "relation":
            for member in obj.members:
                role = member.role or "(empty)"
                self.roles[group + ":" + member.type + ":" + role] += 1
                self.members[group + ":" + role] += 1
                if member.type == "n" and role == "device":
                    self.devices.add(member.ref)

    def node(self, obj):
        self.inspect("node", obj)

    def way(self, obj):
        self.inspect("way", obj)

    def relation(self, obj):
        self.inspect("relation", obj)

    def report(self):
        return {
            "object_counts": dict(self.counts),
            "tag_histograms": {key: dict(value) for key, value in self.tags.items()},
            "relation_member_roles": dict(self.roles),
            "unique_device_node_refs": len(self.devices),
            "device_refs_also_tagged_speed_camera": len(self.devices & self.cameras),
            "camera_nodes_sharing_rounded_coordinates": sum(
                count for count in self.locations.values() if count > 1),
            "limitations": [
                "Counts are OSM objects, not confirmed active devices.",
                "Shared coordinates and member references are duplicate hints only.",
                "No road association, direction inference or relation geometry validation."
            ],
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pbf", type=Path)
    args = parser.parse_args()
    inventory = Inventory()
    inventory.apply_file(str(args.pbf), locations=False)
    print(json.dumps(inventory.report(), indent=2, sort_keys=True, ensure_ascii=False))


if __name__ == "__main__":
    main()
