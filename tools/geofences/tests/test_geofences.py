from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from shapely.geometry import Polygon, Point
from build_geofences import split_antimeridian, region_matches, COUNTRIES, validated_geometry


def test_country_count():
    assert len(COUNTRIES) == 14
    assert len(set(COUNTRIES)) == 14


def test_antimeridian_safe():
    raw = Polygon([(170, 51), (-170, 51), (-170, 55), (170, 55), (170, 51)])
    g = split_antimeridian(raw)
    assert g.is_valid
    assert g.covers(Point(179, 53))
    assert g.covers(Point(-179, 53))
    assert not g.covers(Point(0, 53))
    assert len(g.geoms) == 2


def test_ukr_subdivisions_labels():
    fixtures = [
        {"type": "Feature", "properties": {"shapeName": n},
         "geometry": {"type": "Polygon", "coordinates": [[[30, 45], [31, 45], [31, 46], [30, 46], [30, 45]]]}}
        for n in ["Avtonomna Respublika Krym", "Sevastopol'", "Donets'ka", "Luhans'ka"]
    ]
    groups = region_matches(fixtures)
    assert all(len(groups[k]) == 1 for k in ["crimea", "sevastopol", "donetsk", "luhansk"])


def test_validity():
    p = Polygon([(0,0), (1,0), (1,1), (0,1), (0,0)])
    assert validated_geometry(p).is_valid
