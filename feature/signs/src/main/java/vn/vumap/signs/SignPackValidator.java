package vn.vumap.signs;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lightweight SAF/offline-pack validation skeleton (no SQLite dependency).
 *
 * <p>Checks, in order: schema version, provenance (source/license/version),
 * size cap, raw-byte SHA-256 against {@link PackManifest#expectedSha256}
 * when the manifest declares one, point budget, per-point ranges (VN bbox,
 * confidence, heading, P.127 value vs R.420/R.421 payload), duplicate id /
 * near-duplicate location+type.
 *
 * <p>A declared checksum gates acceptance: without matching raw bytes the
 * pack is rejected and the previous DB is kept. Callers holding the pack
 * bytes must use {@link #validate(PackManifest, List, long, byte[])}; the
 * 3-argument overload passes no bytes and therefore rejects any pack that
 * declares a checksum (fail closed).
 *
 * <p>Real {@code .vupack}/SQLite imports additionally run
 * {@code PRAGMA integrity_check}, manifest manifest-vs-content counts and an
 * atomic file swap with rollback of the previous DB; those steps live in the
 * Android SAF layer and are documented here as the contract this skeleton
 * guards. Importing or committing a pack never certifies field accuracy —
 * synthetic fixtures must stay synthetic and must never be presented as
 * surveyed signs.
 */
public final class SignPackValidator {

    /** Only schema this skeleton understands. */
    public static final int SUPPORTED_SCHEMA = 1;
    /** Rejects truncated/empty packs. */
    public static final int MIN_POINTS = 1;
    /** Generic abuse cap for real packs (synthetic CI fixtures use &lt;= 50). */
    public static final int MAX_POINTS_REAL = 500_000;
    /** Synthetic CI budget enforced by the fixture test, not here. */
    public static final int MAX_POINTS_SYNTHETIC_FIXTURE = 50;
    /** Skeleton file-size cap (5 MiB); real regional packs raise this explicitly. */
    public static final long MAX_BYTES = 5L * 1024 * 1024;

    // Vietnam bounding box (WGS84).
    public static final double MIN_LAT = 8.0;
    public static final double MAX_LAT = 23.5;
    public static final double MIN_LON = 102.0;
    public static final double MAX_LON = 110.0;
    /** Two records of the same type closer than this count as duplicates. */
    public static final double DUPLICATE_METRES = 2.0;

    private SignPackValidator() {
    }

    public static ValidationResult validate(
            PackManifest manifest, List<PackPoint> points, long fileSizeBytes) {
        return validate(manifest, points, fileSizeBytes, null);
    }

    /**
     * Full acceptance path: structural checks plus raw-byte SHA-256
     * verification when {@code manifest.expectedSha256} is set.
     *
     * @param packBytes raw pack bytes the importer hashed; null only when no
     *                  checksum is declared. A declared checksum with null
     *                  bytes fails closed ({@code CHECKSUM_UNVERIFIED}).
     */
    public static ValidationResult validate(
            PackManifest manifest, List<PackPoint> points, long fileSizeBytes,
            byte[] packBytes) {
        List<String> errors = new ArrayList<>();

        if (manifest == null) {
            errors.add("MISSING_MANIFEST");
            return ValidationResult.reject(errors);
        }
        if (manifest.schemaVersion != SUPPORTED_SCHEMA) {
            errors.add("UNSUPPORTED_SCHEMA:" + manifest.schemaVersion);
        }
        if (isBlank(manifest.source)) {
            errors.add("MISSING_PROVENANCE_SOURCE");
        }
        if (isBlank(manifest.license)) {
            errors.add("MISSING_PROVENANCE_LICENSE");
        }
        if (isBlank(manifest.version)) {
            errors.add("MISSING_PROVENANCE_VERSION");
        }
        if (!isBlank(manifest.expectedSha256)) {
            if (packBytes == null) {
                errors.add("CHECKSUM_UNVERIFIED");
            } else if (!verifySha256(packBytes, manifest.expectedSha256)) {
                errors.add("CHECKSUM_MISMATCH");
            }
        }
        if (fileSizeBytes <= 0) {
            errors.add("TRUNCATED_EMPTY_FILE");
        } else if (fileSizeBytes > MAX_BYTES) {
            errors.add("TOO_LARGE:" + fileSizeBytes);
        }

        if (points == null || points.size() < MIN_POINTS) {
            errors.add("TRUNCATED_NO_POINTS");
            return ValidationResult.reject(errors);
        }
        if (points.size() > MAX_POINTS_REAL) {
            errors.add("TOO_MANY_POINTS:" + points.size());
            return ValidationResult.reject(errors);
        }

        Set<String> ids = new HashSet<>();
        for (int i = 0; i < points.size(); i++) {
            PackPoint p = points.get(i);
            String where = "POINT[" + i + "]";
            if (p == null) {
                errors.add(where + ":NULL_ENTRY");
                continue;
            }
            if (isBlank(p.id)) {
                errors.add(where + ":MISSING_ID");
            } else if (!ids.add(p.id)) {
                errors.add(where + ":DUPLICATE_ID:" + p.id);
            }
            if (p.type == null) {
                errors.add(where + ":MISSING_TYPE");
                continue;
            }
            if (Double.isNaN(p.lat) || Double.isNaN(p.lon)
                    || p.lat < MIN_LAT || p.lat > MAX_LAT
                    || p.lon < MIN_LON || p.lon > MAX_LON) {
                errors.add(where + ":OUT_OF_VN_RANGE:" + p.lat + "," + p.lon);
            }
            if (Double.isNaN(p.confidence) || p.confidence < 0.0 || p.confidence > 1.0) {
                errors.add(where + ":BAD_CONFIDENCE:" + p.confidence);
            }
            if (p.headingDeg != null
                    && (p.headingDeg.isNaN() || p.headingDeg < 0.0 || p.headingDeg >= 360.0)) {
                errors.add(where + ":BAD_HEADING:" + p.headingDeg);
            }
            if (isBlank(p.edgeId)) {
                errors.add(where + ":MISSING_EDGE:" + p.id);
            }
            if (isBlank(p.source)) {
                errors.add(where + ":MISSING_SOURCE:" + p.id);
            }
            if (p.type == SignType.P127) {
                if (p.valueKmh == null || p.valueKmh < 5 || p.valueKmh > 120) {
                    errors.add(where + ":BAD_P127_VALUE:" + p.valueKmh);
                }
            } else if (p.type == SignType.R420 || p.type == SignType.R421) {
                if (p.valueKmh != null) {
                    errors.add(where + ":ZONE_SIGN_MUST_NOT_CARRY_VALUE:" + p.id);
                }
            }
        }

        // Near-duplicate scan (same type within ~2 m).
        for (int i = 0; i < points.size() && errors.size() < 50; i++) {
            PackPoint a = points.get(i);
            if (a == null || a.type == null) {
                continue;
            }
            for (int j = i + 1; j < points.size(); j++) {
                PackPoint b = points.get(j);
                if (b == null || b.type != a.type) {
                    continue;
                }
                if (haversineM(a.lat, a.lon, b.lat, b.lon) < DUPLICATE_METRES) {
                    errors.add("DUPLICATE_LOCATION:" + a.id + "~" + b.id);
                    break;
                }
            }
        }

        if (errors.isEmpty()) {
            return ValidationResult.ok();
        }
        return ValidationResult.reject(errors);
    }

    /** Verifies raw bytes against a lower-case hex SHA-256 expectation. */
    public static boolean verifySha256(byte[] content, String expectedHex) {
        if (content == null || expectedHex == null || expectedHex.isEmpty()) {
            return false;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString().equalsIgnoreCase(expectedHex);
        } catch (Exception e) {
            return false;
        }
    }

    /** SHA-256 hex of the given bytes (helper for manifests/tests). */
    public static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static double haversineM(double lat1, double lon1, double lat2, double lon2) {
        double earth = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * earth * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
