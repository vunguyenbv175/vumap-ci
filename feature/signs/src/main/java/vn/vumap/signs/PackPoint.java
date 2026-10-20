package vn.vumap.signs;

/**
 * One sign/road point inside an offline pack (synthetic fixture or real
 * regional {@code .vupack}/SQLite export). Coordinates are WGS84 degrees.
 */
public final class PackPoint {
    public final String id;
    public final SignType type;
    public final double lat;
    public final double lon;
    /** Travel heading the sign applies to, degrees 0..360; null = unaligned. */
    public final Double headingDeg;
    /** Explicit km/h for P.127; must be null for R.420/R.421. */
    public final Integer valueKmh;
    public final String edgeId;
    public final double confidence;
    public final String source;

    public PackPoint(
            String id,
            SignType type,
            double lat,
            double lon,
            Double headingDeg,
            Integer valueKmh,
            String edgeId,
            double confidence,
            String source) {
        this.id = id;
        this.type = type;
        this.lat = lat;
        this.lon = lon;
        this.headingDeg = headingDeg;
        this.valueKmh = valueKmh;
        this.edgeId = edgeId;
        this.confidence = confidence;
        this.source = source;
    }
}
