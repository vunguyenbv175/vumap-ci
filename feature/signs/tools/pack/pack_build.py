"""Build a deterministic synthetic ``.vupack`` (SQLite) from the P1 fixture.

Usage:
    python tools/pack/pack_build.py \
        --fixture fixtures/synthetic_vn_signs_p1.json \
        --out build/synthetic_vn_signs_p1.vupack

Determinism contract: same input fixture + same FORMAT/SCHEMA constants produce
byte-identical pack bytes. We achieve that by pinning page_size, using a fixed
DDL, inserting rows in sorted order, and avoiding timestamps or PRAGMA values
that vary between runs. The manifest records sha256(pack bytes) and the
canonical content digest.

This builder never invents data: it copies exactly the fixture's points/edges
and records the fixture provenance (which marks the data synthetic).
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pack_format as fmt  # noqa: E402


def load_fixture(path: str | Path) -> dict:
    with open(path, "r", encoding="utf-8") as handle:
        document = json.load(handle)
    schema_version = document.get("schemaVersion")
    if schema_version not in fmt.SUPPORTED_SCHEMA_VERSIONS:
        raise SystemExit(
            f"fixture schemaVersion {schema_version!r} "
            f"!= supported {fmt.SUPPORTED_SCHEMA_VERSIONS}"
        )
    if schema_version == fmt.SCHEMA_VERSION_ROAD_LIMITS:
        if "roadLimits" not in document:
            raise SystemExit("v2 fixture must declare a roadLimits list")
    elif document.get("roadLimits"):
        # Never silently drop evidence: a v1 fixture that carries road limits
        # must be bumped to schemaVersion 2.
        raise SystemExit("v1 fixture carries roadLimits; set schemaVersion 2")
    provenance = document.get("provenance") or {}
    for key in ("source", "license", "version"):
        if not provenance.get(key):
            raise SystemExit(f"fixture provenance missing {key!r}")
    # This builder only produces synthetic CI packs. Refuse to re-label real
    # OSM extracts as synthetic; a real-data pack needs a separate safety gate.
    if provenance.get("synthetic") is False:
        raise SystemExit(
            "fixture provenance declares synthetic=false; "
            "the pack builder is synthetic-only"
        )
    if not document.get("points") and not document.get("roadLimits"):
        raise SystemExit("fixture has no evidence rows")
    return document


def build_pack(fixture: dict, out_path: str | Path) -> dict:
    out = Path(out_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    if out.exists():
        out.unlink()

    schema_version = (
        fmt.SCHEMA_VERSION_ROAD_LIMITS
        if fixture.get("schemaVersion") == fmt.SCHEMA_VERSION_ROAD_LIMITS
        else fmt.SCHEMA_VERSION
    )
    road_limits: list[dict] | None = None
    if schema_version == fmt.SCHEMA_VERSION_ROAD_LIMITS:
        road_limits = [fmt.road_limit_row(limit) for limit in (fixture.get("roadLimits") or [])]
        road_limits.sort(key=lambda row: row["road_limit_id"])

    edges = [fmt.edge_row(edge) for edge in (fixture.get("edges") or [])]
    points = [fmt.point_row(point) for point in fixture["points"]] if fixture.get("points") else []
    edges.sort(key=lambda row: row["edge_id"])
    points.sort(key=lambda row: row["point_id"])

    fixture_provenance = fixture["provenance"]
    provenance = {
        "source": fixture_provenance["source"],
        "license": fixture_provenance["license"],
        "version": fixture_provenance["version"],
        # This builder only produces synthetic CI packs; the fixture provenance
        # must not claim real data (never field truth).
        "synthetic": True,
    }
    content_sha = fmt.canonical_sha256(edges, points, provenance, road_limits)
    manifest_rows = [
        (fmt.KEY_FORMAT, fmt.FORMAT),
        (fmt.KEY_FORMAT_VERSION, str(fmt.FORMAT_VERSION)),
        (fmt.KEY_SCHEMA_VERSION, str(schema_version)),
        (fmt.KEY_SOURCE, provenance["source"]),
        (fmt.KEY_LICENSE, provenance["license"]),
        (fmt.KEY_VERSION, provenance["version"]),
        (fmt.KEY_SYNTHETIC, "true"),
        (fmt.KEY_EDGE_COUNT, str(len(edges))),
        (fmt.KEY_POINT_COUNT, str(len(points))),
        (fmt.KEY_CONTENT_SHA256, content_sha),
    ]
    if road_limits is not None:
        manifest_rows.append((fmt.KEY_ROAD_LIMIT_COUNT, str(len(road_limits))))

    connection = sqlite3.connect(out)
    try:
        connection.execute(f"PRAGMA page_size = {fmt.PAGE_SIZE}")
        connection.executescript(fmt.DDL)
        if road_limits is not None:
            connection.executescript(fmt.ROAD_LIMITS_DDL)
        connection.execute(f"PRAGMA user_version = {schema_version}")
        connection.executemany(
            "INSERT INTO pack_manifest (key, value) VALUES (?, ?)", manifest_rows
        )
        connection.executemany(
            "INSERT INTO edges (edge_id, carriageway, level, road_class, lanes, heading_deg)"
            " VALUES (:edge_id, :carriageway, :level, :road_class, :lanes, :heading_deg)",
            edges,
        )
        if points:
            connection.executemany(
                "INSERT INTO points (point_id, type, lat, lon, heading_deg, value_kmh,"
                " edge_id, confidence, source)"
                " VALUES (:point_id, :type, :lat, :lon, :heading_deg, :value_kmh,"
                " :edge_id, :confidence, :source)",
                points,
            )
        if road_limits is not None:
            connection.executemany(
                "INSERT INTO road_limits (road_limit_id, edge_id, direction, value_kmh,"
                " applicability, confidence, provenance)"
                " VALUES (:road_limit_id, :edge_id, :direction, :value_kmh,"
                " :applicability, :confidence, :provenance)",
                road_limits,
            )
        connection.commit()
        connection.execute("VACUUM")
        connection.commit()
    finally:
        connection.close()

    pack_sha = fmt.sha256_file(out)
    manifest = {
        "format": fmt.FORMAT,
        "formatVersion": fmt.FORMAT_VERSION,
        "schemaVersion": schema_version,
        "source": provenance["source"],
        "license": provenance["license"],
        "version": provenance["version"],
        "synthetic": True,
        "edgeCount": len(edges),
        "pointCount": len(points),
        "packBytes": out.stat().st_size,
        "sha256": pack_sha,
        "contentSha256": content_sha,
        "note": (
            "Synthetic CI fixture pack - NOT field truth, NOT surveyed signs. "
            "Never navigate by this data."
        ),
    }
    if road_limits is not None:
        manifest["roadLimitCount"] = len(road_limits)
    return manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument(
        "--manifest",
        help="manifest output path (default: <out>.manifest.json)",
    )
    args = parser.parse_args(argv)

    fixture = load_fixture(args.fixture)
    manifest = build_pack(fixture, args.out)
    manifest_path = Path(args.manifest) if args.manifest else Path(f"{args.out}.manifest.json")
    manifest_path.write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    print(f"pack={args.out}")
    print(f"bytes={manifest['packBytes']}")
    print(f"sha256={manifest['sha256']}")
    print(f"contentSha256={manifest['contentSha256']}")
    print(f"edges={manifest['edgeCount']} points={manifest['pointCount']}")
    print(f"manifest={manifest_path}")
    print("RESULT: PACK BUILD OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
