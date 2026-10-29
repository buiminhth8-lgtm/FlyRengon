#!/usr/bin/env python3
# coding: utf-8
"""Exact polygon containment for the generated geofence FeatureCollection.

Requires shapely>=2; returns *all* matching fence IDs (overlaps are preserved).
GeoJSON coordinates and CLI point arguments use [longitude, latitude].
"""
import argparse
import json
from pathlib import Path
from shapely.geometry import Point, shape
from shapely.prepared import prep


def query(dataset, lon, lat):
    if not (-180 <= lon <= 180) or not (-90 <= lat <= 90):
        raise ValueError("Invalid longitude / latitude")
    point = Point(lon, lat)
    matches = []
    for feature in dataset["features"]:
        polygon = shape(feature["geometry"])
        # covers() includes a point on the boundary, unlike contains().
        if prep(polygon).covers(point):
            matches.append({"id": feature["id"], **feature["properties"]})
    return matches


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file", default="output/all_geofences.geojson", help="GeoJSON FeatureCollection")
    parser.add_argument("--lon", type=float, required=True)
    parser.add_argument("--lat", type=float, required=True)
    args = parser.parse_args()
    data = json.loads(Path(args.file).read_text(encoding="utf-8"))
    print(json.dumps(query(data, args.lon, args.lat), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
