package vn.vumap.signs;

/** Single entry point: runs every P1 skeleton test without JUnit. */
public final class RunAllTests {

    private RunAllTests() {
    }

    public static void main(String[] args) {
        int total = 0;
        try {
            total += ZoneTrackerTest.runAll();
            total += SpeedResolverTest.runAll();
            total += RoadLimitArbitrationTest.runAll();
            total += MapMatcherTest.runAll();
            total += SignPackValidatorTest.runAll();
        } catch (AssertionError e) {
            System.out.println("RESULT: FAILED (" + e.getMessage() + ")");
            System.exit(1);
        }
        System.out.println("RESULT: ALL " + total + " TESTS PASSED");
    }
}
