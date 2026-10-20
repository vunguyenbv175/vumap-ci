"""Validate a ``.vupack`` (SQLite) against its manifest — host-side, stdlib only.

Checks, in order:
  1. manifest parse, JSON-object shape, required provenance/schema fields with
     their declared types, and value gates: format, formatVersion must equal the
     supported constants, schemaVersion must be 1 (sign/zone) or 2 (adds the
     optional ``road_limits`` table), and ``synthetic`` must be literally true
     (this pipeline is synthetic-only).
  2. bounded file size, truncated/empty rejection
  3. raw-byte SHA-256 against manifest.sha256 (declared checksum GATES acceptance)
  4. SQLite ``PRAGMA integrity_check`` must return 'ok'
  5. schema version (``PRAGMA user_version``, pack_manifest row AND manifest field)
  6. required tables/columns; in-pack format/formatVersion rows; synthetic marker
     present in the pack AND consistent with the manifest declaration
  7. point/edge (/v2 road-limit) counts vs manifest
  8. per-row SQLite value types validated BEFORE any arithmetic/normalization
     (malformed rows return deterministic rejection errors, never exceptions);
     per-point ranges (VN bbox, confidence, heading, P127 value range,
     R420/R421 must not carry a value), edge references, duplicate id /
     near-duplicate location+type; per-road-limit direction/applicability
     enums, 5..120 supported value, NULL value for unsupported applicability,
     confidence range and provenance
  9. canonical content digest vs manifest.contentSha256 (v1 digest unchanged;
     v2 additionally covers the road-limit rows)

Exit code 0 = accepted; 1 = rejected (errors printed). Never mutates the pack.

Usage:
    python tools/pack/pack_validate.py \
        --pack build/synthetic_vn_signs_p1.vupack \
        --manifest build/synthetic_vn_signs_p1.vupack.manifest.json
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pack_format as fmt  # noqa: E402

REQUIRED_MANIFEST_KEYS = (
    "format",
    "formatVersion",
    "schemaVersion",
    "source",
    "license",
    "version",
    "synthetic",
    "sha256",
)


def _is_int(value) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _is_num(value) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _is_str(value) -> bool:
    return isinstance(value, str)


def _load_manifest(path: str | Path) -> tuple[dict | None, list[str]]:
    errors: list[str] = []
    try:
        with open(path, "r", encoding="utf-8") as handle:
            manifest = json.load(handle)
    except Exception as exc:  # noqa: BLE001 - report any parse failure as-is
        return None, [f"MANIFEST_PARSE_ERROR:{exc}"]
    if not isinstance(manifest, dict):
        return None, [f"MANIFEST_NOT_OBJECT:{type(manifest).__name__}"]
    for key in REQUIRED_MANIFEST_KEYS:
        if manifest.get(key) in (None, ""):
            errors.append(f"MISSING_MANIFEST_FIELD:{key}")
    type_predicates = {
        "format": _is_str,
        "formatVersion": _is_int,
        "schemaVersion": _is_int,
        "source": _is_str,
        "license": _is_str,
        "version": _is_str,
        "synthetic": lambda value: isinstance(value, bool),
        "sha256": _is_str,
        "edgeCount": lambda value: value is None or _is_int(value),
        "pointCount": lambda value: value is None or _is_int(value),
        "roadLimitCount": lambda value: value is None or _is_int(value),
        "packBytes": lambda value: value is None or _is_int(value),
    }
    for key, predicate in type_predicates.items():
        if key in manifest and not predicate(manifest[key]):
            errors.append(f"MANIFEST_FIELD_TYPE:{key}:{type(manifest[key]).__name__}")
    # Value gates: declarations must match what this validator supports and the
    # synthetic-only pipeline requires a literally-true synthetic marker.
    if _is_str(manifest.get("format")) and manifest["format"] != fmt.FORMAT:
        errors.append(f"UNSUPPORTED_FORMAT:{manifest['format']}")
    if _is_int(manifest.get("formatVersion")) and manifest["formatVersion"] != fmt.FORMAT_VERSION:
        errors.append(f"UNSUPPORTED_FORMAT_VERSION:{manifest['formatVersion']}")
    if _is_int(manifest.get("schemaVersion")) and manifest["schemaVersion"] not in fmt.SUPPORTED_SCHEMA_VERSIONS:
        errors.append(f"UNSUPPORTED_SCHEMA_MANIFEST:{manifest['schemaVersion']}")
    if "synthetic" in manifest and manifest["synthetic"] is not True:
        errors.append("MANIFEST_SYNTHETIC_NOT_TRUE")
    return manifest, errors


def validate(pack_path: str | Path, manifest_path: str | Path) -> list[str]:
    errors: list[str] = []
    pack = Path(pack_path)

    manifest, manifest_errors = _load_manifest(manifest_path)
    errors.extend(manifest_errors)
    if manifest is None:
        return errors

    if not pack.exists():
        return errors + ["PACK_MISSING"]
    size = pack.stat().st_size
    if size <= 0:
        return errors + ["TRUNCATED_EMPTY_FILE"]
    if size > fmt.MAX_BYTES:
        return errors + [f"TOO_LARGE:{size}"]

    declared_sha = str(manifest.get("sha256", ""))
    actual_sha = fmt.sha256_file(pack)
    if not declared_sha:
        errors.append("CHECKSUM_UNVERIFIED")
    elif actual_sha.lower() != declared_sha.lower():
        errors.append("CHECKSUM_MISMATCH")

    try:
        connection = sqlite3.connect(f"file:{pack.as_posix()}?mode=ro", uri=True)
    except sqlite3.Error as exc:
        return errors + [f"SQLITE_OPEN_FAILED:{exc}"]

    try:
        integrity = connection.execute("PRAGMA integrity_check").fetchall()
        if integrity != [("ok",)]:
            errors.append(f"INTEGRITY_CHECK_FAILED:{integrity!r}")

        tables = {
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type = 'table'"
            ).fetchall()
        }
        schema_version = manifest.get("schemaVersion")
        if schema_version not in fmt.SUPPORTED_SCHEMA_VERSIONS:
            schema_version = fmt.SCHEMA_VERSION
        required_tables = (
            fmt.REQUIRED_TABLES_V2
            if schema_version == fmt.SCHEMA_VERSION_ROAD_LIMITS
            else fmt.REQUIRED_TABLES
        )
        for required in required_tables:
            if required not in tables:
                errors.append(f"MISSING_TABLE:{required}")
        if errors and any(e.startswith("MISSING_TABLE") for e in errors):
            return errors

        user_version = connection.execute("PRAGMA user_version").fetchone()[0]
        if user_version not in fmt.SUPPORTED_SCHEMA_VERSIONS:
            errors.append(f"UNSUPPORTED_SCHEMA:{user_version}")
        elif user_version != schema_version:
            errors.append(
                f"SCHEMA_DB_MISMATCH:db={user_version} manifest={schema_version}"
            )

        meta = dict(
            connection.execute("SELECT key, value FROM pack_manifest").fetchall()
        )
        # In-pack format declarations (external manifest cannot substitute).
        if meta.get(fmt.KEY_FORMAT) != fmt.FORMAT:
            errors.append(f"PACK_FORMAT_MISMATCH:{meta.get(fmt.KEY_FORMAT)}")
        if meta.get(fmt.KEY_FORMAT_VERSION) != str(fmt.FORMAT_VERSION):
            errors.append(
                f"UNSUPPORTED_FORMAT_VERSION_META:{meta.get(fmt.KEY_FORMAT_VERSION)}"
            )
        if meta.get(fmt.KEY_SYNTHETIC) != "true":
            errors.append("MISSING_SYNTHETIC_MARKER")
        meta_schema_raw = meta.get(fmt.KEY_SCHEMA_VERSION)
        if meta_schema_raw not in {str(value) for value in fmt.SUPPORTED_SCHEMA_VERSIONS}:
            errors.append(f"UNSUPPORTED_SCHEMA_META:{meta_schema_raw}")
        elif meta_schema_raw != str(schema_version):
            errors.append(
                f"SCHEMA_META_MISMATCH:meta={meta_schema_raw} manifest={schema_version}"
            )
        # Cross-check the manifest's synthetic declaration against the marker
        # stored inside the pack.
        manifest_synthetic = manifest.get("synthetic") is True
        meta_synthetic = meta.get(fmt.KEY_SYNTHETIC) == "true"
        if manifest_synthetic != meta_synthetic:
            errors.append("SYNTHETIC_MISMATCH")
        for manifest_key, meta_key in (
            ("source", fmt.KEY_SOURCE),
            ("license", fmt.KEY_LICENSE),
            ("version", fmt.KEY_VERSION),
        ):
            if str(manifest.get(manifest_key, "")) != meta.get(meta_key):
                errors.append(f"MANIFEST_META_MISMATCH:{manifest_key}")

        edges = [
            dict(zip(("edge_id", "carriageway", "level", "road_class", "lanes", "heading_deg"), row))
            for row in connection.execute(
                "SELECT edge_id, carriageway, level, road_class, lanes, heading_deg"
                " FROM edges ORDER BY edge_id"
            ).fetchall()
        ]
        points = [
            dict(zip(("point_id", "type", "lat", "lon", "heading_deg", "value_kmh", "edge_id", "confidence", "source"), row))
            for row in connection.execute(
                "SELECT point_id, type, lat, lon, heading_deg, value_kmh, edge_id,"
                " confidence, source FROM points ORDER BY point_id"
            ).fetchall()
        ]
        road_limits: list[dict] = []
        is_road_schema = schema_version == fmt.SCHEMA_VERSION_ROAD_LIMITS
        if is_road_schema and "road_limits" in tables:
            road_limits = [
                dict(zip(("road_limit_id", "edge_id", "direction", "value_kmh",
                          "applicability", "confidence", "provenance"), row))
                for row in connection.execute(
                    "SELECT road_limit_id, edge_id, direction, value_kmh,"
                    " applicability, confidence, provenance"
                    " FROM road_limits ORDER BY road_limit_id"
                ).fetchall()
            ]

        if meta.get(fmt.KEY_EDGE_COUNT) != str(len(edges)):
            errors.append(
                f"EDGE_COUNT_MISMATCH:meta={meta.get(fmt.KEY_EDGE_COUNT)} actual={len(edges)}"
            )
        if manifest.get("edgeCount") not in (None, len(edges)):
            errors.append(f"MANIFEST_EDGE_COUNT_MISMATCH:{manifest.get('edgeCount')}")
        if meta.get(fmt.KEY_POINT_COUNT) != str(len(points)):
            errors.append(
                f"POINT_COUNT_MISMATCH:meta={meta.get(fmt.KEY_POINT_COUNT)} actual={len(points)}"
            )
        if manifest.get("pointCount") not in (None, len(points)):
            errors.append(f"MANIFEST_POINT_COUNT_MISMATCH:{manifest.get('pointCount')}")
        if is_road_schema:
            if meta.get(fmt.KEY_ROAD_LIMIT_COUNT) != str(len(road_limits)):
                errors.append(
                    "ROAD_LIMIT_COUNT_MISMATCH:meta="
                    f"{meta.get(fmt.KEY_ROAD_LIMIT_COUNT)} actual={len(road_limits)}"
                )
            if manifest.get("roadLimitCount") not in (None, len(road_limits)):
                errors.append(
                    f"MANIFEST_ROAD_LIMIT_COUNT_MISMATCH:{manifest.get('roadLimitCount')}"
                )

        # v1 requires at least one sign point. v2 also accepts a road-evidence
        # pack with no signs, but never an entirely empty pack.
        if is_road_schema:
            if len(points) + len(road_limits) < fmt.MIN_POINTS:
                errors.append("TRUNCATED_NO_EVIDENCE")
        elif len(points) < fmt.MIN_POINTS:
            errors.append("TRUNCATED_NO_POINTS")
        # The synthetic point budget comes from the VALIDATED synthetic marker
        # (manifest declaration + in-pack marker), not from a name prefix.
        synthetic_valid = manifest_synthetic and meta_synthetic
        budget = fmt.MAX_POINTS_SYNTHETIC if synthetic_valid else fmt.MAX_POINTS_REAL
        if len(points) > budget:
            errors.append(f"TOO_MANY_POINTS:{len(points)}")
        road_budget = (
            fmt.MAX_ROAD_LIMITS_SYNTHETIC if synthetic_valid else fmt.MAX_ROAD_LIMITS_REAL
        )
        if len(road_limits) > road_budget:
            errors.append(f"TOO_MANY_ROAD_LIMITS:{len(road_limits)}")

        # ---- row shape gates: SQLite columns are non-STRICT, so values may    -
        # ---- arrive as text in nominally numeric columns. Validate types      -
        # ---- BEFORE any arithmetic, range check, or digest normalization.     -
        malformed_rows = False

        for index, edge in enumerate(edges):
            where = f"EDGE[{index}]"
            if not _is_str(edge["edge_id"]) or not edge["edge_id"]:
                errors.append(f"{where}:BAD_EDGE_ID_TYPE:{edge['edge_id']!r}")
                malformed_rows = True
            if not _is_str(edge["carriageway"]) or not edge["carriageway"]:
                errors.append(f"{where}:BAD_CARRIAGEWAY_TYPE:{edge['carriageway']!r}")
                malformed_rows = True
            if not _is_int(edge["level"]):
                errors.append(f"{where}:BAD_LEVEL_TYPE:{edge['level']!r}")
                malformed_rows = True
            if edge["road_class"] is not None and not _is_str(edge["road_class"]):
                errors.append(f"{where}:BAD_ROAD_CLASS_TYPE:{edge['road_class']!r}")
                malformed_rows = True
            if edge["lanes"] is not None and not _is_int(edge["lanes"]):
                errors.append(f"{where}:BAD_LANES_TYPE:{edge['lanes']!r}")
                malformed_rows = True
            if edge["heading_deg"] is not None and not _is_num(edge["heading_deg"]):
                errors.append(f"{where}:BAD_EDGE_HEADING_TYPE:{edge['heading_deg']!r}")
                malformed_rows = True

        edge_ids = {
            edge["edge_id"] for edge in edges if _is_str(edge["edge_id"])
        }
        seen: set[str] = set()
        coord_points: list[dict] = []
        for index, point in enumerate(points):
            where = f"POINT[{index}]"
            if not _is_str(point["point_id"]) or not point["point_id"]:
                errors.append(f"{where}:BAD_ID_TYPE:{point['point_id']!r}")
                malformed_rows = True
            elif point["point_id"] in seen:
                errors.append(f"{where}:DUPLICATE_ID:{point['point_id']}")
            else:
                seen.add(point["point_id"])

            type_ok = _is_str(point["type"])
            if not type_ok:
                errors.append(f"{where}:BAD_TYPE:{point['type']!r}")
                malformed_rows = True
            elif point["type"] not in fmt.SIGN_TYPES:
                errors.append(f"{where}:BAD_TYPE:{point['type']}")

            lat, lon = point["lat"], point["lon"]
            coords_ok = _is_num(lat) and _is_num(lon)
            if not coords_ok:
                errors.append(f"{where}:BAD_COORD_TYPE:{lat!r},{lon!r}")
                malformed_rows = True
            elif not (fmt.MIN_LAT <= lat <= fmt.MAX_LAT and fmt.MIN_LON <= lon <= fmt.MAX_LON):
                errors.append(f"{where}:OUT_OF_VN_RANGE:{lat},{lon}")

            confidence = point["confidence"]
            if not _is_num(confidence) or not (0.0 <= confidence <= 1.0):
                errors.append(f"{where}:BAD_CONFIDENCE:{confidence!r}")
                if not _is_num(confidence):
                    malformed_rows = True

            heading = point["heading_deg"]
            if heading is not None:
                if not _is_num(heading):
                    errors.append(f"{where}:BAD_HEADING_TYPE:{heading!r}")
                    malformed_rows = True
                elif not 0.0 <= heading < 360.0:
                    errors.append(f"{where}:BAD_HEADING:{heading}")

            edge_id = point["edge_id"]
            if not _is_str(edge_id) or not edge_id:
                errors.append(f"{where}:BAD_EDGE_TYPE:{edge_id!r}")
                malformed_rows = True
            elif edge_id not in edge_ids:
                errors.append(f"{where}:UNKNOWN_EDGE:{edge_id}")

            if not _is_str(point["source"]) or not point["source"]:
                errors.append(f"{where}:MISSING_SOURCE:{point['point_id']}")
                if not _is_str(point["source"]):
                    malformed_rows = True

            value = point["value_kmh"]
            if type_ok and point["type"] == "P127":
                if not _is_int(value) or not (fmt.P127_MIN_KMH <= value <= fmt.P127_MAX_KMH):
                    errors.append(f"{where}:BAD_P127_VALUE:{value!r}")
                    if not _is_int(value):
                        malformed_rows = True
            elif type_ok:
                if value is not None:
                    if not _is_int(value):
                        errors.append(f"{where}:BAD_VALUE_TYPE:{value!r}")
                        malformed_rows = True
                    else:
                        errors.append(f"{where}:ZONE_SIGN_MUST_NOT_CARRY_VALUE:{point['point_id']}")

            if coords_ok and type_ok:
                coord_points.append(point)

        # ---- road evidence rows (schema v2 only) ---------------------------
        # Unsupported applicability may never carry a value; a supported row
        # must be in range. Anything else is a deterministic rejection.
        seen_road_ids: set[str] = set()
        road_limits_by_edge: dict[str, int] = {}
        for index, limit in enumerate(road_limits):
            where = f"ROAD_LIMIT[{index}]"
            limit_id = limit["road_limit_id"]
            if not _is_str(limit_id) or not limit_id:
                errors.append(f"{where}:BAD_ID_TYPE:{limit_id!r}")
                malformed_rows = True
            elif limit_id in seen_road_ids:
                errors.append(f"{where}:DUPLICATE_ID:{limit_id}")
            else:
                seen_road_ids.add(limit_id)

            edge_id = limit["edge_id"]
            if not _is_str(edge_id) or not edge_id:
                errors.append(f"{where}:BAD_EDGE_TYPE:{edge_id!r}")
                malformed_rows = True
            elif edge_id not in edge_ids:
                errors.append(f"{where}:UNKNOWN_EDGE:{edge_id}")
            else:
                road_limits_by_edge[edge_id] = road_limits_by_edge.get(edge_id, 0) + 1

            direction = limit["direction"]
            if not _is_str(direction) or direction not in fmt.ROAD_DIRECTIONS:
                errors.append(f"{where}:BAD_DIRECTION:{direction!r}")
                if not _is_str(direction):
                    malformed_rows = True

            applicability = limit["applicability"]
            app_ok = _is_str(applicability) and applicability in fmt.ROAD_APPLICABILITIES
            if not app_ok:
                errors.append(f"{where}:BAD_APPLICABILITY:{applicability!r}")
                if not _is_str(applicability):
                    malformed_rows = True

            value = limit["value_kmh"]
            if app_ok and applicability == "SUPPORTED":
                if not _is_int(value) or not (
                    fmt.ROAD_LIMIT_MIN_KMH <= value <= fmt.ROAD_LIMIT_MAX_KMH
                ):
                    errors.append(f"{where}:BAD_ROAD_LIMIT_VALUE:{value!r}")
                    if not _is_int(value):
                        malformed_rows = True
            elif value is not None:
                if not _is_int(value):
                    errors.append(f"{where}:BAD_VALUE_TYPE:{value!r}")
                    malformed_rows = True
                else:
                    errors.append(
                        f"{where}:UNSUPPORTED_ROAD_LIMIT_MUST_NOT_CARRY_VALUE:{limit_id}"
                    )

            confidence = limit["confidence"]
            if not _is_num(confidence) or not (0.0 <= confidence <= 1.0):
                errors.append(f"{where}:BAD_CONFIDENCE:{confidence!r}")
                if not _is_num(confidence):
                    malformed_rows = True

            provenance_text = limit["provenance"]
            if not _is_str(provenance_text) or not provenance_text:
                errors.append(f"{where}:MISSING_PROVENANCE:{limit_id}")
                if not _is_str(provenance_text):
                    malformed_rows = True

        # The Android reader is bounded to MAX_ROAD_LIMITS_PER_EDGE rows for an
        # exact edge. Reject an over-budget edge here instead of letting the
        # reader pass a silently truncated evidence list to the resolver.
        for edge_id, count in sorted(road_limits_by_edge.items()):
            if count > fmt.MAX_ROAD_LIMITS_PER_EDGE:
                errors.append(f"TOO_MANY_ROAD_LIMITS_PER_EDGE:{edge_id}:{count}")

        # Duplicate-location scan only over rows with numeric coordinates and a
        # comparable sign type (malformed rows already guarantee rejection).
        for i, first in enumerate(coord_points):
            if len(errors) >= 50:
                break
            for second in coord_points[i + 1 :]:
                if first["type"] != second["type"]:
                    continue
                if (
                    fmt.haversine_m(first["lat"], first["lon"], second["lat"], second["lon"])
                    < fmt.DUPLICATE_METRES
                ):
                    errors.append(
                        f"DUPLICATE_LOCATION:{first['point_id']}~{second['point_id']}"
                    )
                    break

        # Canonical digest requires well-typed rows; when a malformed row was
        # found we already carry a deterministic rejection error, so skip the
        # normalization instead of letting it raise.
        if not malformed_rows:
            provenance = {
                "source": meta.get(fmt.KEY_SOURCE),
                "license": meta.get(fmt.KEY_LICENSE),
                "version": meta.get(fmt.KEY_VERSION),
                "synthetic": meta_synthetic,
            }
            content_sha = fmt.canonical_sha256(
                edges,
                points,
                provenance,
                road_limits if is_road_schema else None,
            )
            if content_sha != meta.get(fmt.KEY_CONTENT_SHA256):
                errors.append("CONTENT_DIGEST_MISMATCH")
            declared_content = manifest.get(
                "contentSha256", meta.get(fmt.KEY_CONTENT_SHA256)
            )
            if content_sha != declared_content:
                errors.append("MANIFEST_CONTENT_DIGEST_MISMATCH")
    except sqlite3.DatabaseError as exc:
        errors.append(f"SQLITE_ERROR:{exc}")
    except (TypeError, ValueError) as exc:
        # Defense in depth: any shape issue that slipped past the row gates is
        # still a deterministic rejection, never an unhandled exception.
        errors.append(f"DATA_SHAPE_ERROR:{exc}")
    finally:
        connection.close()

    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", required=True)
    parser.add_argument("--manifest", required=True)
    args = parser.parse_args(argv)

    errors = validate(args.pack, args.manifest)
    if errors:
        print("RESULT: PACK REJECTED")
        for error in errors:
            print(f"  - {error}")
        return 1
    print("RESULT: PACK OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
