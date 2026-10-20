"""VUPACK format — constants, SQLite schema DDL and canonical content digest.

Host-side, stdlib only. The on-disk ``.vupack`` is a plain SQLite database;
this module defines the exact schema and the byte-level reproducibility
contract used by build/validate/install.

Schema versions
---------------
* **v1** (``SCHEMA_VERSION``): ``pack_manifest``, ``edges``, ``points``.
  Sign/zone evidence only. Still produced for fixtures without road evidence,
  so existing packs and the committed P1 asset stay byte-identical.
* **v2** (``SCHEMA_VERSION_ROAD_LIMITS``): v1 tables **plus** an optional
  ``road_limits`` table that carries typed road evidence (OSM
  ``maxspeed``/``maxspeed:forward``/``maxspeed:backward``) for the existing
  Java ``RoadLimit`` contract. Additive and backward-compatible: a v1 reader
  keeps accepting v1 packs; only packs that actually carry road evidence are
  written as v2.

Canonical content digest (reproducible across SQLite/library versions):
    sha256( json.dumps({
        "edges":   [ edge rows sorted by edge_id   ],
        "points":  [ point rows sorted by point_id ],
        "manifest": {source, license, version, synthetic},
        # v2 only, present only when road evidence is supplied:
        "roadLimits": [ road limit rows sorted by road_limit_id ],
    }, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8") )

The v1 document is unchanged byte-for-byte, so old packs keep their digest.

Future Android consumption: this maps 1:1 onto a Room database: tables
``pack_manifest``, ``edges``, ``points`` (+ ``road_limits`` from v2);
``PRAGMA user_version`` carries the schema version; ``points.edge_id`` and
``road_limits.edge_id`` are foreign keys into ``edges``.
"""

from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path

FORMAT = "vupack"
FORMAT_VERSION = 1
SCHEMA_VERSION = 1
# Additive schema: v1 tables plus an optional road_limits table.
SCHEMA_VERSION_ROAD_LIMITS = 2
SUPPORTED_SCHEMA_VERSIONS = (SCHEMA_VERSION, SCHEMA_VERSION_ROAD_LIMITS)

# Bounds, mirroring the Java SignPackValidator constants where applicable.
MAX_BYTES = 5 * 1024 * 1024  # 5 MiB cap for a single pack file
MIN_POINTS = 1
MAX_POINTS_SYNTHETIC = 50
MAX_POINTS_REAL = 500_000

# Road evidence (v2): bounded like P.127 values, never guessed outside range.
ROAD_LIMIT_MIN_KMH, ROAD_LIMIT_MAX_KMH = 5, 120
MAX_ROAD_LIMITS_SYNTHETIC = 50
MAX_ROAD_LIMITS_REAL = 500_000
# The Android reader fetches at most this many rows for one exact edge; packs
# above the bound are rejected instead of being read partially.
MAX_ROAD_LIMITS_PER_EDGE = 32
ROAD_DIRECTIONS = ("FORWARD", "BACKWARD", "BOTH", "UNKNOWN")
ROAD_APPLICABILITIES = (
    "SUPPORTED",
    "CONDITIONAL_UNSUPPORTED",
    "LANE_UNSUPPORTED",
    "VEHICLE_UNSUPPORTED",
)

MIN_LAT, MAX_LAT = 8.0, 23.5  # Vietnam bounding box (WGS84)
MIN_LON, MAX_LON = 102.0, 110.0

DUPLICATE_METRES = 2.0
SIGN_TYPES = ("R420", "R421", "P127")
P127_MIN_KMH, P127_MAX_KMH = 5, 120

PAGE_SIZE = 4096

DDL = """
CREATE TABLE pack_manifest (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE edges (
    edge_id     TEXT PRIMARY KEY,
    carriageway TEXT NOT NULL,
    level       INTEGER NOT NULL,
    road_class  TEXT,
    lanes       INTEGER,
    heading_deg REAL
);

CREATE TABLE points (
    point_id    TEXT PRIMARY KEY,
    type        TEXT NOT NULL CHECK (type IN ('R420', 'R421', 'P127')),
    lat         REAL NOT NULL,
    lon         REAL NOT NULL,
    heading_deg REAL,
    value_kmh   INTEGER,
    edge_id     TEXT NOT NULL REFERENCES edges(edge_id),
    confidence  REAL NOT NULL,
    source      TEXT NOT NULL
);
"""

REQUIRED_TABLES = ("pack_manifest", "edges", "points")
REQUIRED_TABLES_V2 = REQUIRED_TABLES + ("road_limits",)

# v2 only. One row per typed road tag record; ``value_kmh`` is NULL for any
# unsupported applicability, so an unsupported rule can never leak a number.
ROAD_LIMITS_DDL = """
CREATE TABLE road_limits (
    road_limit_id TEXT PRIMARY KEY,
    edge_id       TEXT NOT NULL REFERENCES edges(edge_id),
    direction     TEXT NOT NULL CHECK (direction IN ('FORWARD', 'BACKWARD', 'BOTH', 'UNKNOWN')),
    value_kmh     INTEGER,
    applicability TEXT NOT NULL CHECK (applicability IN ('SUPPORTED', 'CONDITIONAL_UNSUPPORTED', 'LANE_UNSUPPORTED', 'VEHICLE_UNSUPPORTED')),
    confidence    REAL NOT NULL,
    provenance    TEXT NOT NULL
);
"""

# DB key names in pack_manifest (values are TEXT).
KEY_SOURCE = "source"
KEY_LICENSE = "license"
KEY_VERSION = "version"
KEY_SYNTHETIC = "synthetic"
KEY_FORMAT = "format"
KEY_FORMAT_VERSION = "formatVersion"
KEY_SCHEMA_VERSION = "schemaVersion"
KEY_EDGE_COUNT = "edgeCount"
KEY_POINT_COUNT = "pointCount"
KEY_ROAD_LIMIT_COUNT = "roadLimitCount"
KEY_CONTENT_SHA256 = "contentSha256"


def sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def sha256_file(path: str | Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    earth = 6371000.0
    d_lat = math.radians(lat2 - lat1)
    d_lon = math.radians(lon2 - lon1)
    a = (
        math.sin(d_lat / 2) * math.sin(d_lat / 2)
        + math.cos(math.radians(lat1))
        * math.cos(math.radians(lat2))
        * math.sin(d_lon / 2)
        * math.sin(d_lon / 2)
    )
    return 2 * earth * math.asin(min(1.0, math.sqrt(a)))


def edge_row(edge: dict) -> dict:
    return {
        "edge_id": edge["id"],
        "carriageway": edge["carriageway"],
        "level": int(edge["level"]),
        "road_class": edge.get("roadClass"),
        "lanes": edge.get("lanes"),
        "heading_deg": edge.get("headingDeg"),
    }


def point_row(point: dict) -> dict:
    return {
        "point_id": point["id"],
        "type": point["type"],
        "lat": point["lat"],
        "lon": point["lon"],
        "heading_deg": point.get("headingDeg"),
        "value_kmh": point.get("valueKmh"),
        "edge_id": point["edgeId"],
        "confidence": point["confidence"],
        "source": point["source"],
    }


def road_limit_row(limit: dict) -> dict:
    """Normalize one fixture road-evidence record into a road_limits row.

    Missing ``direction`` defaults to ``UNKNOWN`` and missing ``applicability``
    to ``SUPPORTED`` (mirroring the Java ``RoadLimit`` constructor defaults),
    never to a guessed direction/value.
    """
    return {
        "road_limit_id": limit["id"],
        "edge_id": limit["edgeId"],
        "direction": limit.get("direction", "UNKNOWN"),
        "value_kmh": limit.get("valueKmh"),
        "applicability": limit.get("applicability", "SUPPORTED"),
        "confidence": limit["confidence"],
        "provenance": limit.get("provenance", ""),
    }


def _as_float(value):
    return None if value is None else float(value)


def _as_int(value):
    return None if value is None else int(value)


def normalize_edge(row: dict) -> dict:
    """Type-stable edge row for the canonical digest (SQLite vs fixture paths)."""
    return {
        "edge_id": row["edge_id"],
        "carriageway": row["carriageway"],
        "level": int(row["level"]),
        "road_class": row["road_class"],
        "lanes": _as_int(row["lanes"]),
        "heading_deg": _as_float(row["heading_deg"]),
    }


def normalize_point(row: dict) -> dict:
    """Type-stable point row for the canonical digest (SQLite vs fixture paths)."""
    return {
        "point_id": row["point_id"],
        "type": row["type"],
        "lat": float(row["lat"]),
        "lon": float(row["lon"]),
        "heading_deg": _as_float(row["heading_deg"]),
        "value_kmh": _as_int(row["value_kmh"]),
        "edge_id": row["edge_id"],
        "confidence": float(row["confidence"]),
        "source": row["source"],
    }


def normalize_road_limit(row: dict) -> dict:
    """Type-stable road-limit row for the canonical digest (v2 only)."""
    return {
        "road_limit_id": row["road_limit_id"],
        "edge_id": row["edge_id"],
        "direction": row["direction"],
        "value_kmh": _as_int(row["value_kmh"]),
        "applicability": row["applicability"],
        "confidence": float(row["confidence"]),
        "provenance": row["provenance"],
    }


def canonical_content(
    edges: list[dict],
    points: list[dict],
    provenance: dict,
    road_limits: list[dict] | None = None,
) -> bytes:
    document = {
        "edges": sorted((normalize_edge(row) for row in edges), key=lambda r: r["edge_id"]),
        "points": sorted((normalize_point(row) for row in points), key=lambda r: r["point_id"]),
        "manifest": {
            "source": provenance["source"],
            "license": provenance["license"],
            "version": provenance["version"],
            "synthetic": bool(provenance["synthetic"]),
        },
    }
    # v1 digests must stay byte-identical: the road key exists only when road
    # evidence is supplied (v2 packs).
    if road_limits is not None:
        document["roadLimits"] = sorted(
            (normalize_road_limit(row) for row in road_limits),
            key=lambda r: r["road_limit_id"],
        )
    return json.dumps(
        document, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")


def canonical_sha256(
    edges: list[dict],
    points: list[dict],
    provenance: dict,
    road_limits: list[dict] | None = None,
) -> str:
    return sha256_bytes(canonical_content(edges, points, provenance, road_limits))
