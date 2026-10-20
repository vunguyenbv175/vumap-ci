package vn.vumap.signs;

import java.util.Objects;

/**
 * One zone observation: an R.420/R.421 sighting (or pack record) bound to a
 * road edge with source, time and confidence.
 *
 * <p>Every state transition in {@link ZoneTracker} keeps its evidence so a
 * missing R.421 degrades confidence instead of silently resetting the zone.
 */
public final class ZoneEvidence {
    private final SignType signType;
    private final String source;
    private final long observedAtMs;
    private final String edgeId;
    private final float confidence;
    private final double lat;
    private final double lon;

    public ZoneEvidence(
            SignType signType,
            String source,
            long observedAtMs,
            String edgeId,
            float confidence,
            double lat,
            double lon) {
        this.signType = Objects.requireNonNull(signType, "signType");
        this.source = Objects.requireNonNull(source, "source");
        this.observedAtMs = observedAtMs;
        this.edgeId = edgeId;
        this.confidence = confidence;
        this.lat = lat;
        this.lon = lon;
    }

    public SignType signType() {
        return signType;
    }

    public String source() {
        return source;
    }

    public long observedAtMs() {
        return observedAtMs;
    }

    /** Matched road edge; may be null for weak/unmatched sightings. */
    public String edgeId() {
        return edgeId;
    }

    public float confidence() {
        return confidence;
    }

    public double lat() {
        return lat;
    }

    public double lon() {
        return lon;
    }

    @Override
    public String toString() {
        return "ZoneEvidence{"
                + "signType=" + signType
                + ", source='" + source + '\''
                + ", edgeId='" + edgeId + '\''
                + ", confidence=" + confidence
                + '}';
    }
}
