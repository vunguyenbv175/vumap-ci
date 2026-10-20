package vn.vumap.signs;

/**
 * Numeric speed conclusion derived from zone + carriageway (+ optional
 * explicit P.127). A null {@link #valueKmh()} means {@code UNKNOWN}: the UI
 * must show the reason label and must not emit an overspeed warning.
 */
public final class SpeedAdvisory {
    private final Integer valueKmh;
    private final String displayLabel;
    private final float confidence;
    private final boolean suppressed;
    private final String reason;
    private final String source;

    public SpeedAdvisory(
            Integer valueKmh,
            String displayLabel,
            float confidence,
            boolean suppressed,
            String reason,
            String source) {
        this.valueKmh = valueKmh;
        this.displayLabel = displayLabel;
        this.confidence = confidence;
        this.suppressed = suppressed;
        this.reason = reason;
        this.source = source;
    }

    public static SpeedAdvisory unknown(
            String displayLabel, float confidence, String reason, String source) {
        return new SpeedAdvisory(null, displayLabel, confidence, true, reason, source);
    }

    /** Null when the limit is not determined (UNKNOWN). */
    public Integer valueKmh() {
        return valueKmh;
    }

    public String displayLabel() {
        return displayLabel;
    }

    public float confidence() {
        return confidence;
    }

    /** True: do not warn on this advisory. */
    public boolean suppressed() {
        return suppressed;
    }

    public String reason() {
        return reason;
    }

    public String source() {
        return source;
    }

    @Override
    public String toString() {
        return "SpeedAdvisory{"
                + "valueKmh=" + valueKmh
                + ", label='" + displayLabel + '\''
                + ", suppressed=" + suppressed
                + ", reason='" + reason + '\''
                + '}';
    }
}
