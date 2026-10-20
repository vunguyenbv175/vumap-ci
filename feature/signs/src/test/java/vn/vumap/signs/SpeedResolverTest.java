package vn.vumap.signs;

/** SpeedResolver guard tests: 50/60 only with carriageway, UNKNOWN otherwise. */
public final class SpeedResolverTest {

    private SpeedResolverTest() {
    }

    public static int runAll() {
        int passed = 0;
        passed = check("insideDualGives60", SpeedResolverTest::insideDualGives60, passed);
        passed = check("insideTwoWayGives50", SpeedResolverTest::insideTwoWayGives50, passed);
        passed = check("outsideDualGives90", SpeedResolverTest::outsideDualGives90, passed);
        passed = check("outsideTwoWayGives80", SpeedResolverTest::outsideTwoWayGives80, passed);
        passed = check("insideUnknownCarriagewayStaysUnknown", SpeedResolverTest::insideUnknownCarriagewayStaysUnknown, passed);
        passed = check("unknownZoneStaysUnknown", SpeedResolverTest::unknownZoneStaysUnknown, passed);
        passed = check("suppressedNeverWarns", SpeedResolverTest::suppressedNeverWarns, passed);
        passed = check("explicitP127Overrides", SpeedResolverTest::explicitP127Overrides, passed);
        passed = check("expresswaySuspendsZoneDefault", SpeedResolverTest::expresswaySuspendsZoneDefault, passed);
        passed = check("warnHysteresis", SpeedResolverTest::warnHysteresis, passed);
        return passed;
    }

    private static int check(String name, Runnable test, int passed) {
        try {
            test.run();
            System.out.println("PASS SpeedResolverTest." + name);
            return passed + 1;
        } catch (AssertionError e) {
            System.out.println("FAIL SpeedResolverTest." + name + " :: " + e.getMessage());
            throw e;
        }
    }

    static void insideDualGives60() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.INSIDE_DENSE, 0.9f, false,
                CarriagewayType.DUAL_DIVIDED_OR_ONEWAY_2PLUS, null, 0f, false);
        Assert.equals(60, advisory.valueKmh(), "inside dual must be 60");
        Assert.isTrue(!advisory.suppressed(), "strong 60 must be warnable");
        Assert.isTrue(SpeedResolver.shouldWarnOverspeed(70, advisory), "70 in 60 must warn");
    }

    static void insideTwoWayGives50() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.INSIDE_DENSE, 0.9f, false,
                CarriagewayType.TWO_WAY_OR_ONEWAY_1LANE, null, 0f, false);
        Assert.equals(50, advisory.valueKmh(), "inside two-way must be 50");
    }

    static void outsideDualGives90() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.OUTSIDE_DENSE, 0.9f, false,
                CarriagewayType.DUAL_DIVIDED_OR_ONEWAY_2PLUS, null, 0f, false);
        Assert.equals(90, advisory.valueKmh(), "outside dual must be 90");
    }

    static void outsideTwoWayGives80() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.OUTSIDE_DENSE, 0.9f, false,
                CarriagewayType.TWO_WAY_OR_ONEWAY_1LANE, null, 0f, false);
        Assert.equals(80, advisory.valueKmh(), "outside two-way must be 80");
    }

    static void insideUnknownCarriagewayStaysUnknown() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.INSIDE_DENSE, 0.8f, false,
                CarriagewayType.UNKNOWN, null, 0f, false);
        Assert.equals(null, advisory.valueKmh(), "unknown carriageway must not guess 50/60");
        Assert.isTrue(advisory.suppressed(), "guessed range must suppress");
        Assert.isTrue(advisory.displayLabel().contains("50/60"), "label must name the 50/60 range");
        Assert.isTrue(!SpeedResolver.shouldWarnOverspeed(70, advisory), "never warn on a guess");
        Assert.isTrue(!SpeedResolver.shouldWarnOverspeed(200, advisory), "never warn on a guess at any speed");
    }

    static void unknownZoneStaysUnknown() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.UNKNOWN, 0.0f, true,
                CarriagewayType.DUAL_DIVIDED_OR_ONEWAY_2PLUS, null, 0f, false);
        Assert.equals(null, advisory.valueKmh(), "unknown zone must stay unknown");
        Assert.isTrue(!SpeedResolver.shouldWarnOverspeed(120, advisory), "no warn without zone");
    }

    static void suppressedNeverWarns() {
        // 20 km without R.421: zone kept, numeric suppressed.
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.INSIDE_DENSE, 0.3f, true,
                CarriagewayType.DUAL_DIVIDED_OR_ONEWAY_2PLUS, null, 0f, false);
        Assert.equals(null, advisory.valueKmh(), "suppressed zone must not conclude 60");
        Assert.isTrue(!SpeedResolver.shouldWarnOverspeed(100, advisory), "suppressed must not warn");
    }

    static void explicitP127Overrides() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.INSIDE_DENSE, 0.9f, false,
                CarriagewayType.DUAL_DIVIDED_OR_ONEWAY_2PLUS, 50, 0.85f, false);
        Assert.equals(50, advisory.valueKmh(), "direction-matched P127 overrides zone default");
    }

    static void expresswaySuspendsZoneDefault() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.INSIDE_DENSE, 0.9f, false,
                CarriagewayType.DUAL_DIVIDED_OR_ONEWAY_2PLUS, null, 0f, true);
        Assert.equals(null, advisory.valueKmh(), "expressway suspends zone default");
        Assert.isTrue(!SpeedResolver.shouldWarnOverspeed(120, advisory), "no zone warn on expressway");
    }

    static void warnHysteresis() {
        SpeedAdvisory advisory = SpeedResolver.resolve(
                ZoneState.INSIDE_DENSE, 0.9f, false,
                CarriagewayType.DUAL_DIVIDED_OR_ONEWAY_2PLUS, null, 0f, false);
        Assert.isTrue(!SpeedResolver.shouldWarnOverspeed(62, advisory), "hysteresis: 62 in 60 must not warn");
        Assert.isTrue(SpeedResolver.shouldWarnOverspeed(64, advisory), "64 in 60 must warn");
    }
}
