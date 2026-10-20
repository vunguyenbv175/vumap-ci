"""P1 pack pipeline host tests — real temp SQLite files, no flags-only checks.

Covers: build determinism, bounded validation (size/coordinate/duplicate/
checksum/schema/integrity), manifest + in-pack declaration gates, malformed
row-shape rejection without exceptions, checksum gating, atomic install
replacement and rollback-preserves-prior behaviour, install I/O failure
cleanup, post-replacement fault injection (fsync/manifest commit rollback),
staged-snapshot installs (TOCTOU), and bounded staging (oversized input is
rejected after at most cap+1 bytes, never fully copied). Fault-injection tests
install a DISTINCT valid v2 prior pair so a rollback that restores nothing is
detected by byte identity, with a characterization test proving a no-op
restore fails those assertions even when the error marker claims success.

Run: python tools/pack/run_pack_tests.py   (or)   python -m unittest -v
"""

from __future__ import annotations

import json
import os
import shutil
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pack_build  # noqa: E402
import pack_format as fmt  # noqa: E402
import pack_install  # noqa: E402
import pack_validate  # noqa: E402

FIXTURE = Path(__file__).resolve().parents[2] / "fixtures" / "synthetic_vn_signs_p1.json"


def _read_rows(pack: Path):
    connection = sqlite3.connect(pack)
    try:
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
        meta = dict(connection.execute("SELECT key, value FROM pack_manifest").fetchall())
    finally:
        connection.close()
    return edges, points, meta


def _sync(pack: Path, manifest_path: Path, meta_updates=None, manifest_updates=None):
    """Re-sign a tampered pack so only the intended error surfaces."""
    edges, points, meta = _read_rows(pack)
    provenance = {
        "source": meta[fmt.KEY_SOURCE],
        "license": meta[fmt.KEY_LICENSE],
        "version": meta[fmt.KEY_VERSION],
        "synthetic": meta[fmt.KEY_SYNTHETIC] == "true",
    }
    content_sha = fmt.canonical_sha256(edges, points, provenance)
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
    manifest["packBytes"] = pack.stat().st_size
    for key, value in (manifest_updates or {}).items():
        manifest[key] = value
    manifest_path.write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return manifest


def _edit_sql(pack: Path, statement: str, params=()):
    connection = sqlite3.connect(pack)
    try:
        connection.execute(statement, params)
        connection.commit()
    finally:
        connection.close()


class PackPipelineTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="vumap-pack-")
        self.dir = Path(self.tmp.name)
        self.pack = self.dir / "synthetic_vn_signs_p1.vupack"
        self.manifest = self.dir / "synthetic_vn_signs_p1.vupack.manifest.json"
        with open(FIXTURE, "r", encoding="utf-8") as handle:
            self.fixture = json.load(handle)
        pack_build.build_pack(self.fixture, self.pack)
        built = pack_build.build_pack(self.fixture, self.pack)
        self.manifest.write_text(
            json.dumps(built, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )

    def tearDown(self):
        self.tmp.cleanup()

    # ---- build determinism -------------------------------------------------
    def test_build_deterministic_and_valid(self):
        first = self.pack.read_bytes()
        second_manifest = pack_build.build_pack(
            self.fixture, self.dir / "second.vupack"
        )
        second = (self.dir / "second.vupack").read_bytes()
        self.assertEqual(first, second, "same fixture must build byte-identical packs")
        self.assertEqual(
            first, (self.dir / "second.vupack").read_bytes(), "rebuild must be stable"
        )
        self.assertEqual(fmt.sha256_bytes(first), second_manifest["sha256"])
        self.assertEqual(
            pack_validate.validate(self.pack, self.manifest), [],
            "freshly built pack must validate clean",
        )

    # ---- bounded validation ------------------------------------------------
    def test_checksum_mismatch_rejected(self):
        data = bytearray(self.pack.read_bytes())
        data[len(data) // 2] ^= 0xFF
        self.pack.write_bytes(bytes(data))
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertIn("CHECKSUM_MISMATCH", errors)

    def test_empty_and_oversize_rejected(self):
        self.pack.write_bytes(b"")
        self.assertEqual(
            pack_validate.validate(self.pack, self.manifest), ["TRUNCATED_EMPTY_FILE"]
        )
        self.pack.write_bytes(self.pack.read_bytes() + b"\0" * (fmt.MAX_BYTES + 1))
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertTrue(any(e.startswith("TOO_LARGE") for e in errors), errors)

    def test_integrity_check_detects_corruption(self):
        data = self.pack.read_bytes()
        self.pack.write_bytes(data[: (len(data) * 6) // 10])
        # Re-sign only the file-level fields so the checksum gate cannot be the
        # thing that rejects this pack; integrity_check must catch it.
        manifest = json.loads(self.manifest.read_text(encoding="utf-8"))
        manifest["sha256"] = fmt.sha256_file(self.pack)
        manifest["packBytes"] = self.pack.stat().st_size
        self.manifest.write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertTrue(errors, "corrupted pack must be rejected")
        self.assertTrue(
            any("INTEGRITY" in e or "SQLITE" in e for e in errors), errors
        )

    def test_unsupported_schema_rejected(self):
        connection = sqlite3.connect(self.pack)
        connection.execute("PRAGMA user_version = 99")
        connection.commit()
        connection.close()
        _sync(
            self.pack, self.manifest,
            meta_updates={fmt.KEY_SCHEMA_VERSION: "99"},
            manifest_updates={"schemaVersion": 99},
        )
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertIn("UNSUPPORTED_SCHEMA:99", errors)
        self.assertIn("UNSUPPORTED_SCHEMA_META:99", errors)

    def test_out_of_vn_range_rejected(self):
        _edit_sql(self.pack, "UPDATE points SET lat = 40.0 WHERE point_id = 'S01'")
        _sync(self.pack, self.manifest)
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertTrue(any("OUT_OF_VN_RANGE" in e for e in errors), errors)

    def test_bad_sign_values_rejected(self):
        _edit_sql(self.pack, "UPDATE points SET value_kmh = 200 WHERE point_id = 'S02'")
        _sync(self.pack, self.manifest)
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertTrue(any("BAD_P127_VALUE" in e for e in errors), errors)

        _edit_sql(self.pack, "UPDATE points SET value_kmh = 60 WHERE point_id = 'S01'")
        _sync(self.pack, self.manifest)
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertTrue(
            any("ZONE_SIGN_MUST_NOT_CARRY_VALUE" in e for e in errors), errors
        )

    def test_duplicate_location_rejected(self):
        _edit_sql(
            self.pack,
            "UPDATE points SET lat = 10.82312, lon = 106.63010 WHERE point_id = 'S12'",
        )
        _sync(self.pack, self.manifest)
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertTrue(any(e.startswith("DUPLICATE_LOCATION") for e in errors), errors)

    def test_unknown_edge_rejected(self):
        _edit_sql(self.pack, "UPDATE points SET edge_id = 'E_NOPE' WHERE point_id = 'S03'")
        _sync(self.pack, self.manifest)
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertTrue(any("UNKNOWN_EDGE" in e for e in errors), errors)

    def test_missing_manifest_field_rejected(self):
        manifest = json.loads(self.manifest.read_text(encoding="utf-8"))
        del manifest["license"]
        self.manifest.write_text(json.dumps(manifest), encoding="utf-8")
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertIn("MISSING_MANIFEST_FIELD:license", errors)

    # ---- declaration and row-shape gates -----------------------------------
    def test_manifest_version_and_synthetic_declarations_rejected(self):
        pristine = self.manifest.read_bytes()
        cases = (
            ("formatVersion", 99, "UNSUPPORTED_FORMAT_VERSION:99"),
            ("schemaVersion", 99, "UNSUPPORTED_SCHEMA_MANIFEST:99"),
            ("synthetic", False, "MANIFEST_SYNTHETIC_NOT_TRUE"),
        )
        for key, value, expected in cases:
            with self.subTest(key=key, value=value):
                manifest = json.loads(pristine)
                manifest[key] = value
                self.manifest.write_text(
                    json.dumps(manifest, indent=2, sort_keys=True) + "\n",
                    encoding="utf-8",
                )
                errors = pack_validate.validate(self.pack, self.manifest)
                self.assertIn(expected, errors, errors)

    def test_inpack_format_declarations_rejected(self):
        _sync(self.pack, self.manifest, meta_updates={fmt.KEY_FORMAT: "other"})
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertTrue(
            any(e.startswith("PACK_FORMAT_MISMATCH") for e in errors), errors
        )
        _sync(
            self.pack, self.manifest,
            meta_updates={
                fmt.KEY_FORMAT: fmt.FORMAT,
                fmt.KEY_FORMAT_VERSION: "99",
            },
        )
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertIn("UNSUPPORTED_FORMAT_VERSION_META:99", errors)

    def test_non_object_manifest_rejected(self):
        self.manifest.write_text(json.dumps(["not", "an", "object"]), encoding="utf-8")
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertTrue(
            any(e.startswith("MANIFEST_NOT_OBJECT") for e in errors), errors
        )

    def test_textual_sqlite_values_rejected_without_exception(self):
        dest = self.dir / "app-data"
        dest.mkdir()
        self.assertEqual(
            pack_install.install(self.pack, self.manifest, dest), [],
            "valid pair must install before the malformed-input attempt",
        )
        target = dest / pack_install.DEFAULT_PACK_NAME
        target_manifest = dest / f"{pack_install.DEFAULT_PACK_NAME}.manifest.json"
        prior_pack = target.read_bytes()
        prior_manifest = target_manifest.read_bytes()

        # The points table is non-STRICT: text lands in REAL/INTEGER columns.
        _edit_sql(self.pack, "UPDATE points SET lat = 'north' WHERE point_id = 'S02'")
        _edit_sql(self.pack, "UPDATE points SET heading_deg = 'east' WHERE point_id = 'S01'")
        _edit_sql(self.pack, "UPDATE points SET value_kmh = 'sixty' WHERE point_id = 'S02'")
        # Re-sign ONLY the file-level checksum: the content digest is skipped
        # for malformed rows and the row bytes are not otherwise re-hashed here.
        manifest = json.loads(self.manifest.read_text(encoding="utf-8"))
        manifest["sha256"] = fmt.sha256_file(self.pack)
        manifest["packBytes"] = self.pack.stat().st_size
        self.manifest.write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )

        # Must return deterministic rejection errors, never raise.
        errors = pack_validate.validate(self.pack, self.manifest)
        self.assertTrue(any("BAD_COORD_TYPE" in e for e in errors), errors)
        self.assertTrue(any("BAD_HEADING_TYPE" in e for e in errors), errors)
        self.assertTrue(any("BAD_P127_VALUE" in e for e in errors), errors)

        install_errors = pack_install.install(self.pack, self.manifest, dest)
        self.assertTrue(install_errors, "malformed pack must be rejected by install")
        self.assertEqual(
            target.read_bytes(), prior_pack,
            "rejected install must not change the installed pack",
        )
        self.assertEqual(
            target_manifest.read_bytes(), prior_manifest,
            "rejected install must not change the installed manifest",
        )
        leftovers = [p.name for p in dest.iterdir() if p.name.startswith(".")]
        self.assertEqual(leftovers, [], "rejected install must not leave temp files")

    # ---- atomic install contract ------------------------------------------
    def test_install_success_replaces_and_validates(self):
        dest = self.dir / "app-data"
        dest.mkdir()
        errors = pack_install.install(self.pack, self.manifest, dest)
        self.assertEqual(errors, [])
        target = dest / pack_install.DEFAULT_PACK_NAME
        self.assertEqual(target.read_bytes(), self.pack.read_bytes())
        self.assertEqual(
            pack_validate.validate(target, dest / f"{pack_install.DEFAULT_PACK_NAME}.manifest.json"), []
        )
        leftovers = [p.name for p in dest.iterdir() if p.name.startswith(".")]
        self.assertEqual(leftovers, [], "no temp artifacts after install")

    def test_install_rejection_preserves_prior_pack(self):
        dest = self.dir / "app-data"
        dest.mkdir()
        self.assertEqual(pack_install.install(self.pack, self.manifest, dest), [])
        target = dest / pack_install.DEFAULT_PACK_NAME
        target_manifest = dest / f"{pack_install.DEFAULT_PACK_NAME}.manifest.json"
        prior_bytes = target.read_bytes()
        prior_sha = fmt.sha256_file(target)
        prior_manifest_bytes = target_manifest.read_bytes()

        data = bytearray(self.pack.read_bytes())
        data[len(data) // 2] ^= 0xFF
        bad_pack = self.dir / "bad.vupack"
        bad_pack.write_bytes(bytes(data))
        errors = pack_install.install(bad_pack, self.manifest, dest)
        self.assertIn("CHECKSUM_MISMATCH", errors)
        self.assertEqual(target.read_bytes(), prior_bytes, "prior pack must be untouched")
        self.assertEqual(fmt.sha256_file(target), prior_sha)
        self.assertEqual(
            target_manifest.read_bytes(), prior_manifest_bytes,
            "prior manifest must be untouched",
        )
        leftovers = [p.name for p in dest.iterdir() if p.name.startswith(".")]
        self.assertEqual(leftovers, [], "rejected install must not leave temp files")

    def test_install_replaces_valid_pack_and_keeps_previous(self):
        dest = self.dir / "app-data"
        dest.mkdir()
        self.assertEqual(pack_install.install(self.pack, self.manifest, dest), [])
        prior_bytes = (dest / pack_install.DEFAULT_PACK_NAME).read_bytes()

        v2_pack, v2_manifest = self._build_v2_pair()
        self.assertEqual(pack_install.install(v2_pack, v2_manifest, dest), [])
        target = dest / pack_install.DEFAULT_PACK_NAME
        self.assertEqual(target.read_bytes(), v2_pack.read_bytes())
        self.assertNotEqual(target.read_bytes(), prior_bytes)
        previous = dest / f"{pack_install.DEFAULT_PACK_NAME}.previous"
        self.assertTrue(previous.exists(), "previous pack retained for rollback")
        self.assertEqual(previous.read_bytes(), prior_bytes)

    def test_install_io_error_leaves_prior_and_cleans_temp(self):
        dest = self.dir / "app-data"
        dest.mkdir()
        self.assertEqual(pack_install.install(self.pack, self.manifest, dest), [])
        target = dest / pack_install.DEFAULT_PACK_NAME
        prior_bytes = target.read_bytes()

        with mock.patch("os.replace", side_effect=OSError("simulated failure")):
            errors = pack_install.install(self.pack, self.manifest, dest)
        self.assertTrue(any(e.startswith("INSTALL_IO_ERROR") for e in errors), errors)
        self.assertEqual(target.read_bytes(), prior_bytes, "prior pack must survive I/O error")
        leftovers = [p.name for p in dest.iterdir() if p.name.startswith(".")]
        self.assertEqual(leftovers, [], "failed install must clean its temp file")

    def test_install_rejects_missing_dest_dir(self):
        errors = pack_install.install(self.pack, self.manifest, self.dir / "nope")
        self.assertTrue(any(e.startswith("DEST_DIR_MISSING") for e in errors), errors)

    # ---- post-replacement fault injection (rollback contract) --------------
    def _build_v2_pair(self) -> tuple[Path, Path]:
        """Build a valid v2 pair whose bytes differ from the v1 self.pack pair."""
        v2_fixture = json.loads(json.dumps(self.fixture))
        for point in v2_fixture["points"]:
            if point["id"] == "S02":
                point["confidence"] = 0.86
        v2_pack = self.dir / "v2.vupack"
        v2_manifest = self.dir / "v2.vupack.manifest.json"
        v2 = pack_build.build_pack(v2_fixture, v2_pack)
        v2_manifest.write_text(json.dumps(v2, indent=2, sort_keys=True) + "\n")
        return v2_pack, v2_manifest

    def _install_prior_pair(self):
        """Install a DISTINCT valid v2 pair; return dest + byte snapshots.

        The fault-injection tests then attempt a v1 install: because the
        incoming bytes differ from the installed bytes, a rollback that does
        not actually restore is caught by byte identity (with an identical
        self-reinstall, a no-op restore would pass every byte assertion)."""
        dest = self.dir / "app-data"
        dest.mkdir(exist_ok=True)
        v2_pack, v2_manifest = self._build_v2_pair()
        self.assertEqual(
            pack_install.install(v2_pack, v2_manifest, dest), [],
            "distinct v2 prior pair must install",
        )
        target = dest / pack_install.DEFAULT_PACK_NAME
        target_manifest = dest / f"{pack_install.DEFAULT_PACK_NAME}.manifest.json"
        return (
            dest,
            target,
            target_manifest,
            target.read_bytes(),
            target_manifest.read_bytes(),
        )

    def _assert_pair_restored(
        self, dest, target, target_manifest, prior_pack, prior_manifest, errors,
        msg="", incoming_pack=None,
    ):
        self.assertTrue(
            any(e.startswith("INSTALL_IO_ERROR") for e in errors), errors
        )
        self.assertEqual(
            target.read_bytes(), prior_pack,
            f"previous pack must remain byte-identical {msg}",
        )
        self.assertEqual(
            target_manifest.read_bytes(), prior_manifest,
            f"previous manifest must remain byte-identical {msg}",
        )
        if incoming_pack is not None:
            self.assertNotEqual(
                target.read_bytes(), incoming_pack,
                f"incoming bytes must NOT remain under the target {msg} — "
                "with distinct pairs this is what detects a missing restore",
            )
        leftovers = [p.name for p in dest.iterdir() if p.name.startswith(".")]
        self.assertEqual(leftovers, [], "failed install must clean its temp files")

    def test_install_post_replace_pack_fsync_failure_rolls_back_pair(self):
        dest, target, target_manifest, prior_pack, prior_manifest = (
            self._install_prior_pair()
        )
        real_fsync = pack_install._fsync_file

        def flaky(path):
            if Path(path) == target:
                raise OSError("simulated failure fsyncing the replaced pack")
            real_fsync(path)

        with mock.patch.object(pack_install, "_fsync_file", side_effect=flaky):
            errors = pack_install.install(self.pack, self.manifest, dest)
        self._assert_pair_restored(
            dest, target, target_manifest, prior_pack, prior_manifest, errors,
            "after post-replace pack fsync failure",
            incoming_pack=self.pack.read_bytes(),
        )
        self.assertIn("PRIOR_PAIR_RESTORED", errors)

    def test_install_post_replace_manifest_fsync_failure_rolls_back_pair(self):
        dest, target, target_manifest, prior_pack, prior_manifest = (
            self._install_prior_pair()
        )
        real_fsync = pack_install._fsync_file

        def flaky(path):
            if Path(path) == target_manifest:
                raise OSError("simulated failure fsyncing the replaced manifest")
            real_fsync(path)

        with mock.patch.object(pack_install, "_fsync_file", side_effect=flaky):
            errors = pack_install.install(self.pack, self.manifest, dest)
        self._assert_pair_restored(
            dest, target, target_manifest, prior_pack, prior_manifest, errors,
            "after post-replace manifest fsync failure",
            incoming_pack=self.pack.read_bytes(),
        )
        self.assertIn("PRIOR_PAIR_RESTORED", errors)

    def test_install_manifest_replace_failure_rolls_back_pack(self):
        """Read-only installed manifest equivalent: the manifest commit fails
        AFTER the pack was already replaced — the pack must roll back."""
        dest, target, target_manifest, prior_pack, prior_manifest = (
            self._install_prior_pair()
        )
        real_replace = os.replace

        def guarded(src, dst):
            if ".stage-manifest-" in Path(src).name:
                raise PermissionError("simulated read-only manifest destination")
            return real_replace(src, dst)

        with mock.patch.object(pack_install.os, "replace", side_effect=guarded):
            errors = pack_install.install(self.pack, self.manifest, dest)
        self._assert_pair_restored(
            dest, target, target_manifest, prior_pack, prior_manifest, errors,
            "after the manifest commit failed mid-replacement",
            incoming_pack=self.pack.read_bytes(),
        )
        self.assertIn("PRIOR_PAIR_RESTORED", errors)

    def test_distinct_prior_pair_detects_missing_restore(self):
        """Characterization of the fault-injection tests' detection power:
        with a DISTINCT installed pair, a rollback that restores nothing is
        caught by byte identity — even though the error list still claims
        PRIOR_PAIR_RESTORED (the marker alone is emitted whenever no restore
        ERROR occurred, so it must never be treated as proof of restoration)."""
        dest, target, target_manifest, prior_pack, prior_manifest = (
            self._install_prior_pair()
        )
        self.assertNotEqual(
            prior_pack, self.pack.read_bytes(),
            "fixture sanity: the installed v2 pair must differ from incoming v1",
        )
        real_fsync = pack_install._fsync_file

        def flaky(path):
            if Path(path) == target:
                raise OSError("simulated failure fsyncing the replaced pack")
            real_fsync(path)

        with mock.patch.object(pack_install, "_fsync_file", side_effect=flaky), \
                mock.patch.object(pack_install, "_restore", return_value=None):
            errors = pack_install.install(self.pack, self.manifest, dest)

        self.assertTrue(any(e.startswith("INSTALL_IO_ERROR") for e in errors), errors)
        self.assertIn(
            "PRIOR_PAIR_RESTORED", errors,
            "marker still claims success — this is exactly why byte identity with "
            "distinct pairs is the real assertion",
        )
        self.assertNotEqual(
            target.read_bytes(), prior_pack,
            "a no-op restore leaves the INCOMING pack under the target; the "
            "fault-injection tests must detect that",
        )

    def test_install_commits_validated_staged_snapshot_not_late_source(self):
        """Concurrent rebuild of the SOURCE after validation must not leak
        unvalidated bytes into the installed pack (TOCTOU regression)."""
        dest = self.dir / "app-data"
        dest.mkdir()
        original_pack = self.pack.read_bytes()
        original_manifest = self.manifest.read_bytes()

        real_validate = pack_validate.validate

        def mutating_validate(pack, manifest):
            errors = real_validate(pack, manifest)
            mutated = bytearray(original_pack)
            mutated[len(mutated) // 2] ^= 0xFF
            self.pack.write_bytes(bytes(mutated))
            self.manifest.write_text(
                json.dumps({"sha256": "deadbeef"}), encoding="utf-8"
            )
            return errors

        with mock.patch.object(
            pack_validate, "validate", side_effect=mutating_validate
        ):
            errors = pack_install.install(self.pack, self.manifest, dest)

        self.assertEqual(errors, [], errors)
        target = dest / pack_install.DEFAULT_PACK_NAME
        target_manifest = dest / f"{pack_install.DEFAULT_PACK_NAME}.manifest.json"
        self.assertEqual(
            target.read_bytes(), original_pack,
            "installed pack must be the validated snapshot, not mutated source",
        )
        self.assertEqual(
            target_manifest.read_bytes(), original_manifest,
            "installed manifest must be the validated snapshot, not mutated source",
        )

    # ---- bounded staging (oversized input cannot fill the destination) -----
    def _write_oversized_pack(self, name: str) -> tuple[Path, Path]:
        big_pack = self.dir / name
        with open(big_pack, "wb") as handle:
            handle.write(b"\0" * (fmt.MAX_BYTES + 4096))
        big_manifest = self.dir / f"{name}.manifest.json"
        big_manifest.write_bytes(self.manifest.read_bytes())
        return big_pack, big_manifest

    def test_stage_copy_stops_at_size_cap(self):
        src = self.dir / "oversized.vupack"
        with open(src, "wb") as handle:
            handle.write(b"\0" * (fmt.MAX_BYTES + 4096))
        dst = self.dir / "staged.vupack"
        copied = pack_install._stage_copy(src, dst, fmt.MAX_BYTES)
        self.assertEqual(copied, fmt.MAX_BYTES + 1, "bounded copy must stop at cap+1")
        self.assertEqual(dst.stat().st_size, fmt.MAX_BYTES + 1)
        self.assertLess(
            dst.stat().st_size, src.stat().st_size,
            "an oversized source must never be fully staged",
        )

    def test_install_oversized_input_rejected_early(self):
        dest, target, target_manifest, prior_pack, prior_manifest = (
            self._install_prior_pair()
        )
        big_pack, big_manifest = self._write_oversized_pack("big.vupack")

        errors = pack_install.install(big_pack, big_manifest, dest)
        self.assertTrue(any(e.startswith("TOO_LARGE") for e in errors), errors)
        self.assertEqual(
            target.read_bytes(), prior_pack, "prior pack must be byte-identical"
        )
        self.assertEqual(
            target_manifest.read_bytes(), prior_manifest,
            "prior manifest must be byte-identical",
        )
        leftovers = [p.name for p in dest.iterdir() if p.name.startswith(".")]
        self.assertEqual(leftovers, [], "oversized rejection must leave no stage files")

    def test_install_oversized_input_rejected_even_when_stat_underreports(self):
        """The preliminary stat is only a fast path: if the source grows after
        it, the bounded copy still stops at cap+1 and the install rejects."""
        dest, target, target_manifest, prior_pack, prior_manifest = (
            self._install_prior_pair()
        )
        big_pack, big_manifest = self._write_oversized_pack("grown.vupack")

        with mock.patch.object(pack_install, "_input_size", return_value=1):
            errors = pack_install.install(big_pack, big_manifest, dest)
        self.assertTrue(any(e.startswith("TOO_LARGE") for e in errors), errors)
        self.assertEqual(
            target.read_bytes(), prior_pack, "prior pack must be byte-identical"
        )
        self.assertEqual(
            target_manifest.read_bytes(), prior_manifest,
            "prior manifest must be byte-identical",
        )
        leftovers = [p.name for p in dest.iterdir() if p.name.startswith(".")]
        self.assertEqual(leftovers, [], "oversized rejection must leave no stage files")


if __name__ == "__main__":
    unittest.main(verbosity=2)
