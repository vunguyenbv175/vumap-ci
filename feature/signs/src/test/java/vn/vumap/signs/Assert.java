package vn.vumap.signs;

/** Minimal assertion helpers (no JUnit dependency for the P1 skeleton). */
final class Assert {
    private Assert() {
    }

    static void isTrue(boolean cond, String message) {
        if (!cond) {
            throw new AssertionError(message);
        }
    }

    static void equals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    static void floatEquals(float expected, float actual, float epsilon, String message) {
        if (Math.abs(expected - actual) > epsilon) {
            throw new AssertionError(
                    message + " expected=" + expected + " actual=" + actual);
        }
    }
}
