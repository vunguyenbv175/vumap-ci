package vn.vumap.signs;

import java.util.Arrays;
import java.util.Collections;

/** Map-match negative + continuity tests (adjudication B, P1 gate 5 slice). */
public final class MapMatcherTest {

    private MapMatcherTest() {
    }

    public static int runAll() {
        int passed = 0;
        passed = check("parallelSameDirectionAbstains", MapMatcherTest::parallelSameDirectionAbstains, passed);
        passed = check("bridgeLevelAbstainsWithoutConnectivity", MapMatcherTest::bridgeLevelAbstainsWithoutConnectivity, passed);
        passed = check("connectivitySelectsCorrectDeck", MapMatcherTest::connectivitySelectsCorrectDeck, passed);
        passed = check("gpsJumpAbstains", MapMatcherTest::gpsJumpAbstains, passed);
        passed = check("noFixAbstains", MapMatcherTest::noFixAbstains, passed);
        passed = check("hysteresisKeepsPrevious", MapMatcherTest::hysteresisKeepsPrevious, passed);
        passed = check("hysteresisNeverKeepsBelowFloor", MapMatcherTest::hysteresisNeverKeepsBelowFloor, passed);
        passed = check("nonFiniteMeasurementsAbstain", MapMatcherTest::nonFiniteMeasurementsAbstain, passed);
        passed = check("routeLockEscapeWhenLockedEdgeGone", MapMatcherTest::routeLockEscapeWhenLockedEdgeGone, passed);
        passed = check("clearWinnerMatches", MapMatcherTest::clearWinnerMatches, passed);
        return passed;
    }

    private static int check(String name, Runnable test, int passed) {
        try {
            test.run();
            System.out.println("PASS MapMatcherTest." + name);
            return passed + 1;
        } catch (AssertionError e) {
            System.out.println("FAIL MapMatcherTest." + name + " :: " + e.getMessage());
            throw e;
        }
    }

    static void parallelSameDirectionAbstains() {
        // Two parallel decks ~7 m apart, same heading, both near: must abstain.
        MapMatcher.Result result = MapMatcher.match(
                Arrays.asList(
                        new MapMatcher.Candidate("E_P_LEFT", 6.0, 8.0, 0, false, false),
                        new MapMatcher.Candidate("E_P_RIGHT", 7.0, 10.0, 0, false, false)),
                null, null, null);
        Assert.equals(null, result.edgeId, "parallel roads must abstain");
        Assert.isTrue(result.ambiguous, "parallel case must flag ambiguous");
    }

    static void bridgeLevelAbstainsWithoutConnectivity() {
        // Same plan position, different decks, neither connected: abstain.
        MapMatcher.Result result = MapMatcher.match(
                Arrays.asList(
                        new MapMatcher.Candidate("E_BR_UP", 5.0, 5.0, 1, false, false),
                        new MapMatcher.Candidate("E_BR_LOW", 6.0, 6.0, 0, false, false)),
                "E_APPROACH", 0, null);
        Assert.equals(null, result.edgeId, "bridge decks must abstain without connectivity");
        Assert.isTrue(result.ambiguous, "bridge case must flag ambiguous");
    }

    static void connectivitySelectsCorrectDeck() {
        // Previous edge reaches the upper deck: topology breaks the tie.
        MapMatcher.Result result = MapMatcher.match(
                Arrays.asList(
                        new MapMatcher.Candidate("E_BR_UP", 8.0, 6.0, 1, true, false),
                        new MapMatcher.Candidate("E_BR_LOW", 5.0, 6.0, 0, false, false)),
                "E_APPROACH", 0, null);
        Assert.equals("E_BR_UP", result.edgeId, "connected deck must win despite longer lateral");
    }

    static void gpsJumpAbstains() {
        MapMatcher.Result result = MapMatcher.match(
                Collections.singletonList(
                        new MapMatcher.Candidate("E_Q1", 48.0, 170.0, 0, false, false)),
                "E_Q1", 0, null);
        Assert.equals(null, result.edgeId, "far + wrong-heading fix must abstain (GPS jump)");
    }

    static void noFixAbstains() {
        MapMatcher.Result result = MapMatcher.match(
                Collections.<MapMatcher.Candidate>emptyList(), "E_Q1", 0, null);
        Assert.equals(null, result.edgeId, "no candidates must abstain");
    }

    static void hysteresisKeepsPrevious() {
        // Previous edge still plausible; top is disconnected: keep previous.
        MapMatcher.Result result = MapMatcher.match(
                Arrays.asList(
                        new MapMatcher.Candidate("E_P_RIGHT", 5.0, 5.0, 0, false, false),
                        new MapMatcher.Candidate("E_P_LEFT", 6.0, 6.0, 0, true, false)),
                "E_P_LEFT", 0, null);
        Assert.equals("E_P_LEFT", result.edgeId, "hysteresis must keep previous on close call");
    }

    static void hysteresisNeverKeepsBelowFloor() {
        // Review case: previous edge A at 48 m / 180 deg (score ~0.024, a lone
        // GPS_JUMP_LOW_SCORE) must NOT be returned as matched when the reliable
        // disconnected candidate B (5 m / 0 deg, score ~0.94) is top. Hysteresis
        // never retains a previous edge below MIN_SCORE; the reliable top wins.
        MapMatcher.Result result = MapMatcher.match(
                Arrays.asList(
                        new MapMatcher.Candidate("E_B_NEAR", 5.0, 0.0, 0, false, false),
                        new MapMatcher.Candidate("E_A_FAR", 48.0, 180.0, 0, false, false)),
                "E_A_FAR", 0, null);
        Assert.equals("E_B_NEAR", result.edgeId, "below-floor previous must not be retained");
        Assert.isTrue(result.confidence >= MapMatcher.MIN_SCORE, "returned edge must clear MIN_SCORE");
    }

    static void nonFiniteMeasurementsAbstain() {
        // NaN / infinite distances or headings must be filtered before scoring:
        // a lone invalid candidate abstains (never matches with NaN confidence)...
        MapMatcher.Result lone = MapMatcher.match(
                Collections.singletonList(
                        new MapMatcher.Candidate("E_BAD", Double.NaN, 5.0, 0, false, false)),
                null, null, null);
        Assert.equals(null, lone.edgeId, "NaN distance must abstain");
        MapMatcher.Result loneHeading = MapMatcher.match(
                Collections.singletonList(
                        new MapMatcher.Candidate("E_BAD", 5.0, Double.NaN, 0, false, false)),
                null, null, null);
        Assert.equals(null, loneHeading.edgeId, "NaN heading must abstain");
        MapMatcher.Result infinite = MapMatcher.match(
                Collections.singletonList(
                        new MapMatcher.Candidate("E_BAD", Double.POSITIVE_INFINITY, 5.0, 0, false, false)),
                null, null, null);
        Assert.equals(null, infinite.edgeId, "infinite distance must abstain");
        // ...and an invalid candidate alongside a valid one must not displace it.
        MapMatcher.Result mixed = MapMatcher.match(
                Arrays.asList(
                        new MapMatcher.Candidate("E_BAD", Double.NaN, Double.NaN, 0, false, false),
                        new MapMatcher.Candidate("E_Q1", 4.0, 5.0, 0, true, false)),
                "E_Q1", 0, null);
        Assert.equals("E_Q1", mixed.edgeId, "valid candidate must win over NaN candidate");
        Assert.isTrue(Double.isFinite(mixed.confidence), "confidence must be finite");
    }

    static void routeLockEscapeWhenLockedEdgeGone() {
        // Route expected E_Q1 but the fix only sees the ramp corridor: unlock.
        MapMatcher.Result result = MapMatcher.match(
                Collections.singletonList(
                        new MapMatcher.Candidate("E_RAMP", 6.0, 10.0, 0, true, false)),
                "E_Q2", 0, "E_Q1");
        Assert.equals("E_RAMP", result.edgeId, "matcher must escape route-lock when locked edge is gone");
    }

    static void clearWinnerMatches() {
        MapMatcher.Result result = MapMatcher.match(
                Arrays.asList(
                        new MapMatcher.Candidate("E_Q1", 4.0, 5.0, 0, true, false),
                        new MapMatcher.Candidate("E_Q1_OPP", 5.0, 170.0, 0, false, false)),
                "E_Q1", 0, null);
        Assert.equals("E_Q1", result.edgeId, "clear winner must match");
        Assert.isTrue(!result.ambiguous, "clear winner must not flag ambiguous");
    }
}
