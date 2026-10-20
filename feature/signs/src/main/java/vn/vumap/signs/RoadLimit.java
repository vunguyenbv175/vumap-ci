package vn.vumap.signs;

import java.util.Objects;

/**
 * Typed OSM/road speed-limit evidence for one directed road edge.
 *
 * <p>This is the road/OSM side of source-conflict arbitration: an explicit
 * P.127 observation is sign evidence, while {@code maxspeed},
 * {@code maxspeed:forward} and {@code maxspeed:backward} rows are road
 * evidence. One instance models one directional tag row; callers pass every
 * row that applies to the current edge (the production repository enforces the
 * exact edge match before the evidence reaches {@link SpeedResolver}).
 *
 * <p>Only evidence that applies to the travel direction may be adjudicated:
 * {@link Direction#BOTH} always applies; a matching directed row applies; the
 * opposite direction is ignored (never used and never treated as a conflict);
 * an unknown direction is ambiguous and must abstain. OSM {@code conditional},
 * {@code lanes} and vehicle-specific tags are not interpreted in this slice;
 * they keep their typed {@link Applicability} so the resolver suppresses the
 * numeric conclusion instead of guessing or silently ignoring a road-specific
 * rule.
 *
 * <p>Values are bounded to the same 5..120 km/h range as P.127 evidence.
 * Instances are immutable evidence only: they never change {@link ZoneState},
 * never reset zone confidence, and never certify field accuracy.
 */
public final class RoadLimit {

    public static final int MIN_KMH = 5;
    public static final int MAX_KMH = 120;

    /** Provenance of the road record; the advisory source is not a legal claim. */
    public enum Source {
        OSM,
        ROAD
    }

    /** Travel direction (relative to the edge heading) the tag applies to. */
    public enum Direction {
        FORWARD,
        BACKWARD,
        BOTH,
        UNKNOWN
    }

    /** Tag applicability. Only {@link #SUPPORTED} records may be adjudicated. */
    public enum Applicability {
        SUPPORTED,
        /** OSM conditional restriction: applicability depends on unmodelled conditions. */
        CONDITIONAL_UNSUPPORTED,
        /** OSM lane-specific restriction: per-lane application is not modelled. */
        LANE_UNSUPPORTED,
        /** OSM vehicle-specific restriction: vehicle class is not modelled. */
        VEHICLE_UNSUPPORTED
    }

    private final String edgeId;
    private final Source source;
    private final String provenance;
    private final Direction direction;
    private final Integer valueKmh;
    private final float confidence;
    private final Applicability applicability;

    public RoadLimit(
            String edgeId,
            Source source,
            String provenance,
            Direction direction,
            Integer valueKmh,
            float confidence,
            Applicability applicability) {
        if (edgeId == null || edgeId.trim().isEmpty()) {
            throw new IllegalArgumentException("edgeId must not be blank");
        }
        this.edgeId = edgeId;
        this.source = Objects.requireNonNull(source, "source");
        this.provenance = provenance == null ? "" : provenance;
        this.direction = direction == null ? Direction.UNKNOWN : direction;
        this.applicability = applicability == null
                ? Applicability.CONDITIONAL_UNSUPPORTED
                : applicability;
        if (Float.isNaN(confidence) || confidence < 0.0f || confidence > 1.0f) {
            throw new IllegalArgumentException("confidence must be within 0..1");
        }
        this.confidence = confidence;
        if (this.applicability == Applicability.SUPPORTED) {
            if (valueKmh == null || valueKmh < MIN_KMH || valueKmh > MAX_KMH) {
                throw new IllegalArgumentException(
                        "supported road limit needs a value within " + MIN_KMH + ".." + MAX_KMH);
            }
        }
        this.valueKmh = valueKmh;
    }

    /** Convenience factory for an applicable directional/bidirectional limit. */
    public static RoadLimit supported(
            String edgeId,
            Source source,
            String provenance,
            Direction direction,
            int valueKmh,
            float confidence) {
        return new RoadLimit(
                edgeId, source, provenance, direction, valueKmh, confidence,
                Applicability.SUPPORTED);
    }

    /** Convenience factory for an unsupported tag that must only ever abstain. */
    public static RoadLimit unsupported(
            String edgeId,
            Source source,
            String provenance,
            Direction direction,
            Applicability applicability) {
        if (applicability == Applicability.SUPPORTED) {
            throw new IllegalArgumentException("use supported(...) for applicable evidence");
        }
        return new RoadLimit(edgeId, source, provenance, direction, null, 0.0f, applicability);
    }

    public String edgeId() {
        return edgeId;
    }

    public Source source() {
        return source;
    }

    /** Source version/provenance string, e.g. an OSM snapshot label. */
    public String provenance() {
        return provenance;
    }

    public Direction direction() {
        return direction;
    }

    /** Null for unsupported tags; a value is never guessed. */
    public Integer valueKmh() {
        return valueKmh;
    }

    public float confidence() {
        return confidence;
    }

    public Applicability applicability() {
        return applicability;
    }

    public boolean isSupported() {
        return applicability == Applicability.SUPPORTED;
    }

    /** Source chip label used by advisories: {@code OSM} or {@code ROAD}. */
    public String sourceLabel() {
        return source == Source.OSM ? "OSM" : "ROAD";
    }

    @Override
    public String toString() {
        return "RoadLimit{"
                + "edgeId='" + edgeId + '\''
                + ", source=" + source
                + ", direction=" + direction
                + ", valueKmh=" + valueKmh
                + ", confidence=" + confidence
                + ", applicability=" + applicability
                + '}';
    }
}
