"""Host tests for the deterministic OSM road-evidence adapter (stdlib only).

Covers the narrow parsing contract: explicit maxspeed and directional
forward/backward evidence, abstention for conditional/lane/vehicle tags,
no unit conversion, no value clamping, directional abstention for
``oneway=-1``, conservative carriageway derivation, deterministic ordering
and rebuild, and the committed compact real OSM extract (exact provenance
checked against its sidecar).

Run: python tools/pack/run_pack_tests.py   (or)   python -m unittest -v
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import osm_road_limits as osm  # noqa: E402
import pack_build  # noqa: E402
import pack_format as fmt  # noqa: E402
import pack_validate  # noqa: E402

FIXTURES = Path(__file__).resolve().parents[2] / "fixtures"
REAL_EXTRACT = FIXTURES / "osm" / "hanoi_maxspeed_bbox_osm.json"
REAL_PROVENANCE = FIXTURES / "osm" / "hanoi_maxspeed_bbox_osm.provenance.json"

PROVENANCE = {
    "source": "synthetic-osm-test",
    "license": "synthetic-test-only",
    "version": "test-20261020",
    "attribution": "synthetic fixture, no real provider",
}


def way(way_id, tags, geometry=None):
    if geometry is None:
        geometry = [
            {"lat": 10.82310, "lon": 106.62970},
            {"lat": 10.82330, "lon": 106.63060},
        ]
    return {"type": "way", "id": way_id, "tags": tags, "geometry": geometry}


def adapt(elements, **overrides):
    kwargs = dict(PROVENANCE)
    kwargs.update(overrides)
    return osm.adapt({"elements": elements}, **kwargs)


def rows_by_id(fixture):
    return {row["id"]: row for row in fixture["roadLimits"]}


class OsmRoadLimitAdapterTest(unittest.TestCase):
    def test_explicit_and_directional_tags(self):
        fixture, report = adapt(
            [
                way(1, {"highway": "primary", "maxspeed": "50"}),
                way(2, {"highway": "primary", "maxspeed:forward": "60"}),
                way(3, {"highway": "primary", "maxspeed:backward": "50"}),
            ]
        )
        rows = rows_by_id(fixture)
        self.assertEqual("BOTH", rows["OSM_W1:maxspeed:BOTH"]["direction"])
        self.assertEqual(50, rows["OSM_W1:maxspeed:BOTH"]["valueKmh"])
        self.assertEqual("FORWARD", rows["OSM_W2:maxspeed:forward:FORWARD"]["direction"])
        self.assertEqual(50, rows["OSM_W3:maxspeed:backward:BACKWARD"]["valueKmh"])
        self.assertEqual(
            "BACKWARD", rows["OSM_W3:maxspeed:backward:BACKWARD"]["direction"]
        )
        self.assertTrue(all(r["applicability"] == "SUPPORTED" for r in rows.values()))
        self.assertEqual(3, report["supported"])
        self.assertEqual([], report["skipped"])
        self.assertEqual(3, len(fixture["edges"]))

    def test_unsupported_tags_abstain_without_a_value(self):
        fixture, report = adapt(
            [
                way(
                    10,
                    {
                        "highway": "secondary",
                        "maxspeed:conditional": "40 @ (22:00-06:00)",
                        "maxspeed:lanes": "50|60",
                        "maxspeed:hgv": "40",
                    },
                )
            ]
        )
        rows = rows_by_id(fixture)
        self.assertEqual(3, len(rows))
        self.assertEqual(
            "CONDITIONAL_UNSUPPORTED",
            rows["OSM_W10:maxspeed:conditional:BOTH"]["applicability"],
        )
        self.assertEqual(
            "LANE_UNSUPPORTED", rows["OSM_W10:maxspeed:lanes:BOTH"]["applicability"]
        )
        self.assertEqual(
            "VEHICLE_UNSUPPORTED", rows["OSM_W10:maxspeed:hgv:BOTH"]["applicability"]
        )
        # Unsupported rules must never carry a numeric value.
        self.assertTrue(all(row["valueKmh"] is None for row in rows.values()))
        self.assertEqual(3, report["unsupported"])
        self.assertEqual(0, report["supported"])

    def test_unsupported_units_symbolic_and_out_of_range_are_skipped(self):
        fixture, report = adapt(
            [
                way(
                    20,
                    {
                        "highway": "tertiary",
                        "maxspeed": "60 mph",
                        "maxspeed:forward": "walk",
                        "maxspeed:backward": "4",
                    },
                ),
                way(
                    21,
                    {
                        "highway": "tertiary",
                        "maxspeed": "50;60",
                    },
                ),
                way(
                    22,
                    {
                        "highway": "tertiary",
                        "maxspeed": "130",
                    },
                ),
                way(
                    23,
                    {
                        "highway": "tertiary",
                        "maxspeed": "50 mph",
                    },
                ),
            ]
        )
        self.assertEqual([], fixture["roadLimits"])
        self.assertEqual([], fixture["edges"])
        reasons = {(row["wayId"], row["tag"]): row["reason"] for row in report["skipped"]}
        self.assertEqual("UNSUPPORTED_UNIT", reasons[("20", "maxspeed")])
        self.assertEqual("SYMBOLIC_VALUE", reasons[("20", "maxspeed:forward")])
        self.assertEqual("OUT_OF_RANGE", reasons[("20", "maxspeed:backward")])
        self.assertEqual("UNSUPPORTED_VALUE_SYNTAX", reasons[("21", "maxspeed")])
        self.assertEqual("OUT_OF_RANGE", reasons[("22", "maxspeed")])
        self.assertEqual("UNSUPPORTED_UNIT", reasons[("23", "maxspeed")])

    def test_kmh_suffix_and_spacing_accepted(self):
        fixture, report = adapt(
            [
                way(30, {"highway": "primary", "maxspeed": "50 km/h"}),
                way(31, {"highway": "primary", "maxspeed": " 60kmh "}),
                way(32, {"highway": "primary", "maxspeed": "80 KPH"}),
            ]
        )
        values = {row["edgeId"]: row["valueKmh"] for row in fixture["roadLimits"]}
        self.assertEqual({"OSM_W30": 50, "OSM_W31": 60, "OSM_W32": 80}, values)
        self.assertEqual([], report["skipped"])

    def test_oneway_minus_one_directional_tags_abstain(self):
        fixture, _ = adapt(
            [
                way(
                    40,
                    {
                        "highway": "primary",
                        "oneway": "-1",
                        "maxspeed": "50",
                        "maxspeed:forward": "60",
                        "maxspeed:backward": "40",
                    },
                )
            ]
        )
        rows = rows_by_id(fixture)
        self.assertEqual("BOTH", rows["OSM_W40:maxspeed:BOTH"]["direction"])
        self.assertEqual(50, rows["OSM_W40:maxspeed:BOTH"]["valueKmh"])
        # Direction relative to the way geometry is reversed; never guess it.
        self.assertEqual("UNKNOWN", rows["OSM_W40:maxspeed:forward:UNKNOWN"]["direction"])
        self.assertEqual("UNKNOWN", rows["OSM_W40:maxspeed:backward:UNKNOWN"]["direction"])
        self.assertEqual(60, rows["OSM_W40:maxspeed:forward:UNKNOWN"]["valueKmh"])

    def test_carriageway_is_never_guessed(self):
        fixture, _ = adapt(
            [
                way(50, {"highway": "primary", "oneway": "yes", "lanes": "2", "maxspeed": "50"}),
                way(51, {"highway": "primary", "oneway": "yes", "lanes": "1", "maxspeed": "50"}),
                way(52, {"highway": "primary", "oneway": "yes", "maxspeed": "50"}),
                way(53, {"highway": "primary", "oneway": "no", "maxspeed": "50"}),
                way(54, {"highway": "primary", "maxspeed": "50"}),
            ]
        )
        carriageways = {edge["id"]: edge["carriageway"] for edge in fixture["edges"]}
        self.assertEqual(
            "DUAL_DIVIDED_OR_ONEWAY_2PLUS", carriageways["OSM_W50"]
        )
        self.assertEqual("TWO_WAY_OR_ONEWAY_1LANE", carriageways["OSM_W51"])
        self.assertEqual("UNKNOWN", carriageways["OSM_W52"])
        self.assertEqual("TWO_WAY_OR_ONEWAY_1LANE", carriageways["OSM_W53"])
        self.assertEqual("UNKNOWN", carriageways["OSM_W54"])

    def test_heading_from_geometry_and_missing_geometry(self):
        fixture, _ = adapt(
            [
                way(
                    60,
                    {"highway": "primary", "maxspeed": "50"},
                    geometry=[
                        {"lat": 10.0, "lon": 106.0},
                        {"lat": 10.0, "lon": 106.1},
                    ],
                ),
                way(61, {"highway": "primary", "maxspeed": "50"}, geometry=[]),
            ]
        )
        headings = {edge["id"]: edge["headingDeg"] for edge in fixture["edges"]}
        self.assertAlmostEqual(90.0, headings["OSM_W60"], delta=0.05)
        self.assertIsNone(headings["OSM_W61"])

    def test_closed_and_degenerate_geometry_leave_heading_unknown(self):
        closed_ring = [
            {"lat": 10.0, "lon": 106.0},
            {"lat": 10.01, "lon": 106.0},
            {"lat": 10.01, "lon": 106.01},
            {"lat": 10.0, "lon": 106.0},
        ]
        zero_length = [
            {"lat": 10.0, "lon": 106.0},
            {"lat": 10.0, "lon": 106.0},
        ]
        repeated_first_point = [
            {"lat": 10.0, "lon": 106.0},
            {"lat": 10.0, "lon": 106.0},
            {"lat": 10.0, "lon": 106.1},
        ]
        fixture, _ = adapt(
            [
                way(62, {"highway": "primary", "maxspeed:forward": "50"}, geometry=closed_ring),
                way(63, {"highway": "primary", "maxspeed:forward": "50"}, geometry=zero_length),
                way(64, {"highway": "primary", "maxspeed": "50"}, geometry=repeated_first_point),
            ]
        )
        headings = {edge["id"]: edge["headingDeg"] for edge in fixture["edges"]}
        # Coincident endpoints carry no travel bearing: never invent north.
        self.assertIsNone(headings["OSM_W62"])
        self.assertIsNone(headings["OSM_W63"])
        # A repeated interior/start point is harmless when the endpoints differ.
        self.assertAlmostEqual(90.0, headings["OSM_W64"], delta=0.05)

    def test_conflicting_same_edge_values_are_kept_for_the_resolver(self):
        fixture, _ = adapt(
            [
                way(
                    70,
                    {
                        "highway": "primary",
                        "maxspeed": "50",
                        "maxspeed:forward": "60",
                    },
                )
            ]
        )
        directions = sorted(row["direction"] for row in fixture["roadLimits"])
        self.assertEqual(["BOTH", "FORWARD"], directions)
        # Two applicable values on one edge must reach the resolver so it can
        # abstain (ROAD_SOURCE_AMBIGUOUS) instead of the adapter picking one.
        self.assertEqual(
            [50, 60], sorted(row["valueKmh"] for row in fixture["roadLimits"])
        )

    def test_unknown_qualifier_is_skipped_not_mislabelled(self):
        fixture, report = adapt(
            [
                way(
                    80,
                    {
                        "highway": "primary",
                        "maxspeed:advisory": "40",
                        "maxspeed": "50",
                    },
                )
            ]
        )
        self.assertEqual(["OSM_W80:maxspeed:BOTH"], [row["id"] for row in fixture["roadLimits"]])
        self.assertEqual(
            "UNSUPPORTED_TAG",
            report["skipped"][0]["reason"],
        )

    def test_input_order_does_not_change_the_fixture(self):
        elements = [
            way(90, {"highway": "primary", "maxspeed": "50"}),
            way(91, {"highway": "primary", "maxspeed:forward": "60"}),
            way(92, {"highway": "primary", "maxspeed:hgv": "40"}),
        ]
        first, _ = adapt(elements)
        second, _ = adapt(list(reversed(elements)))
        self.assertEqual(
            json.dumps(first, sort_keys=True, ensure_ascii=False),
            json.dumps(second, sort_keys=True, ensure_ascii=False),
        )

    def test_duplicate_way_elements_are_deduplicated(self):
        elements = [
            way(95, {"highway": "primary", "maxspeed": "50"}),
            way(95, {"highway": "primary", "maxspeed": "70"}),
        ]
        fixture, report = adapt(elements)
        self.assertEqual(1, len(fixture["edges"]))
        self.assertEqual(1, len(fixture["roadLimits"]))
        self.assertEqual(1, report["ways"])

    def test_non_way_elements_are_ignored(self):
        fixture, report = adapt(
            [
                {"type": "node", "id": 1, "tags": {"maxspeed": "50"}},
                {"type": "relation", "id": 2, "tags": {"maxspeed": "50"}},
                way(96, {"highway": "primary", "maxspeed": "50"}),
            ]
        )
        self.assertEqual(1, len(fixture["edges"]))
        self.assertEqual(1, report["ways"])

    def test_provenance_and_confidence_are_required(self):
        with self.assertRaises(ValueError):
            osm.adapt({"elements": []}, **{**PROVENANCE, "source": ""})
        with self.assertRaises(ValueError):
            osm.adapt({"elements": []}, **{**PROVENANCE, "confidence": 1.5})
        with self.assertRaises(ValueError):
            osm.adapt({}, **PROVENANCE)

    def test_adapter_output_builds_and_validates_as_a_v2_pack(self):
        fixture, _ = adapt(
            [
                way(100, {"highway": "primary", "oneway": "yes", "lanes": "2", "maxspeed": "60"}),
                way(
                    101,
                    {
                        "highway": "secondary",
                        "maxspeed:conditional": "40 @ (22:00-06:00)",
                        "maxspeed:forward": "80",
                    },
                ),
            ],
            synthetic=True,
        )
        with tempfile.TemporaryDirectory(prefix="vumap-osm-") as tmp:
            tmp_path = Path(tmp)
            fixture_path = tmp_path / "osm_fixture.json"
            fixture_path.write_text(
                json.dumps(fixture, indent=2, sort_keys=True) + "\n", encoding="utf-8"
            )
            loaded = pack_build.load_fixture(fixture_path)
            pack_path = tmp_path / "osm.vupack"
            manifest_path = tmp_path / "osm.vupack.manifest.json"
            manifest = pack_build.build_pack(loaded, pack_path)
            manifest_path.write_text(
                json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
            )
            self.assertEqual(2, manifest["schemaVersion"])
            self.assertEqual(3, manifest["roadLimitCount"])
            self.assertEqual([], pack_validate.validate(pack_path, manifest_path))

    def test_real_extract_cannot_be_built_as_a_synthetic_pack(self):
        document = json.loads(REAL_EXTRACT.read_text(encoding="utf-8"))
        fixture, _ = osm.adapt(
            document,
            source="OpenStreetMap",
            license="ODbL-1.0",
            version="osm-base 2026-10-20T08:15:48Z",
            attribution="(c) OpenStreetMap contributors",
            synthetic=False,
        )
        with tempfile.TemporaryDirectory(prefix="vumap-osm-real-") as tmp:
            fixture_path = Path(tmp) / "real.json"
            fixture_path.write_text(json.dumps(fixture), encoding="utf-8")
            with self.assertRaises(SystemExit):
                pack_build.load_fixture(fixture_path)


class RealRegionalSampleTest(unittest.TestCase):
    """The committed compact real extract keeps exact provenance and parses."""

    def test_real_extract_parses_with_exact_provenance(self):
        document = json.loads(REAL_EXTRACT.read_text(encoding="utf-8"))
        sidecar = json.loads(REAL_PROVENANCE.read_text(encoding="utf-8"))
        fixture, report = osm.adapt(
            document,
            source=sidecar["source"],
            license=sidecar["license"],
            version=f"osm-base {sidecar['osmBaseTimestamp']}",
            attribution=sidecar["attribution"],
            synthetic=False,
        )
        self.assertFalse(fixture["provenance"]["synthetic"])
        self.assertEqual("OpenStreetMap", fixture["provenance"]["source"])
        self.assertEqual("ODbL-1.0", fixture["provenance"]["license"])
        self.assertIn("osmBaseTimestamp", sidecar)
        self.assertEqual(sidecar["osmBaseTimestamp"], report["osmBase"])
        self.assertGreater(len(fixture["edges"]), 0)
        self.assertGreater(len(fixture["roadLimits"]), 0)
        values = [row["valueKmh"] for row in fixture["roadLimits"]]
        self.assertTrue(all(5 <= value <= 120 for value in values))
        self.assertTrue(
            all(row["applicability"] == "SUPPORTED" for row in fixture["roadLimits"])
        )
        # Every row keeps the exact OSM way id and provenance string.
        way_ids = {str(element["id"]) for element in document["elements"]}
        for row in fixture["roadLimits"]:
            stem = row["edgeId"].split("OSM_W", 1)[1]
            self.assertIn(stem, way_ids)
            self.assertIn("OpenStreetMap", row["provenance"])
        self.assertEqual([], report["skipped"])

    def test_real_extract_values_are_never_converted(self):
        document = json.loads(REAL_EXTRACT.read_text(encoding="utf-8"))
        for element in document["elements"]:
            if str(element.get("tags", {}).get("maxspeed", "")).lower().endswith("mph"):
                self.fail("committed real sample must not contain mph values")
        sidecar = json.loads(REAL_PROVENANCE.read_text(encoding="utf-8"))
        self.assertEqual(
            "OpenStreetMap", sidecar["source"]
        )
        self.assertIn("openstreetmap.org/copyright", sidecar["attribution"])


if __name__ == "__main__":
    unittest.main()
