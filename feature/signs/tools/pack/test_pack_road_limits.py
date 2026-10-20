"""Schema-v2 road-evidence pack tests — real temp SQLite files.

Covers the additive v2 contract: deterministic build, v1 packs still accepted
unchanged (no road table, same canonical digest path), road-only packs without
sign points, unsupported-applicability rows that never carry a value, and
deterministic rejection of malformed/duplicate/out-of-bounds road rows while
the raw-byte checksum and canonical digest still gate acceptance.

Run: python tools/pack/run_pack_tests.py   (or)   python -m unittest -v
"""

from __future__ import annotations

import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pack_build  # noqa: E402
import pack_format as fmt  # noqa: E402
import pack_validate  # noqa: E402

FIXTURES = Path(__file__).resolve().parents[2] / "fixtures"
V1_FIXTURE = FIXTURES / "synthetic_vn_signs_p1.json"
V2_FIXTURE = FIXTURES / "synthetic_vn_road_limits_p2.json"


def _read_pack(pack: Path):
    connection = sqlite3.connect(pack)
    try:
        tables = {
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type = 'table'"
            ).fetchall()
        }
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
        road_limits = []
        if "road_limits" in tables:
            road_limits = [
                dict(zip(("road_limit_id", "edge_id", "direction", "value_kmh",
                          "applicability", "confidence", "provenance"), row))
                for row in connection.execute(
                    "SELECT road_limit_id, edge_id, direction, value_kmh,"
                    " applicability, confidence, provenance"
                    " FROM road_limits ORDER BY road_limit_id"
                ).fetchall()
            ]
        meta = dict(
            connection.execute("SELECT key, value FROM pack_manifest").fetchall()
        )
    finally:
        connection.close()
    return edges, points, road_limits, meta


def _resync(pack: Path, manifest_path: Path, meta_updates=None, manifest_updates=None):
    """Re-sign a tampered pack+manifest so only the intended error surfaces."""
    edges, points, road_limits, meta = _read_pack(pack)
    is_v2 = meta.get(fmt.KEY_SCHEMA_VERSION) == str(fmt.SCHEMA_VERSION_ROAD_LIMITS)
    provenance = {
        "source": meta[fmt.KEY_SOURCE],
        "license": meta[fmt.KEY_LICENSE],
        "version": meta[fmt.KEY_VERSION],
        "synthetic": meta[fmt.KEY_SYNTHETIC] == "true",
    }
    content_sha = fmt.canonical_sha256(
        edges, points, provenance, road_limits if is_v2 else None
    )
    connection = sqlite3.connect(pack)
    try:
        for key, value in (
            (fmt.KEY_CONTENT_SHA256, content_sha),
            (fmt.KEY_EDGE_COUNT, str(len(edges))),
            (fmt.KEY_POINT_COUNT, str(len(points))),
        ):
            connection.execute(
                "UPDATE pack_manifest SET value = ? WHERE key = ?", (value, key)
            )
        if is_v2:
            connection.execute(
                "UPDATE pack_manifest SET value = ? WHERE key = ?",
                (str(len(road_limits)), fmt.KEY_ROAD_LIMIT_COUNT),
            )
        for key, value in (meta_updates or {}).items():
            connection.execute(
                "UPDATE pack_manifest SET value = ? WHERE key = ?", (str(value), key)
            )
        connection.commit()
    finally:
        connection.close()

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["sha256"] = fmt.sha256_file(pack)
    manifest["contentSha256"] = content_sha
    manifest["edgeCount"] = len(edges)
    manifest["pointCount"] = len(points)
    if is_v2:
        manifest["roadLimitCount"] = len(road_limits)
    manifest["packBytes"] = pack.stat().st_size
    for key, value in (manifest_updates or {}).items():
        manifest[key] = value
    manifest_path.write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return manifest


def _edit_sql(pack: Path, statement: str, params=(), ignore_checks: bool = False):
    connection = sqlite3.connect(pack)
    try:
        if ignore_checks:
            connection.execute("PRAGMA ignore_check_constraints = ON")
        connection.execute(statement, params)
        connection.commit()
    finally:
        connection.close()


def _replace_road_limits(pack: Path, rows):
    """Replace road_limits with an unconstrained table (duplicate-id fixture)."""
    connection = sqlite3.connect(pack)
    try:
        connection.execute("DROP TABLE road_limits")
        connection.execute(
            "CREATE TABLE road_limits (road_limit_id TEXT, edge_id TEXT,"
            " direction TEXT, value_kmh INTEGER, applicability TEXT,"
            " confidence REAL, provenance TEXT)"
        )
        connection.executemany(
            "INSERT INTO road_limits VALUES (?, ?, ?, ?, ?, ?, ?)", rows
        )
        connection.commit()
    finally:
        connection.close()


def _set_user_version(pack: Path, version: int):
    _edit_sql(pack, f"PRAGMA user_version = {version}")


class PackRoadLimitsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="vumap-road-v2-")
        self.dir = Path(self.tmp.name)
        self.pack = self.dir / "road_limits.vupack"
        self.manifest = self.dir / "road_limits.vupack.manifest.json"
        self.fixture = json.loads(V2_FIXTURE.read_text(encoding="utf-8"))
        self.built = pack_build.build_pack(self.fixture, self.pack)
        self.manifest.write_text(
            json.dumps(self.built, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )

    def tearDown(self):
        self.tmp.cleanup()

    def test_build_and_validate_v2(self):
        self.assertEqual(2, self.built["schemaVersion"])
        self.assertEqual(9, self.built["roadLimitCount"])
        self.assertEqual([], pack_validate.validate(self.pack, self.manifest))

    def test_v2_declarations_and_table(self):
        connection = sqlite3.connect(self.pack)
        try:
            self.assertEqual(
                2, connection.execute("PRAGMA user_version").fetchone()[0]
            )
            meta = dict(
                connection.execute("SELECT key, value FROM pack_manifest").fetchall()
            )
            self.assertEqual("2", meta[fmt.KEY_SCHEMA_VERSION])
            self.assertEqual("9", meta[fmt.KEY_ROAD_LIMIT_COUNT])
            ids = [
                row[0]
                for row in connection.execute(
                    "SELECT road_limit_id FROM road_limits ORDER BY road_limit_id"
                ).fetchall()
            ]
            self.assertEqual(sorted(ids), ids)
        finally:
            connection.close()

    def test_rebuild_is_byte_identical(self):
        rebuild = self.dir / "rebuild.vupack"
        pack_build.build_pack(self.fixture, rebuild)
        self.assertEqual(
            fmt.sha256_file(self.pack), fmt.sha256_file(rebuild)
        )

    def test_v1_sign_only_pack_stays_v1_and_valid(self):
        v1_pack = self.dir / "v1.vupack"
        v1_manifest_path = self.dir / "v1.vupack.manifest.json"
        fixture = json.loads(V1_FIXTURE.read_text(encoding="utf-8"))
        manifest = pack_build.build_pack(fixture, v1_pack)
        v1_manifest_path.write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        self.assertEqual(1, manifest["schemaVersion"])
        self.assertNotIn("roadLimitCount", manifest)
        connection = sqlite3.connect(v1_pack)
        try:
            self.assertEqual(1, connection.execute("PRAGMA user_version").fetchone()[0])
            tables = {
                row[0]
                for row in connection.execute(
                    "SELECT name FROM sqlite_master WHERE type = 'table'"
                ).fetchall()
            }
            self.assertNotIn("road_limits", tables)
        finally:
            connection.close()
        self.assertEqual([], pack_validate.validate(v1_pack, v1_manifest_path))

    def test_road_only_pack_without_points_is_accepted(self):
        fixture = dict(self.fixture)
        fixture["points"] = []
        pack = self.dir / "road_only.vupack"
        manifest_path = self.dir / "road_only.vupack.manifest.json"
        manifest = pack_build.build_pack(fixture, pack)
        manifest_path.write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        self.assertEqual(0, manifest["pointCount"])
        self.assertEqual(9, manifest["roadLimitCount"])
        self.assertEqual([], pack_validate.validate(pack, manifest_path))

    def test_all_empty_v2_pack_is_rejected(self):
        fixture = dict(self.fixture)
        fixture["points"] = []
        fixture["roadLimits"] = []
        pack = self.dir / "empty.vupack"
        manifest_path = self.dir / "empty.vupack.manifest.json"
        manifest = pack_build.build_pack(fixture, pack)
        manifest_path.write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        errors = pack_validate.validate(pack, manifest_path)
        self.assertIn("TRUNCATED_NO_EVIDENCE", errors)

    def test_supported_value_out_of_bounds_is_rejected(self):
        _edit_sql(
            self.pack,
            "UPDATE road_limits SET value_kmh = 200 WHERE road_limit_id = 'RL1_FWD'",
        )
        _resync(self.pack, self.manifest)
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertTrue(any("BAD_ROAD_LIMIT_VALUE" in e for e in errors), errors)

    def test_supported_value_must_be_integer(self):
        _edit_sql(
            self.pack,
            "UPDATE road_limits SET value_kmh = '60 km/h' WHERE road_limit_id = 'RL1_FWD'",
        )
        # A malformed row cannot be re-normalized for the content digest: the
        # validator must reject it deterministically without attempting one.
        manifest = json.loads(self.manifest.read_text(encoding="utf-8"))
        manifest["sha256"] = fmt.sha256_file(self.pack)
        self.manifest.write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertTrue(any("BAD_ROAD_LIMIT_VALUE" in e for e in errors), errors)

    def test_unsupported_applicability_must_not_carry_value(self):
        _edit_sql(
            self.pack,
            "UPDATE road_limits SET value_kmh = 60 WHERE road_limit_id = 'RL3_HGV'",
        )
        _resync(self.pack, self.manifest)
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertTrue(
            any("UNSUPPORTED_ROAD_LIMIT_MUST_NOT_CARRY_VALUE" in e for e in errors),
            errors,
        )

    def test_bad_direction_is_rejected(self):
        _edit_sql(
            self.pack,
            "UPDATE road_limits SET direction = 'SIDEWAYS' WHERE road_limit_id = 'RL1_FWD'",
            ignore_checks=True,
        )
        _resync(self.pack, self.manifest)
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertTrue(any("BAD_DIRECTION" in e for e in errors), errors)

    def test_bad_applicability_is_rejected(self):
        _edit_sql(
            self.pack,
            "UPDATE road_limits SET applicability = 'MAYBE' WHERE road_limit_id = 'RL1_FWD'",
            ignore_checks=True,
        )
        _resync(self.pack, self.manifest)
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertTrue(any("BAD_APPLICABILITY" in e for e in errors), errors)

    def test_bad_confidence_and_missing_provenance_are_rejected(self):
        _edit_sql(
            self.pack,
            "UPDATE road_limits SET confidence = 1.5 WHERE road_limit_id = 'RL1_FWD'",
        )
        _edit_sql(
            self.pack,
            "UPDATE road_limits SET provenance = '' WHERE road_limit_id = 'RL2_BOTH'",
        )
        _resync(self.pack, self.manifest)
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertTrue(any("BAD_CONFIDENCE" in e for e in errors), errors)
        self.assertTrue(any("MISSING_PROVENANCE" in e for e in errors), errors)

    def test_unknown_edge_is_rejected(self):
        _edit_sql(
            self.pack,
            "UPDATE road_limits SET edge_id = 'NOPE' WHERE road_limit_id = 'RL1_FWD'",
        )
        _resync(self.pack, self.manifest)
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertTrue(any("UNKNOWN_EDGE:NOPE" in e for e in errors), errors)

    def test_duplicate_road_limit_id_is_rejected(self):
        rows = [
            ("DUP", "E_RL1", "FORWARD", 60, "SUPPORTED", 0.85, "duplicate"),
            ("DUP", "E_RL1", "FORWARD", 60, "SUPPORTED", 0.85, "duplicate"),
        ]
        _replace_road_limits(self.pack, rows)
        _resync(self.pack, self.manifest)
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertTrue(any("DUPLICATE_ID:DUP" in e for e in errors), errors)

    def test_digest_covers_road_limit_values(self):
        _edit_sql(
            self.pack,
            "UPDATE road_limits SET value_kmh = 70 WHERE road_limit_id = 'RL1_FWD'",
        )
        # Re-sign the raw-byte checksum only: the content digest must still fail.
        manifest = json.loads(self.manifest.read_text(encoding="utf-8"))
        manifest["sha256"] = fmt.sha256_file(self.pack)
        self.manifest.write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertIn("CONTENT_DIGEST_MISMATCH", errors)

    def test_manifest_road_limit_count_mismatch_is_rejected(self):
        _resync(
            self.pack,
            self.manifest,
            manifest_updates={"roadLimitCount": 99},
        )
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertTrue(
            any("MANIFEST_ROAD_LIMIT_COUNT_MISMATCH" in e for e in errors), errors
        )

    def test_meta_road_limit_count_mismatch_is_rejected(self):
        _resync(
            self.pack,
            self.manifest,
            meta_updates={fmt.KEY_ROAD_LIMIT_COUNT: "99"},
        )
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertTrue(any("ROAD_LIMIT_COUNT_MISMATCH" in e for e in errors), errors)

    def test_schema_db_mismatch_is_rejected(self):
        _set_user_version(self.pack, 1)
        _resync(self.pack, self.manifest)
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertTrue(any("SCHEMA_DB_MISMATCH" in e for e in errors), errors)

    def test_unsupported_schema_version_is_rejected(self):
        _resync(
            self.pack,
            self.manifest,
            meta_updates={fmt.KEY_SCHEMA_VERSION: "3"},
            manifest_updates={"schemaVersion": 3},
        )
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertTrue(any("UNSUPPORTED_SCHEMA_MANIFEST:3" in e for e in errors), errors)
        self.assertTrue(any("UNSUPPORTED_SCHEMA_META:3" in e for e in errors), errors)

    def test_v1_fixture_with_road_limits_is_rejected(self):
        fixture = json.loads(V2_FIXTURE.read_text(encoding="utf-8"))
        fixture["schemaVersion"] = 1
        path = self.dir / "bad_v1_fixture.json"
        path.write_text(json.dumps(fixture), encoding="utf-8")
        with self.assertRaises(SystemExit):
            pack_build.load_fixture(path)

    def test_v2_fixture_without_road_limits_key_is_rejected(self):
        fixture = json.loads(V2_FIXTURE.read_text(encoding="utf-8"))
        del fixture["roadLimits"]
        path = self.dir / "bad_v2_fixture.json"
        path.write_text(json.dumps(fixture), encoding="utf-8")
        with self.assertRaises(SystemExit):
            pack_build.load_fixture(path)

    def test_synthetic_road_limit_budget_is_enforced(self):
        fixture = json.loads(V2_FIXTURE.read_text(encoding="utf-8"))
        fixture["points"] = []
        fixture["roadLimits"] = [
            {
                "id": f"BULK_{index:03d}",
                "edgeId": "E_RL1",
                "direction": "BOTH",
                "valueKmh": 50,
                "applicability": "SUPPORTED",
                "confidence": 0.8,
                "provenance": "bulk synthetic",
            }
            for index in range(fmt.MAX_ROAD_LIMITS_SYNTHETIC + 1)
        ]
        pack = self.dir / "bulk.vupack"
        manifest_path = self.dir / "bulk.vupack.manifest.json"
        manifest = pack_build.build_pack(fixture, pack)
        manifest_path.write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        errors = pack_validate.validate(pack, manifest_path)
        self.assertTrue(any(e.startswith("TOO_MANY_ROAD_LIMITS") for e in errors), errors)

    def test_per_edge_road_limit_budget_accepts_exact_bound(self):
        fixture = json.loads(V2_FIXTURE.read_text(encoding="utf-8"))
        fixture["points"] = []
        fixture["roadLimits"] = [
            {
                "id": f"EDGE_{index:02d}",
                "edgeId": "E_RL1",
                "direction": "BOTH",
                "valueKmh": 60,
                "applicability": "SUPPORTED",
                "confidence": 0.8,
                "provenance": "per-edge bound synthetic",
            }
            for index in range(fmt.MAX_ROAD_LIMITS_PER_EDGE)
        ]
        # Decisive conflict is retained within the accepted bound, never dropped.
        fixture["roadLimits"][-1]["valueKmh"] = 50
        pack = self.dir / "per_edge.vupack"
        manifest_path = self.dir / "per_edge.vupack.manifest.json"
        manifest = pack_build.build_pack(fixture, pack)
        manifest_path.write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        self.assertEqual([], pack_validate.validate(pack, manifest_path))
        self.assertEqual(fmt.MAX_ROAD_LIMITS_PER_EDGE, manifest["roadLimitCount"])

    def test_per_edge_road_limit_budget_rejects_extra_conflicting_evidence(self):
        fixture = json.loads(V2_FIXTURE.read_text(encoding="utf-8"))
        fixture["points"] = []
        fixture["roadLimits"] = [
            {
                "id": f"EDGE_{index:02d}",
                "edgeId": "E_RL1",
                "direction": "BOTH",
                "valueKmh": 60,
                "applicability": "SUPPORTED",
                "confidence": 0.8,
                "provenance": "per-edge bound synthetic",
            }
            for index in range(fmt.MAX_ROAD_LIMITS_PER_EDGE + 1)
        ]
        # The row a silently truncated reader would drop is a real conflict.
        fixture["roadLimits"][-1]["valueKmh"] = 50
        pack = self.dir / "per_edge_overflow.vupack"
        manifest_path = self.dir / "per_edge_overflow.vupack.manifest.json"
        manifest = pack_build.build_pack(fixture, pack)
        manifest_path.write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        errors = pack_validate.validate(pack, manifest_path)
        self.assertTrue(
            any(
                e.startswith("TOO_MANY_ROAD_LIMITS_PER_EDGE:E_RL1:")
                for e in errors
            ),
            errors,
        )

    def test_v1_content_digest_matches_committed_asset(self):
        """The v1 canonical digest path is unchanged by the v2 addition."""
        committed_manifest = json.loads(
            (
                FIXTURES.parents[2]
                / "app/src/main/assets/signs/synthetic_vn_signs_p1.vupack.manifest.json"
            ).read_text(encoding="utf-8")
        )
        fixture = json.loads(V1_FIXTURE.read_text(encoding="utf-8"))
        build = pack_build.build_pack(fixture, self.dir / "asset_v1.vupack")
        self.assertEqual(1, build["schemaVersion"])
        self.assertEqual(
            committed_manifest["contentSha256"], build["contentSha256"]
        )


if __name__ == "__main__":
    unittest.main()
