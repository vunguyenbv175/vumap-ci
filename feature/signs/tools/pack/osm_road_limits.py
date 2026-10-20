"""Deterministic host OSM road-evidence adapter (stdlib only, offline).

Turns a bounded OpenStreetMap JSON extract into a schema-v2 fixture that the
existing ``pack_build.py`` pipeline can package. The output rows are typed
``RoadLimit`` evidence for the Java/Android resolver; this adapter never
fabricates a direction, unit conversion or legal value.

Input shape (Overpass ``out tags geom;`` JSON, or an equivalent saved extract)::

    {"osm3s": {"timestamp_osm_base": "2026-10-20T08:10:48Z"},
     "elements": [
       {"type": "way", "id": 37731777,
        "tags": {"highway": "tertiary", "lanes": "2", "maxspeed": "50"},
        "geometry": [{"lat": 20.99, "lon": 105.85}, ...]}
     ]}

Only ``way`` elements are read; nodes/relations are ignored. The result is
deterministic: ways are processed in ascending id order, rows are sorted, and
no timestamp/locale/formatting input leaks into the document.

Parsing rules (deliberately narrow, all documented limits are intentional):

* ``maxspeed`` -> direction ``BOTH`` (applies regardless of travel direction).
* ``maxspeed:forward`` -> ``FORWARD``; ``maxspeed:backward`` -> ``BACKWARD``
  (OSM direction is relative to the way geometry, which is also the edge
  heading written into the pack).
* ``oneway=-1`` means traffic runs against the way geometry, so directional
  tags cannot be mapped without guessing: they are emitted with direction
  ``UNKNOWN`` and the resolver abstains. Plain ``maxspeed`` stays ``BOTH``.
* ``maxspeed:conditional`` / ``*:conditional`` -> ``CONDITIONAL_UNSUPPORTED``.
* ``maxspeed:lanes`` / ``maxspeed:forward:lanes`` / ... -> ``LANE_UNSUPPORTED``.
* vehicle-class keys (hgv, bus, truck, motorcycle, psv, goods, ...) ->
  ``VEHICLE_UNSUPPORTED``. Unsupported rows always carry ``valueKmh = null``.
* Any other ``maxspeed:*`` key is skipped and reported; it is never mapped to a
  guessed applicability.
* A supported value must be an integer 5..120 with no unit or an explicit
  ``km/h``/``kmh``/``kph`` suffix. ``mph``, knots, symbolic values
  (walk/none/signals/variable/...), ranges, lists and conditional strings are
  skipped and reported -- never converted or clamped to km/h.

Confidence is a fixed source-quality prior for an explicit tag, not a field
verification score. Importing or committing a fixture never certifies that a
limit is legal or surveyed.

Closed ways and zero-length/repeated-endpoint geometry leave ``headingDeg``
null: no bearing is invented for direction verification.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pack_format as fmt  # noqa: E402

# Bounded source prior for an explicit OSM maxspeed tag (not field truth).
DEFAULT_CONFIDENCE = 0.8

DIRECTION_BY_KEY = {
    "maxspeed": "BOTH",
    "maxspeed:forward": "FORWARD",
    "maxspeed:backward": "BACKWARD",
}

VEHICLE_CLASSES = frozenset(
    {
        "hgv",
        "bus",
        "truck",
        "motorcycle",
        "moped",
        "psv",
        "goods",
        "trailer",
        "agricultural",
        "emergency",
        "delivery",
        "taxi",
        "car",
        "van",
        "minibus",
        "coach",
        "hazmat",
    }
)

_KMH_VALUE = re.compile(r"^\s*(\d{1,3})\s*(?:km/h|kmh|kph)?\s*$", re.IGNORECASE)
_ANY_UNIT = re.compile(r"^\s*\d{1,3}(?:\.\d+)?\s*([a-zA-Z/]+)\s*$")
# Endpoints closer than ~0.1 mm have no meaningful great-circle bearing.
_MIN_BEARING_BASELINE_DEG = 1e-9


def initial_bearing_deg(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Initial great-circle bearing from point 1 to point 2, 0..360 degrees."""
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    d_lambda = math.radians(lon2 - lon1)
    y = math.sin(d_lambda) * math.cos(phi2)
    x = math.cos(phi1) * math.sin(phi2) - math.sin(phi1) * math.cos(phi2) * math.cos(d_lambda)
    bearing = math.degrees(math.atan2(y, x))
    return round((bearing + 360.0) % 360.0, 3)


def _as_lanes(tags: dict) -> int | None:
    raw = tags.get("lanes")
    if raw is None:
        return None
    text = str(raw).strip()
    if not text.isdigit():
        return None
    lanes = int(text)
    # Bound the accepted range; unexpected values are dropped, never guessed.
    if lanes < 1 or lanes > 12:
        return None
    return lanes


def _carriageway(tags: dict, lanes: int | None) -> str:
    """Conservative TT38 carriageway bucket; UNKNOWN whenever evidence is weak."""
    oneway = str(tags.get("oneway", "")).strip().lower()
    if oneway in ("yes", "true", "1", "-1"):
        # One-way traffic (with or against the way geometry). A one-way road
        # with unknown lane count cannot be classified safely.
        if lanes is None:
            return "UNKNOWN"
        if lanes >= 2:
            return "DUAL_DIVIDED_OR_ONEWAY_2PLUS"
        return "TWO_WAY_OR_ONEWAY_1LANE"
    if oneway in ("no", "false", "0"):
        return "TWO_WAY_OR_ONEWAY_1LANE"
    # Missing oneway tag: never assume a direction or a carriageway class.
    return "UNKNOWN"


def classify_tag(tag_key: str) -> tuple[str, str | None]:
    """Return (direction, unsupported applicability or None) for a maxspeed key.

    ``direction`` is ``BOTH``/``FORWARD``/``BACKWARD``/``UNKNOWN``; a non-null
    applicability means the tag must abstain (value stays NULL).
    """
    if tag_key in DIRECTION_BY_KEY:
        return DIRECTION_BY_KEY[tag_key], None

    parts = tag_key.split(":")
    if parts[0] != "maxspeed" or len(parts) < 2:
        return "UNKNOWN", None

    if "forward" in parts:
        direction = "FORWARD"
    elif "backward" in parts:
        direction = "BACKWARD"
    else:
        direction = "BOTH"

    if "conditional" in parts:
        return direction, "CONDITIONAL_UNSUPPORTED"
    if "lanes" in parts:
        return direction, "LANE_UNSUPPORTED"
    qualifiers = set(parts[1:])
    if qualifiers & VEHICLE_CLASSES:
        return direction, "VEHICLE_UNSUPPORTED"
    # Unknown qualifier (advisory, type, variable, ...): skip, never mislabel.
    return direction, None


def parse_speed_kmh(raw: str) -> tuple[int | None, str | None]:
    """Parse an explicit OSM speed value into km/h, or (None, skip_reason).

    Only plain integers in km/h (implicit or explicit suffix) are accepted.
    Nothing is converted from another unit and nothing is clamped.
    """
    text = str(raw).strip()
    if not text:
        return None, "MALFORMED_VALUE"
    match = _KMH_VALUE.match(text)
    if match:
        value = int(match.group(1))
        if value < fmt.ROAD_LIMIT_MIN_KMH or value > fmt.ROAD_LIMIT_MAX_KMH:
            return None, "OUT_OF_RANGE"
        return value, None
    if text.isdigit():
        return None, "OUT_OF_RANGE"
    # Report the unit family without ever converting it.
    unit_match = _ANY_UNIT.match(text)
    if unit_match:
        unit = unit_match.group(1).lower()
        if unit in ("km/h", "kmh", "kph"):
            return None, "MALFORMED_VALUE"
        return None, "UNSUPPORTED_UNIT"
    if ";" in text or "@" in text or "|" in text or "-" in text:
        return None, "UNSUPPORTED_VALUE_SYNTAX"
    return None, "SYMBOLIC_VALUE"


def _edge_id(way_id: int | str) -> str:
    return f"OSM_W{way_id}"


def _heading(geometry) -> float | None:
    if not isinstance(geometry, list) or len(geometry) < 2:
        return None
    try:
        first = geometry[0]
        last = geometry[-1]
        lat1 = float(first["lat"])
        lon1 = float(first["lon"])
        lat2 = float(last["lat"])
        lon2 = float(last["lon"])
    except (KeyError, TypeError, ValueError):
        return None
    # A closed way (roundabout, loop) has coincident endpoints, and a
    # zero-length geometry repeats them. There is no single travel bearing in
    # either case; atan2(0, 0) would invent 0 degrees and make directional
    # evidence look verified. Leave the heading unknown instead.
    if (
        abs(lat1 - lat2) <= _MIN_BEARING_BASELINE_DEG
        and abs(lon1 - lon2) <= _MIN_BEARING_BASELINE_DEG
    ):
        return None
    return initial_bearing_deg(lat1, lon1, lat2, lon2)


def adapt(
    document: dict,
    *,
    source: str,
    license: str,
    version: str,
    attribution: str,
    confidence: float = DEFAULT_CONFIDENCE,
    synthetic: bool = False,
) -> tuple[dict, dict]:
    """Convert an OSM JSON extract into a schema-v2 fixture plus a report."""
    for key, value in (("source", source), ("license", license), ("version", version)):
        if not value:
            raise ValueError(f"provenance {key} must not be blank")
    if not (0.0 <= confidence <= 1.0):
        raise ValueError("confidence must be within 0..1")

    elements = document.get("elements")
    if not isinstance(elements, list):
        raise ValueError("OSM extract must contain an elements list")

    ways = []
    for element in elements:
        if not isinstance(element, dict) or element.get("type") != "way":
            continue
        tags = element.get("tags") or {}
        if not isinstance(tags, dict):
            continue
        if not any(str(key).startswith("maxspeed") for key in tags):
            continue
        ways.append(element)

    # Deterministic order and duplicate-way tolerance (first occurrence wins).
    ways.sort(key=lambda way: str(way.get("id", "")))
    seen_ways: set[str] = set()
    edges: list[dict] = []
    road_limits: list[dict] = []
    skipped: list[dict] = []

    for way in ways:
        way_id = way.get("id")
        if way_id is None:
            continue
        way_key = str(way_id)
        if way_key in seen_ways:
            continue
        seen_ways.add(way_key)

        tags = way.get("tags") or {}
        lanes = _as_lanes(tags)
        heading = _heading(way.get("geometry"))
        oneway = str(tags.get("oneway", "")).strip().lower()
        direction_flip = oneway == "-1"

        rows_for_way: list[dict] = []
        for tag_key in sorted(tags):
            if not str(tag_key).startswith("maxspeed"):
                continue
            raw_value = tags[tag_key]
            direction, unsupported = classify_tag(tag_key)
            if unsupported is None and tag_key not in DIRECTION_BY_KEY:
                skipped.append(
                    {
                        "wayId": way_key,
                        "tag": tag_key,
                        "value": str(raw_value),
                        "reason": "UNSUPPORTED_TAG",
                    }
                )
                continue

            if unsupported is None:
                value, reason = parse_speed_kmh(raw_value)
                if reason is not None:
                    skipped.append(
                        {
                            "wayId": way_key,
                            "tag": tag_key,
                            "value": str(raw_value),
                            "reason": reason,
                        }
                    )
                    continue
            else:
                # Unsupported applicability never carries a number.
                value = None

            if direction_flip and direction in ("FORWARD", "BACKWARD"):
                # oneway=-1: OSM forward/backward is relative to the way
                # geometry, which is opposite to traffic. Abstain, never guess.
                direction = "UNKNOWN"

            rows_for_way.append(
                {
                    "id": f"{_edge_id(way_key)}:{tag_key}:{direction}",
                    "edgeId": _edge_id(way_key),
                    "direction": direction,
                    "valueKmh": value,
                    "applicability": unsupported or "SUPPORTED",
                    "confidence": round(confidence, 3),
                    "provenance": f"OSM way {way_key} tag {tag_key} | {source} {version}",
                }
            )

        if not rows_for_way:
            continue

        edges.append(
            {
                "id": _edge_id(way_key),
                "carriageway": _carriageway(tags, lanes),
                "level": 0,
                "roadClass": tags.get("highway"),
                "lanes": lanes,
                "headingDeg": heading,
            }
        )
        road_limits.extend(rows_for_way)

    road_limits.sort(key=lambda row: row["id"])
    edges.sort(key=lambda edge: edge["id"])
    skipped.sort(key=lambda row: (row["wayId"], row["tag"], row["reason"]))

    fixture = {
        "schemaVersion": fmt.SCHEMA_VERSION_ROAD_LIMITS,
        "provenance": {
            "source": source,
            "license": license,
            "version": version,
            "attribution": attribution,
            "synthetic": bool(synthetic),
            "note": (
                "Host OSM road-evidence extract. Explicit maxspeed tags only; "
                "unsupported tags abstain. Not field-surveyed and not a legal "
                "speed verification."
            ),
        },
        "edges": edges,
        "points": [],
        "roadLimits": road_limits,
    }
    report = {
        "ways": len(seen_ways),
        "edges": len(edges),
        "roadLimits": len(road_limits),
        "supported": sum(1 for row in road_limits if row["applicability"] == "SUPPORTED"),
        "unsupported": sum(1 for row in road_limits if row["applicability"] != "SUPPORTED"),
        "skipped": skipped,
        "osmBase": ((document.get("osm3s") or {}).get("timestamp_osm_base")),
        "attribution": attribution,
        "license": license,
        "synthetic": bool(synthetic),
    }
    return fixture, report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="OSM JSON extract")
    parser.add_argument("--out", required=True, help="fixture JSON output path")
    parser.add_argument("--source", required=True)
    parser.add_argument("--license", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--attribution", required=True)
    parser.add_argument("--confidence", type=float, default=DEFAULT_CONFIDENCE)
    parser.add_argument(
        "--synthetic",
        action="store_true",
        help="mark the output as a synthetic fixture (default: real extract)",
    )
    args = parser.parse_args(argv)

    with open(args.input, "r", encoding="utf-8") as handle:
        document = json.load(handle)

    fixture, report = adapt(
        document,
        source=args.source,
        license=args.license,
        version=args.version,
        attribution=args.attribution,
        confidence=args.confidence,
        synthetic=args.synthetic,
    )

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        json.dumps(fixture, indent=2, sort_keys=True, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(f"fixture={out}")
    print(
        "ways={ways} edges={edges} roadLimits={roadLimits} "
        "supported={supported} unsupported={unsupported}".format(**report)
    )
    for row in report["skipped"]:
        print(f"SKIPPED way={row['wayId']} tag={row['tag']} reason={row['reason']}")
    print("RESULT: OSM ROAD-LIMIT ADAPTER OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
