package vn.vumap.signs;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Source-conflict arbitration tests: typed road/OSM evidence vs explicit P.127.
 *
 * <p>Covers: agreeing sources, conflicting numeric sources, unsupported
 * conditional/lane/vehicle applicability, opposite-direction and unknown-
 * direction abstention, road-only positive controls, expressway suppression,
 * and the untouched legacy (no road evidence) behavior.
 */
public final class RoadLimitArbitrationTest {

    private RoadLimitArbitrationTest() {
    }

    public static int runAll() {
        int passed = 0;
        passed = check("roadOnlySupportedAppliesForward", RoadLimitArbitrationTest::roadOnlySupportedAppliesForward, passed);
        passed = check("roadOnlyZeroConfidenceDoesNotWarn", RoadLimitArbitrationTest::roadOnlyZeroConfidenceDoesNotWarn, passed);
        passed = check("roadOnlyBelowThresholdConfidenceDoesNotWarn", RoadLimitArbitrationTest::roadOnlyBelowThresholdConfidenceDoesNotWarn, passed);
        passed = check("roadOnlyBidirectionalApplies", RoadLimitArbitrationTest::roadOnlyBidirectionalApplies, passed);
        passed = check("roadOnlyBackwardIgnoredForForwardTravel", RoadLimitArbitrationTest::roadOnlyBackwardIgnoredForForwardTravel, passed);
        passed = check("unknownTravelDirectionAbstainsForDirectionalEvidence", RoadLimitArbitrationTest::unknownTravelDirectionAbstainsForDirectionalEvidence, passed);
        passed = check("unknownTravelDirectionAppliesBidirectionalEvidence", RoadLimitArbitrationTest::unknownTravelDirectionAppliesBidirectionalEvidence, passed);
        passed = check("signAndRoadAgreeGiveNumeric", RoadLimitArbitrationTest::signAndRoadAgreeGiveNumeric, passed);
        passed = check("signAndRoadConflictIsAmbiguous", RoadLimitArbitrationTest::signAndRoadConflictIsAmbiguous, passed);
        passed = check("conditionalUnsupportedAbstainsEvenWithSign", RoadLimitArbitrationTest::conditionalUnsupportedAbstainsEvenWithSign, passed);
        passed = check("laneUnsupportedAbstains", RoadLimitArbitrationTest::laneUnsupportedAbstains, passed);
        passed = check("vehicleUnsupportedAbstains", RoadLimitArbitrationTest::vehicleUnsupportedAbstains, passed);
        passed = check("unsupportedRecordAmongSupportedAbstains", RoadLimitArbitrationTest::unsupportedRecordAmongSupportedAbstains, passed);
        passed = check("roadSourceConflictIsAmbiguous", RoadLimitArbitrationTest::roadSourceConflictIsAmbiguous, passed);
        passed = check("expresswayRoadOnlySuppressesWarning", RoadLimitArbitrationTest::expresswayRoadOnlySuppressesWarning, passed);
        passed = check("emptyRoadEvidenceKeepsLegacyBehavior", RoadLimitArbitrationTest::emptyRoadEvidenceKeepsLegacyBehavior, passed);
        passed = check("roadLimitRejectsUnsafeConstruction", RoadLimitArbitrationTest::roadLimitRejectsUnsafeConstruction, passed);
        return passed;
    }

    private static int check(String name, Runnable test, int passed) {
        try {
            test.run();
            System.out.println("PASS RoadLimitArbitrationTest." + name);
            return passed + 1;
        } catch (AssertionError e) {
            System.out.println("FAIL RoadLimitArbitrationTest." + name + " :: " + e.getMessage());
            throw e;
        }
    }

    private static List<RoadLimit> limits(RoadLimit... evidence) {
        return evidence == null ? Collections.<RoadLimit>emptyList() : Arrays.asList(evidence);
    }

    static void roadOnlySupportedAppliesForward() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.UNKNOWN, 0.0f, true,
                CarriagewayType.UNKNOWN, null, 0f, false,
                limits(RoadLimit.supported(
                        "E1", RoadLimit.Source.OSM, "osm-synthetic-v1",
                        RoadLimit.Direction.FORWARD, 50, 0.8f)),
                RoadLimit.Direction.FORWARD);
        Assert.equals(50, advisory.valueKmh(), "EXACT-edge road limit must give a numeric");
        Assert.isTrue(!advisory.suppressed(), "supported road-only evidence is displayable");
        Assert.equals("ROAD_LIMIT_OSM", advisory.reason(), "road-only reason must name the source");
        Assert.equals("OSM", advisory.source(), "source chip must be OSM");
        Assert.isTrue(SpeedResolver.shouldWarnOverspeed(60, advisory), "60 > 50+3 must warn on supported road evidence");
        Assert.isTrue(!SpeedResolver.shouldWarnOverspeed(52, advisory), "hysteresis applies to road evidence too");
    }

    static void roadOnlyZeroConfidenceDoesNotWarn() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.UNKNOWN, 0.0f, true,
                CarriagewayType.UNKNOWN, null, 0f, false,
                limits(RoadLimit.supported(
                        "E1", RoadLimit.Source.OSM, "osm-synthetic-v1",
                        RoadLimit.Direction.FORWARD, 50, 0.0f)),
                RoadLimit.Direction.FORWARD);
        Assert.equals(50, advisory.valueKmh(), "zero-confidence road limit stays displayable");
        Assert.isTrue(advisory.suppressed(), "zero-confidence road evidence must not warn");
        Assert.isTrue(!SpeedResolver.shouldWarnOverspeed(60, advisory), "zero confidence must not warn");
    }

    static void roadOnlyBelowThresholdConfidenceDoesNotWarn() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.UNKNOWN, 0.0f, true,
                CarriagewayType.UNKNOWN, null, 0f, false,
                limits(RoadLimit.supported(
                        "E1", RoadLimit.Source.OSM, "osm-synthetic-v1",
                        RoadLimit.Direction.FORWARD, 50, 0.6f)),
                RoadLimit.Direction.FORWARD);
        Assert.equals(50, advisory.valueKmh(), "below-threshold road limit stays displayable");
        Assert.isTrue(advisory.suppressed(), "below-threshold road evidence must not warn");
        Assert.isTrue(!SpeedResolver.shouldWarnOverspeed(60, advisory), "below-threshold confidence must not warn");
    }

    static void roadOnlyBidirectionalApplies() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.UNKNOWN, 0.0f, true,
                CarriagewayType.UNKNOWN, null, 0f, false,
                limits(RoadLimit.supported(
                        "E1", RoadLimit.Source.OSM, "osm-synthetic-v1",
                        RoadLimit.Direction.BOTH, 80, 0.7f)),
                RoadLimit.Direction.FORWARD);
        Assert.equals(80, advisory.valueKmh(), "bidirectional maxspeed applies to forward travel");
        Assert.equals("ROAD_LIMIT_OSM", advisory.reason(), "bidirectional reason must name the source");
    }

    static void roadOnlyBackwardIgnoredForForwardTravel() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.UNKNOWN, 0.0f, true,
                CarriagewayType.UNKNOWN, null, 0f, false,
                limits(RoadLimit.supported(
                        "E1", RoadLimit.Source.OSM, "osm-synthetic-v1",
                        RoadLimit.Direction.BACKWARD, 50, 0.9f)),
                RoadLimit.Direction.FORWARD);
        Assert.equals(null, advisory.valueKmh(), "opposite-direction road evidence must not be applied");
        Assert.equals("NO_ZONE_EVIDENCE", advisory.reason(), "opposite-direction evidence falls through unchanged");
        Assert.isTrue(advisory.suppressed(), "no numeric means suppressed");
        Assert.isTrue(!SpeedResolver.shouldWarnOverspeed(200, advisory), "opposite-direction evidence never warns");
    }

    static void unknownTravelDirectionAbstainsForDirectionalEvidence() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.INSIDE_DENSE, 0.9f, false,
                CarriagewayType.DUAL_DIVIDED_OR_ONEWAY_2PLUS, null, 0f, false,
                limits(RoadLimit.supported(
                        "E1", RoadLimit.Source.OSM, "osm-synthetic-v1",
                        RoadLimit.Direction.FORWARD, 50, 0.9f)),
                RoadLimit.Direction.UNKNOWN);
        Assert.equals(null, advisory.valueKmh(), "directional road evidence must abstain when travel direction is unknown");
        Assert.equals("ROAD_LIMIT_DIRECTION_AMBIGUOUS", advisory.reason(), "direction ambiguity must be explicit");
        Assert.isTrue(advisory.suppressed(), "direction ambiguity suppresses the numeric");
        Assert.isTrue(!SpeedResolver.shouldWarnOverspeed(120, advisory), "direction ambiguity never warns");
    }

    static void unknownTravelDirectionAppliesBidirectionalEvidence() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.UNKNOWN, 0.0f, true,
                CarriagewayType.UNKNOWN, null, 0f, false,
                limits(RoadLimit.supported(
                        "E1", RoadLimit.Source.OSM, "osm-synthetic-v1",
                        RoadLimit.Direction.BOTH, 50, 0.8f)),
                RoadLimit.Direction.UNKNOWN);
        Assert.equals(50, advisory.valueKmh(), "bidirectional evidence is direction-independent");
        Assert.equals("ROAD_LIMIT_OSM", advisory.reason(), "bidirectional evidence keeps its source reason");
    }

    static void signAndRoadAgreeGiveNumeric() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.INSIDE_DENSE, 0.9f, false,
                CarriagewayType.DUAL_DIVIDED_OR_ONEWAY_2PLUS, 60, 0.9f, false,
                limits(RoadLimit.supported(
                        "E1", RoadLimit.Source.OSM, "osm-synthetic-v1",
                        RoadLimit.Direction.FORWARD, 60, 0.7f)),
                RoadLimit.Direction.FORWARD);
        Assert.equals(60, advisory.valueKmh(), "agreeing sources keep the numeric limit");
        Assert.isTrue(!advisory.suppressed(), "agreeing sources are warnable");
        Assert.equals("ROAD_SIGN_AGREE", advisory.reason(), "agree reason must be explicit");
        Assert.equals("P127+OSM", advisory.source(), "agree source must name both sources");
        Assert.isTrue(SpeedResolver.shouldWarnOverspeed(70, advisory), "agreeing 60 must warn at 70");
    }

    static void signAndRoadConflictIsAmbiguous() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.INSIDE_DENSE, 0.9f, false,
                CarriagewayType.DUAL_DIVIDED_OR_ONEWAY_2PLUS, 60, 0.9f, false,
                limits(RoadLimit.supported(
                        "E1", RoadLimit.Source.OSM, "osm-synthetic-v1",
                        RoadLimit.Direction.FORWARD, 50, 0.9f)),
                RoadLimit.Direction.FORWARD);
        Assert.equals(null, advisory.valueKmh(), "conflicting sources must not produce a numeric");
        Assert.isTrue(advisory.suppressed(), "conflicting sources must be suppressed");
        Assert.equals("ROAD_SIGN_CONFLICT_AMBIGUOUS", advisory.reason(), "conflict reason must be explicit");
        Assert.equals("AMBIGUOUS", advisory.source(), "source chip must be AMBIGUOUS");
        Assert.isTrue(!SpeedResolver.shouldWarnOverspeed(200, advisory), "conflict must never warn");
    }

    static void conditionalUnsupportedAbstainsEvenWithSign() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.INSIDE_DENSE, 0.9f, false,
                CarriagewayType.DUAL_DIVIDED_OR_ONEWAY_2PLUS, 60, 0.9f, false,
                limits(RoadLimit.unsupported(
                        "E1", RoadLimit.Source.OSM, "osm-synthetic-v1",
                        RoadLimit.Direction.FORWARD,
                        RoadLimit.Applicability.CONDITIONAL_UNSUPPORTED)),
                RoadLimit.Direction.FORWARD);
        Assert.equals(null, advisory.valueKmh(), "conditional road tags must not be adjudicated");
        Assert.equals("ROAD_LIMIT_APPLICABILITY_UNSUPPORTED", advisory.reason(), "unsupported applicability must be explicit");
        Assert.isTrue(advisory.suppressed(), "unsupported applicability suppresses any sign numeric");
        Assert.isTrue(!SpeedResolver.shouldWarnOverspeed(200, advisory), "unsupported applicability never warns");
    }

    static void laneUnsupportedAbstains() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.INSIDE_DENSE, 0.9f, false,
                CarriagewayType.DUAL_DIVIDED_OR_ONEWAY_2PLUS, null, 0f, false,
                limits(RoadLimit.unsupported(
                        "E1", RoadLimit.Source.OSM, "osm-synthetic-v1",
                        RoadLimit.Direction.BOTH,
                        RoadLimit.Applicability.LANE_UNSUPPORTED)),
                RoadLimit.Direction.FORWARD);
        Assert.equals(null, advisory.valueKmh(), "lane-specific road tags must not be guessed");
        Assert.equals("ROAD_LIMIT_APPLICABILITY_UNSUPPORTED", advisory.reason(), "lane unsupported must be explicit");
        Assert.isTrue(!SpeedResolver.shouldWarnOverspeed(120, advisory), "lane unsupported never warns");
    }

    static void vehicleUnsupportedAbstains() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.OUTSIDE_DENSE, 0.9f, false,
                CarriagewayType.TWO_WAY_OR_ONEWAY_1LANE, null, 0f, false,
                limits(RoadLimit.unsupported(
                        "E1", RoadLimit.Source.ROAD, "road-synthetic-v1",
                        RoadLimit.Direction.FORWARD,
                        RoadLimit.Applicability.VEHICLE_UNSUPPORTED)),
                RoadLimit.Direction.FORWARD);
        Assert.equals(null, advisory.valueKmh(), "vehicle-specific road tags must not be guessed");
        Assert.equals("ROAD_LIMIT_APPLICABILITY_UNSUPPORTED", advisory.reason(), "vehicle unsupported must be explicit");
    }

    static void unsupportedRecordAmongSupportedAbstains() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.UNKNOWN, 0.0f, true,
                CarriagewayType.UNKNOWN, null, 0f, false,
                limits(
                        RoadLimit.supported(
                                "E1", RoadLimit.Source.OSM, "osm-synthetic-v1",
                                RoadLimit.Direction.FORWARD, 60, 0.9f),
                        RoadLimit.unsupported(
                                "E1", RoadLimit.Source.OSM, "osm-synthetic-v1",
                                RoadLimit.Direction.FORWARD,
                                RoadLimit.Applicability.CONDITIONAL_UNSUPPORTED)),
                RoadLimit.Direction.FORWARD);
        Assert.equals(null, advisory.valueKmh(), "an unsupported record must win over a supported one");
        Assert.equals("ROAD_LIMIT_APPLICABILITY_UNSUPPORTED", advisory.reason(), "unsupported record must be explicit");
    }

    static void roadSourceConflictIsAmbiguous() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.UNKNOWN, 0.0f, true,
                CarriagewayType.UNKNOWN, null, 0f, false,
                limits(
                        RoadLimit.supported(
                                "E1", RoadLimit.Source.OSM, "osm-synthetic-v1",
                                RoadLimit.Direction.FORWARD, 50, 0.9f),
                        RoadLimit.supported(
                                "E1", RoadLimit.Source.ROAD, "road-synthetic-v1",
                                RoadLimit.Direction.BOTH, 80, 0.8f)),
                RoadLimit.Direction.FORWARD);
        Assert.equals(null, advisory.valueKmh(), "conflicting road records must not produce a numeric");
        Assert.equals("ROAD_SOURCE_AMBIGUOUS", advisory.reason(), "road-road conflict must be explicit");
        Assert.equals("AMBIGUOUS", advisory.source(), "road-road conflict source chip must be AMBIGUOUS");
        Assert.isTrue(!SpeedResolver.shouldWarnOverspeed(200, advisory), "road-road conflict never warns");
    }

    static void expresswayRoadOnlySuppressesWarning() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.UNKNOWN, 0.0f, true,
                CarriagewayType.UNKNOWN, null, 0f, true,
                limits(RoadLimit.supported(
                        "E1", RoadLimit.Source.OSM, "osm-synthetic-v1",
                        RoadLimit.Direction.FORWARD, 90, 0.9f)),
                RoadLimit.Direction.FORWARD);
        Assert.equals(90, advisory.valueKmh(), "expressway road limit stays displayable");
        Assert.isTrue(advisory.suppressed(), "expressway road limit must not drive warnings");
        Assert.equals("ROAD_LIMIT_ON_EXPRESSWAY_SUPPRESSED", advisory.reason(), "expressway suppression must be explicit");
        Assert.isTrue(!SpeedResolver.shouldWarnOverspeed(200, advisory), "expressway road limit never warns");
    }

    static void emptyRoadEvidenceKeepsLegacyBehavior() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.INSIDE_DENSE, 0.9f, false,
                CarriagewayType.DUAL_DIVIDED_OR_ONEWAY_2PLUS, null, 0f, false,
                Collections.<RoadLimit>emptyList(),
                RoadLimit.Direction.FORWARD);
        Assert.equals(60, advisory.valueKmh(), "no road evidence keeps the verified zone default");
        Assert.equals("INSIDE_DUAL_60", advisory.reason(), "legacy reason must be unchanged");
        Assert.equals(SpeedResolver.resolve(
                        ZoneState.INSIDE_DENSE, 0.9f, false,
                        CarriagewayType.DUAL_DIVIDED_OR_ONEWAY_2PLUS, null, 0f, false)
                .reason(),
                advisory.reason(),
                "7-arg overload must behave exactly like an empty list");
    }

    static void roadLimitRejectsUnsafeConstruction() {
        try {
            RoadLimit.supported("E1", RoadLimit.Source.OSM, "p", RoadLimit.Direction.FORWARD, 4, 0.9f);
            throw new AssertionError("out-of-range road value must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            RoadLimit.supported("E1", RoadLimit.Source.OSM, "p", RoadLimit.Direction.BOTH, 200, 0.9f);
            throw new AssertionError("out-of-range road value must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            RoadLimit.supported("", RoadLimit.Source.OSM, "p", RoadLimit.Direction.BOTH, 50, 0.9f);
            throw new AssertionError("blank edge must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
