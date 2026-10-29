#!/usr/bin/env python3
# coding: utf-8
"""Generate offline low-resolution country previews using a local Natural Earth 1:110m Shapefile.

Requires geopandas. This is FOR MAP PREVIEW AND PIPELINE SMOKE TESTS ONLY.
No Ukrainian ADM1 regional data is inferred or fabricated from ADM0.
"""
import argparse
from pathlib import Path
from shapely.ops import unary_union
import geopandas as gpd
from build_geofences import (COUNTRIES, make_feature, save_json, wrap_feature,
                             area_footprint, get_feature_geom, polygons_only)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--shapefile", required=True, help="Local Natural Earth low resolution shapefile")
    p.add_argument("--output", default="preview_110m")
    a = p.parse_args()
    df = gpd.read_file(a.shapefile)
    iso_col = "iso_a3"
    if iso_col not in df:
        raise RuntimeError(f"Missing {iso_col} column in local shapefile")
    out = Path(a.output)
    all_feats = []
    metadata = {
        "boundaryID": "NaturalEarth_lowres_110m_preview_only",
        "boundaryYearRepresented": "reference dataset release unknown",
        "boundaryLicense": "Natural Earth public domain",
        "gjDownloadURL": "local Natural Earth lowres shapefile shipped with pyogrio tests",
    }
    for iso, (zh, en) in COUNTRIES.items():
        records = df.loc[df[iso_col] == iso]
        if len(records) != 1:
            raise ValueError(f"Could not locate exact country {iso}; found {len(records)}")
        geom = records.iloc[0].geometry
        if iso == "SOM":
            # Lowres 110m represents Somaliland as a separate feature; include
            # it in Somalia's preview administrative envelope, and disclose it.
            other = df.loc[df["name"] == "Somaliland"]
            if len(other) != 1:
                raise ValueError("Cannot find Somaliland for Somalia preview union")
            geom = unary_union([geom, other.iloc[0].geometry])
        feat = make_feature(f"country_{iso}", zh, en, "ADM0", geom, iso, metadata,
                            preview_only=True,
                            not_for_operational_geofencing=True,
                            scale="1:110,000,000",
                            geopolitical_note=("Somaliland merged for preview" if iso == "SOM" else "lowres default Natural Earth boundaries"))
        all_feats.append(feat)
        save_json(out / "countries" / f"{iso}.geojson", wrap_feature(feat))
        print(iso,zh, len(polygons_only(get_feature_geom(feat))))
    save_json(out / "all_countries_preview.geojson", {"type":"FeatureCollection", "features":all_feats})
    save_json(out / "preview_manifest.json", {
        "warning": "LOW RESOLUTION PREVIEW ONLY. DO NOT USE FOR ENFORCEMENT OR NEAR-BORDER ELECTRONIC FENCES.",
        "scale": "Natural Earth 1:110m",
        "features": [area_footprint(f) for f in all_feats],
        "country_count": 14, "special_region_count": 0,
        "regions_missing_reason": "ADM1 boundaries are not present in the offline reference shapefile. build_geofences.py downloads these from geoBoundaries when run with internet access.",
        "crs": "EPSG:4326",
    }, pretty=True)
    print("Generated 14 country previews, 0 disputed region features (not fabricated)")

if __name__ == '__main__':
    main()
