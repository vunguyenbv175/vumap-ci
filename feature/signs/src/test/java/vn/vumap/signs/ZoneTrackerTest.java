package vn.vumap.signs;

/**
 * ZoneState scope + decay-without-reset tests (adjudication A1/A2, P1 gate 4).
 */
public final class ZoneTrackerTest {

    private ZoneTrackerTest() {
    }

    public static int runAll() {
        int passed = 0;
        passed = check("r420EntersInside", ZoneTrackerTest::r420EntersInside, passed);
        passed = check("r421ClosesScope", ZoneTrackerTest::r421ClosesScope, passed);
        passed = check("missingR421TwentyKmKeepsInside", ZoneTrackerTest::missingR421TwentyKmKeepsInside, passed);
        passed = check("decayLowersConfidenceWithoutReset", ZoneTrackerTest::decayLowersConfidenceWithoutReset, passed);
        passed = check("noResetOnRoadClass", ZoneTrackerTest::noResetOnRoadClass, passed);
        passed = check("noResetOnSingleP127", ZoneTrackerTest::noResetOnSingleP127, passed);
        passed = check("noResetOnTurnOrMotorway", ZoneTrackerTest::noResetOnTurnOrMotorway, passed);
        passed = check("weakEvidenceKeepsHistoryAndFlagsVerify", ZoneTrackerTest::weakEvidenceKeepsHistoryAndFlagsVerify, passed);
        passed = check("intersectionDoesNotEndZone", ZoneTrackerTest::intersectionDoesNotEndZone, passed);
        return passed;
    }

    private static int check(String name, Runnable test, int passed) {
        try {
            test.run();
            System.out.println("PASS ZoneTrackerTest." + name);
            return passed + 1;
        } catch (AssertionError e) {
            System.out.println("FAIL ZoneTrackerTest." + name + " :: " + e.getMessage());
            throw e;
        }
    }

    static void r420EntersInside() {
        ZoneTracker tracker = new ZoneTracker();
        Assert.equals(ZoneState.UNKNOWN, tracker.state(), "initial");
        tracker.observe(new ZoneEvidence(SignType.R420, "OFFLINE_PACK", 1L, "E_Q1", 0.9f, 10.82, 106.62));
        Assert.equals(ZoneState.INSIDE_DENSE, tracker.state(), "R420 must enter INSIDE");
        Assert.isTrue(!tracker.isSuppressed(), "strong R420 must not suppress");
    }

    static void r421ClosesScope() {
        ZoneTracker tracker = new ZoneTracker();
        tracker.observe(new ZoneEvidence(SignType.R420, "OFFLINE_PACK", 1L, "E_Q1", 0.9f, 10.82, 106.62));
        tracker.observe(new ZoneEvidence(SignType.R421, "OFFLINE_PACK", 2L, "E_Q2", 0.9f, 10.82, 106.63));
        Assert.equals(ZoneState.OUTSIDE_DENSE, tracker.state(), "R421 must close to OUTSIDE");
        Assert.equals(2, tracker.history().size(), "history keeps both evidences");
    }

    static void missingR421TwentyKmKeepsInside() {
        ZoneTracker tracker = new ZoneTracker();
        tracker.observe(new ZoneEvidence(SignType.R420, "OFFLINE_PACK", 1L, "E_L1", 0.9f, 16.05, 108.20));
        tracker.onLongDistanceWithoutR421(20_000.0);
        Assert.equals(ZoneState.INSIDE_DENSE, tracker.state(), "20km without R421 must NOT flip OUTSIDE");
        Assert.isTrue(tracker.needsVerification(), "long gap must flag verification");
        Assert.isTrue(tracker.isSuppressed(), "long gap must suppress numeric");
    }

    static void decayLowersConfidenceWithoutReset() {
        ZoneTracker tracker = new ZoneTracker();
        tracker.observe(new ZoneEvidence(SignType.R420, "OFFLINE_PACK", 1L, "E_L1", 0.9f, 16.05, 108.20));
        float before = tracker.confidence();
        tracker.decay(3_600_000L, 5_000.0);
        Assert.isTrue(tracker.confidence() < before, "decay must lower confidence");
        Assert.equals(ZoneState.INSIDE_DENSE, tracker.state(), "decay must never reset zone");
    }

    static void noResetOnRoadClass() {
        ZoneTracker tracker = new ZoneTracker();
        tracker.observe(new ZoneEvidence(SignType.R420, "OFFLINE_PACK", 1L, "E_Q1", 0.9f, 10.82, 106.62));
        tracker.onRoadClassHint("trunk");
        tracker.onRoadClassHint("primary");
        Assert.equals(ZoneState.INSIDE_DENSE, tracker.state(), "road class must not reset zone");
    }

    static void noResetOnSingleP127() {
        ZoneTracker tracker = new ZoneTracker();
        tracker.observe(new ZoneEvidence(SignType.R420, "OFFLINE_PACK", 1L, "E_Q1", 0.9f, 10.82, 106.62));
        tracker.onSingleSpeedSign(60);
        Assert.equals(ZoneState.INSIDE_DENSE, tracker.state(), "single P127 must not end zone");
    }

    static void noResetOnTurnOrMotorway() {
        ZoneTracker tracker = new ZoneTracker();
        tracker.observe(new ZoneEvidence(SignType.R420, "OFFLINE_PACK", 1L, "E_Q1", 0.9f, 10.82, 106.62));
        tracker.onTurnOrIntersection();
        tracker.onMotorwayHint();
        Assert.equals(ZoneState.INSIDE_DENSE, tracker.state(), "turn/motorway must not reset zone");
    }

    static void weakEvidenceKeepsHistoryAndFlagsVerify() {
        ZoneTracker tracker = new ZoneTracker();
        // Null edge + low confidence: state still flips (visible) but suppressed.
        tracker.observe(new ZoneEvidence(SignType.R420, "OFFLINE_PACK", 1L, null, 0.3f, 10.82, 106.62));
        Assert.equals(ZoneState.INSIDE_DENSE, tracker.state(), "weak R420 still records zone");
        Assert.isTrue(tracker.needsVerification(), "weak evidence must flag verification");
        Assert.isTrue(tracker.isSuppressed(), "weak evidence must suppress numeric");
        Assert.equals(1, tracker.history().size(), "history kept");
    }

    static void intersectionDoesNotEndZone() {
        ZoneTracker tracker = new ZoneTracker();
        tracker.observe(new ZoneEvidence(SignType.R420, "OFFLINE_PACK", 1L, "E_Q1", 0.9f, 10.82, 106.62));
        tracker.onTurnOrIntersection();
        tracker.observe(new ZoneEvidence(SignType.R420, "OFFLINE_PACK", 2L, "E_INT", 0.85f, 10.82, 106.63));
        Assert.equals(ZoneState.INSIDE_DENSE, tracker.state(), "intersection interior keeps INSIDE");
    }
}
