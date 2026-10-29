#!/usr/bin/env python3
# coding: utf-8
"""Build 14 national + 3 separately identifiable ADM1 geofences from geoBoundaries.

Full polygons are downloaded when you run this script on a networked computer.
The low-resolution preview shipped with this toolkit is NOT production data.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import unicodedata
from pathlib import Path

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry
from shapely import affinity
from shapely.geometry import box, mapping, shape, Polygon, MultiPolygon, Point
from shapely.ops import unary_union
from shapely.validation import explain_validity

COUNTRIES = {
    "PRK": ("朝鲜", "North Korea"),
    "SOM": ("索马里", "Somalia"),
    "CAF": ("中非共和国", "Central African Republic"),
    "SSD": ("南苏丹", "South Sudan"),
    "IRN": ("伊朗", "Iran"),
    "SYR": ("叙利亚", "Syria"),
    "YEM": ("也门", "Yemen"),
    "LBY": ("利比亚", "Libya"),
    "RUS": ("俄罗斯", "Russia"),
    "USA": ("美国", "United States"),
    "ISR": ("以色列", "Israel"),
    "TUR": ("土耳其", "Turkey"),
    "JPN": ("日本", "Japan"),
    "UKR": ("乌克兰", "Ukraine"),
}
REGIONS = {
    "crimea": ("克里米亚地区（含塞瓦斯托波尔）", "Crimea incl. Sevastopol"),
    "donetsk": ("顿涅茨克州", "Donetsk Oblast"),
    "luhansk": ("卢甘斯克州", "Luhansk Oblast"),
}
# These are 2017/administrative regional envelopes, NOT dynamic military front lines.
NAME_REGEX = {
    "crimea": r"crimea|krym|qirim|крим|крым|кримськ|крымск",
    "sevastopol": r"sevast|севаст",
    "donetsk": r"donetsk|donets|donjec|донець|донец",
    "luhansk": r"luhansk|luhans|lugansk|lugan|луган",
}
API_BASE = "https://www.geoboundaries.org/api/current/gbOpen"


def session_with_retries() -> requests.Session:
    session = requests.Session()
    retry = Retry(total=4, backoff_factor=1.3, status_forcelist=[429, 500, 502, 503, 504])
    session.mount("https://", HTTPAdapter(max_retries=retry))
    session.headers.update({"User-Agent": "GeoFenceBoundaryBuilder/1.0 (open-data research)",
                            "Accept": "application/json,*/*"})
    return session


def json_from_url(session: requests.Session, url: str):
    resp = session.get(url, timeout=(15, 180))
    resp.raise_for_status()
    # GitHub raw may expose the Git-LFS *pointer*, not the GeoJSON blob.
    if resp.content.startswith(b"version https://git-lfs.github.com/spec/v1"):
        alternative = github_lfs_media_url(url)
        if not alternative:
            raise RuntimeError(f"Received Git LFS pointer instead of GeoJSON: {url}")
        resp = session.get(alternative, timeout=(15, 240))
        resp.raise_for_status()
    try:
        return resp.json()
    except ValueError as exc:
        raise ValueError(f"Response is not valid JSON: {url}; prefix={resp.text[:90]!r}") from exc


def github_lfs_media_url(url: str) -> str | None:
    # github.com/org/repo/raw/<rev>/<path>
    m = re.match(r"https?://github\.com/([^/]+)/([^/]+)/raw/([^/]+)/(.+)", url)
    if m:
        return f"https://media.githubusercontent.com/media/{m.group(1)}/{m.group(2)}/{m.group(3)}/{m.group(4)}"
    m = re.match(r"https?://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.+)", url)
    if m:
        return f"https://media.githubusercontent.com/media/{m.group(1)}/{m.group(2)}/{m.group(3)}/{m.group(4)}"
    return None


def load_layer(session, cache: Path, iso3: str, level: str, offline=False):
    cache.mkdir(parents=True, exist_ok=True)
    meta_path = cache / f"{iso3}_{level}_metadata.json"
    geo_path = cache / f"{iso3}_{level}.geojson"

    if meta_path.is_file():
        metadata = json.loads(meta_path.read_text(encoding="utf-8"))
    elif offline and geo_path.is_file():
        metadata = {"boundaryID": "local:unknown", "boundaryISO": iso3,
                    "boundaryType": level, "boundaryYearRepresented": "unknown",
                    "boundaryLicense": "unknown (check the file's origin)",
                    "gjDownloadURL": "local manually supplied GeoJSON", "boundarySource": "local"}
    elif offline:
        raise FileNotFoundError(f"Missing offline metadata/input for {iso3} {level}: {geo_path}")
    else:
        url = f"{API_BASE}/{iso3}/{level}/"
        metadata = json_from_url(session, url)
        if not isinstance(metadata, dict) or not metadata.get("gjDownloadURL"):
            raise RuntimeError(f"No download URL: {url}: {metadata}")
        meta_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")

    if geo_path.is_file():
        data = json.loads(geo_path.read_text(encoding="utf-8"))
    elif offline:
        raise FileNotFoundError(f"Missing cached GeoJSON for {iso3} {level}: {geo_path}")
    else:
        url = metadata["gjDownloadURL"]
        print(f"Downloading {iso3} {level}: {url}", flush=True)
        data = json_from_url(session, url)
        geo_path.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    if not isinstance(data, dict) or data.get("type") not in ("FeatureCollection", "Feature", "Polygon", "MultiPolygon"):
        raise ValueError(f"Invalid/unsupported GeoJSON for {iso3} {level}")
    return metadata, data


def extract_features(data):
    if data["type"] == "FeatureCollection":
        return data.get("features", [])
    if data["type"] == "Feature":
        return [data]
    return [{"type": "Feature", "properties": {}, "geometry": data}]


def polygons_only(geom):
    if geom.is_empty:
        return []
    if geom.geom_type == "Polygon":
        return [geom]
    if geom.geom_type in ("MultiPolygon", "GeometryCollection"):
        output = []
        for g in geom.geoms:
            output.extend(polygons_only(g))
        return output
    return []


def validated_geometry(geom):
    if geom.is_empty:
        raise ValueError("Empty geometry not suitable as geofence")
    if not geom.is_valid:
        from shapely import make_valid
        geom = make_valid(geom)
    polys = polygons_only(geom)
    if not polys:
        raise ValueError(f"No polygon after repair; geom_type={geom.geom_type}")
    geom = polys[0] if len(polys) == 1 else MultiPolygon(polys)
    if not geom.is_valid:
        raise ValueError(f"Invalid geometry: {explain_validity(geom)}")
    return geom


def merge_geometries(features):
    geoms = [validated_geometry(shape(f["geometry"])) for f in features
             if f.get("geometry") is not None]
    if not geoms:
        raise ValueError("No polygon features")
    return validated_geometry(geoms[0] if len(geoms) == 1 else unary_union(geoms))


def unwrap_ring(ring):
    result = []
    prev = None
    for coord in ring:
        x = float(coord[0])
        if prev is not None:
            while x - prev > 180:
                x -= 360
            while x - prev < -180:
                x += 360
        result.append((x, float(coord[1])))
        prev = x
    if len(result) > 1 and ring[0][0] == ring[-1][0] and ring[0][1] == ring[-1][1]:
        result[-1] = result[0]
    return result


def ring_has_antimeridian_jump(coords):
    coords = list(coords)
    return any(abs(coords[i][0] - coords[i - 1][0]) > 180 for i in range(1, len(coords)))


def split_antimeridian(geometry):
    """Split short longitude-wrap crossings into valid [-180,180] MultiPolygons.

    Avoid a naive bounding-box test on countries that span the dateline.
    Source geometries that already split correctly are left unchanged.
    """
    polygons = []
    for p in polygons_only(geometry):
        if not (ring_has_antimeridian_jump(p.exterior.coords) or any(
            ring_has_antimeridian_jump(h.coords) for h in p.interiors
        )):
            polygons.append(p)
            continue
        outer = unwrap_ring(p.exterior.coords)
        center = (min(x for x, _ in outer) + max(x for x, _ in outer)) / 2
        inner = []
        for hole in p.interiors:
            h = unwrap_ring(hole.coords)
            hcenter = (min(x for x, _ in h) + max(x for x, _ in h)) / 2
            offset = round((center - hcenter) / 360) * 360
            inner.append([(x + offset, y) for x, y in h])
        unwrapped = validated_geometry(Polygon(outer, inner))
        for piece in polygons_only(unwrapped):
            for shift in range(-2, 3):
                viewport = box(-180 + shift * 360, -90, 180 + shift * 360, 90)
                if not piece.intersects(viewport):
                    continue
                clipped = piece.intersection(viewport)
                if clipped.is_empty or clipped.area < 1e-13:
                    continue
                translated = affinity.translate(clipped, xoff=-360 * shift)
                polygons.extend(polygons_only(validated_geometry(translated)))
    if not polygons:
        raise ValueError("No polygons after antimeridian split")
    return validated_geometry(polygons[0] if len(polygons) == 1 else MultiPolygon(polygons))


def normalize_name(name):
    name = unicodedata.normalize("NFKD", str(name)).casefold()
    return "".join(c for c in name if not unicodedata.combining(c))


def region_matches(features):
    regions = {"crimea": [], "sevastopol": [], "donetsk": [], "luhansk": []}
    seen_names = []
    for feat in features:
        props = feat.get("properties") or {}
        candidates = [props.get(k) for k in ("shapeName", "name", "NAME_1", "NAME", "name_en", "admin1Name")]
        candidates = [v for v in candidates if isinstance(v, str) and v.strip()]
        name = candidates[0] if candidates else ""
        seen_names.append(name or str(props)[:100])
        for region_key, pattern in NAME_REGEX.items():
            if any(re.search(pattern, normalize_name(v)) for v in candidates):
                regions[region_key].append(feat)
    missing = [key for key, feats in regions.items() if not feats]
    if missing:
        raise ValueError("Ukraine ADM1 required areas missing: " + ", ".join(missing)
                         + "\nAvailable names: " + "; ".join(seen_names)
                         + "\nDo NOT substitute approximate hand-drawn boxes")
    return regions


def make_feature(identifier, cn, en, level, geometry, source_iso, metadata, **extras):
    geom = split_antimeridian(validated_geometry(geometry))
    feature = {
        "type": "Feature",
        "id": identifier,
        "properties": {
            "geofence_id": identifier,
            "name_zh": cn,
            "name_en": en,
            "geojson_crs": "EPSG:4326",
            "boundary_kind": "country_admin0" if level == "ADM0" else "administrative_region_admin1",
            "source_iso3": source_iso,
            "source_boundary_id": metadata.get("boundaryID", "unknown"),
            "source_year": metadata.get("boundaryYearRepresented", "unknown"),
            "source_license": metadata.get("boundaryLicense", "unknown"),
            "source_url": metadata.get("gjDownloadURL", "unknown"),
            **extras,
        },
        "geometry": mapping(geom),
    }
    return feature


def save_json(path: Path, obj, pretty=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False,
                               indent=2 if pretty else None,
                               separators=None if pretty else (",", ":")), encoding="utf-8")


def wrap_feature(f):
    return {"type": "FeatureCollection", "features": [f]}


def get_feature_geom(f):
    return shape(f["geometry"])


def area_footprint(f):
    g = get_feature_geom(f)
    return {"id": f["id"], "name_zh": f["properties"]["name_zh"],
            "type": g.geom_type, "bbox": [round(v, 6) for v in g.bounds],
            "polygons": len(polygons_only(g)), "source_year": f["properties"]["source_year"],
            "source_license": f["properties"]["source_license"]}


def build(output: Path, cache: Path, offline=False):
    session = session_with_retries()
    all_features = []
    metadata_index = {}
    for iso, (cn, en) in COUNTRIES.items():
        meta, data = load_layer(session, cache, iso, "ADM0", offline)
        metadata_index[f"{iso}_ADM0"] = meta
        geometry = merge_geometries(extract_features(data))
        feat = make_feature(f"country_{iso}", cn, en, "ADM0", geometry, iso, meta,
                            geo_policy="source_boundary_unmodified",
                            temporal_type="administrative_boundary_not_live_control_line")
        all_features.append(feat)
        save_json(output / "countries" / f"{iso}.geojson", wrap_feature(feat))
        print(f"[COUNTRY] {iso:<4s} {cn}: polygons={len(polygons_only(get_feature_geom(feat)))}", flush=True)

    meta, data = load_layer(session, cache, "UKR", "ADM1", offline)
    metadata_index["UKR_ADM1"] = meta
    region_groups = region_matches(extract_features(data))
    region_sets = {
        "crimea": region_groups["crimea"] + region_groups["sevastopol"],
        "donetsk": region_groups["donetsk"],
        "luhansk": region_groups["luhansk"],
    }
    for name, subset in region_sets.items():
        cn, en = REGIONS[name]
        feat = make_feature(f"region_{name}", cn, en, "ADM1", merge_geometries(subset),
                            "UKR", meta,
                            region_scope="full_administrative_area",
                            territorial_status="disputed_or_conflict_affected",
                            not_actual_control_line=True,
                            included_names=[(f.get("properties") or {}).get("shapeName") for f in subset])
        all_features.append(feat)
        save_json(output / "regions" / f"{name}.geojson", wrap_feature(feat))
        print(f"[REGION]  {name}: {len(subset)} source ADM1 unit(s)", flush=True)

    if len(all_features) != 17 or len({f["id"] for f in all_features}) != 17:
        raise RuntimeError("Expected exactly 14 unique country + 3 region features")
    # Smoke checks use inland reference points; they do NOT certify border accuracy.
    inland_reference_points = {
        "country_PRK": (125.7625, 39.0392),      # Pyongyang
        "country_SOM": (45.3182, 2.0469),        # Mogadishu
        "country_CAF": (18.5582, 4.3947),        # Bangui
        "country_SSD": (31.5713, 4.8594),        # Juba
        "country_IRN": (51.3890, 35.6892),      # Tehran
        "country_SYR": (36.2765, 33.5138),      # Damascus
        "country_YEM": (44.1910, 15.3694),      # Sana'a
        "country_LBY": (13.1913, 32.8872),      # Tripoli
        "country_RUS": (37.6173, 55.7558),      # Moscow
        "country_USA": (-77.0369, 38.9072),     # Washington, DC
        "country_ISR": (34.7818, 32.0853),      # Tel Aviv
        "country_TUR": (32.8597, 39.9334),      # Ankara
        "country_JPN": (139.6503, 35.6762),     # Tokyo
        "country_UKR": (30.5234, 50.4501),      # Kyiv
        "region_crimea": (34.1024, 44.9521),   # Simferopol
        "region_donetsk": (37.8029, 48.0159),  # Donetsk
        "region_luhansk": (39.3070, 48.5740),  # Luhansk
    }
    problems = []
    test_results = []
    for feature in all_features:
        lon, lat = inland_reference_points[feature["id"]]
        ok = get_feature_geom(feature).covers(Point(lon, lat))
        test_results.append({"geofence_id": feature["id"], "test_lon_lat": [lon, lat], "covers": ok})
        if not ok:
            problems.append(feature["id"])
    # Sevastopol is separately identified in ADM1; explicitly verify its inclusion.
    crimea_feature = next(f for f in all_features if f["id"] == "region_crimea")
    sev_ok = get_feature_geom(crimea_feature).covers(Point(33.5254, 44.6167))
    test_results.append({"geofence_id": "region_crimea", "test_lon_lat": [33.5254, 44.6167], "covers": sev_ok})
    if not sev_ok:
        problems.append("region_crimea:Sevastopol")
    save_json(output / "metadata" / "reference_point_checks.json", test_results, pretty=True)
    if problems:
        raise RuntimeError("Reference-point checks failed for " + ",".join(problems) +
                           ". Review source topology and territorial boundary conventions.")
    save_json(output / "all_geofences.geojson", {"type": "FeatureCollection", "features": all_features})
    save_json(output / "metadata" / "source_metadata.json", metadata_index, pretty=True)
    manifest = {
        "dataset": "country_and_ukraine_admin1_geofences",
        "feature_count": len(all_features), "country_count": 14, "region_count": 3,
        "crs": "EPSG:4326", "coordinate_order": "[longitude, latitude]",
        "geopolitical_boundary_notice": "Original source ADM0 boundaries are retained. Special UKR ADM1 areas are separately represented and may overlap country geofences. No dynamic front lines are encoded.",
        "features": [area_footprint(f) for f in all_features],
    }
    save_json(output / "manifest.json", manifest, pretty=True)
    print(f"\nSUCCESS: 14 country boundaries + 3 regional boundaries -> {output.resolve()}")
    print("Do not treat administrative boundaries as live conflict control boundaries.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="output", help="Output directory for full GeoJSON files")
    parser.add_argument("--cache", default="cache", help="Cache directory (metadata and downloaded GeoJSON)")
    parser.add_argument("--offline", action="store_true", help="Use only cached layers. Supply all 15 .geojson inputs first.")
    args = parser.parse_args()
    try:
        build(Path(args.output), Path(args.cache), offline=args.offline)
    except Exception as exc:
        print(f"BUILD FAILED: {exc}", file=sys.stderr)
        print("Check network / source names. Incomplete output must NOT be used as a complete fence set.", file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
