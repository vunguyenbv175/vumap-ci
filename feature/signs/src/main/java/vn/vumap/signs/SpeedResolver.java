package vn.vumap.signs;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves the numeric limit from zone + carriageway evidence.
 *
 * <p>Adjudication rule A1 (ACCEPT WITH GUARD): the 50/60 (inside) and 80/90
 * (outside) defaults apply to passenger cars off-expressway <em>only</em>
 * when the carriageway class is verified. With {@code UNKNOWN} carriageway
 * the resolver returns {@code UNKNOWN} with an explicit
 * {@code 50/60 (or 80/90) theo loai duong} label and {@code suppressed=true},
 * so {@link #shouldWarnOverspeed} can never fire on a guess.
 *
 * <p>Source-conflict arbitration (project plan section 6): a direction-matched
 * explicit P.127 (confidence &gt;= 0.7) overrides the zone default, but it never
 * silently overrides typed road (OSM) evidence:
 *
 * <ul>
 *   <li>sign and directed road value equal -&gt; one numeric conclusion from both
 *       sources ({@code ROAD_SIGN_AGREE});</li>
 *   <li>sign and road value differ -&gt; {@code AMBIGUOUS}: no numeric value,
 *       suppressed, zero confident alerts ({@code ROAD_SIGN_CONFLICT_AMBIGUOUS});</li>
 *   <li>road evidence with unsupported conditional/lane/vehicle applicability,
 *       or with an unknown travel direction, abstains to {@code UNKNOWN};</li>
 *   <li>road-only evidence yields a numeric conclusion with the OSM/ROAD source
 *       chip; opposite-direction road evidence is ignored, never applied.</li>
 * </ul>
 *
 * <p>Zone suppression (missing R.421 decay, weak evidence) suppresses
 * zone-derived numbers but never invents one. Road evidence never changes
 * {@link ZoneState} or its confidence.
 */
public final class SpeedResolver {

    /** Overspeed hysteresis so the warning does not chatter at the boundary. */
    public static final int WARN_HYSTERESIS_KMH = 3;
    /** Minimum confidence for an explicit P.127 to override the zone default. */
    public static final float EXPLICIT_MIN_CONFIDENCE = 0.7f;

    private SpeedResolver() {
    }

    /**
     * @param state        current zone (UNKNOWN forces UNKNOWN numeric)
     * @param zoneConfidence tracker confidence 0..1
     * @param suppressed   tracker suppression flag (decay / weak evidence)
     * @param carriageway  verified carriageway evidence (UNKNOWN allowed)
     * @param explicitLimitKmh direction-matched P.127 value, or null
     * @param explicitConfidence confidence of the explicit sign, 0..1
     * @param onExpressway true on cao toc: zone-derived numbers are suspended
     */
    public static SpeedAdvisory resolve(
            ZoneState state,
            float zoneConfidence,
            boolean suppressed,
            CarriagewayType carriageway,
            Integer explicitLimitKmh,
            float explicitConfidence,
            boolean onExpressway) {
        return resolve(
                state,
                zoneConfidence,
                suppressed,
                carriageway,
                explicitLimitKmh,
                explicitConfidence,
                onExpressway,
                Collections.<RoadLimit>emptyList(),
                RoadLimit.Direction.FORWARD);
    }

    /**
     * Full resolution including typed road/OSM evidence for the current edge.
     *
     * @param roadLimits road evidence rows already filtered to the exact current
     *                   edge; may be null/empty when no road data exists
     * @param travelDirection travel direction relative to the edge heading;
     *                   {@link RoadLimit.Direction#UNKNOWN} when it cannot be
     *                   established (directional evidence then abstains)
     */
    public static SpeedAdvisory resolve(
            ZoneState state,
            float zoneConfidence,
            boolean suppressed,
            CarriagewayType carriageway,
            Integer explicitLimitKmh,
            float explicitConfidence,
            boolean onExpressway,
            List<RoadLimit> roadLimits,
            RoadLimit.Direction travelDirection) {

        boolean haveExplicit =
                explicitLimitKmh != null && explicitConfidence >= EXPLICIT_MIN_CONFIDENCE;

        // ---- Road/OSM source arbitration -----------------------------------
        // Only evidence for the current edge (the caller enforced the exact
        // edge) and for the travel direction may be adjudicated. Opposite
        // direction evidence is ignored, never used and never a conflict.
        boolean roadDirectionAmbiguous = false;
        boolean roadApplicabilityUnsupported = false;
        RoadLimit roadEvidence = null;
        Set<Integer> roadValues = new LinkedHashSet<>();
        if (roadLimits != null) {
            for (RoadLimit limit : roadLimits) {
                if (limit == null) {
                    continue;
                }
                Boolean applies = appliesToTravel(limit.direction(), travelDirection);
                if (applies == null) {
                    roadDirectionAmbiguous = true;
                    continue;
                }
                if (!applies.booleanValue()) {
                    continue;
                }
                if (!limit.isSupported()) {
                    roadApplicabilityUnsupported = true;
                    continue;
                }
                roadEvidence = limit;
                roadValues.add(limit.valueKmh());
            }
        }

        if (roadDirectionAmbiguous) {
            return SpeedAdvisory.unknown(
                    "Gioi han duong chua xac dinh (huong ap dung chua ro)",
                    0.0f,
                    "ROAD_LIMIT_DIRECTION_AMBIGUOUS",
                    "UNKNOWN");
        }
        if (roadApplicabilityUnsupported) {
            return SpeedAdvisory.unknown(
                    "Gioi han duong chua xac dinh (dieu kien/loai xe/lan chua ho tro)",
                    0.0f,
                    "ROAD_LIMIT_APPLICABILITY_UNSUPPORTED",
                    roadEvidence == null ? "ROAD" : roadEvidence.sourceLabel());
        }
        if (roadValues.size() > 1) {
            return SpeedAdvisory.unknown(
                    "Du lieu duong mau thuan - chua xac dinh",
                    0.0f,
                    "ROAD_SOURCE_AMBIGUOUS",
                    "AMBIGUOUS");
        }
        if (roadEvidence != null) {
            int roadValue = roadEvidence.valueKmh();
            String roadSource = roadEvidence.sourceLabel();
            if (haveExplicit && explicitLimitKmh.intValue() == roadValue) {
                return new SpeedAdvisory(
                        roadValue,
                        roadValue + " km/h (bien P.127 va du lieu duong "
                                + roadSource + " khop nhau)",
                        Math.max(explicitConfidence, roadEvidence.confidence()),
                        onExpressway,
                        onExpressway
                                ? "ROAD_SIGN_AGREE_ON_EXPRESSWAY_SUPPRESSED"
                                : "ROAD_SIGN_AGREE",
                        "P127+" + roadSource);
            }
            if (haveExplicit) {
                return SpeedAdvisory.unknown(
                        "Nguon mau thuan: bien P.127 " + explicitLimitKmh
                                + " / du lieu duong " + roadSource + " " + roadValue
                                + " - chua xac dinh",
                        0.0f,
                        "ROAD_SIGN_CONFLICT_AMBIGUOUS",
                        "AMBIGUOUS");
            }
            return new SpeedAdvisory(
                    roadValue,
                    roadValue + " km/h (du lieu duong " + roadSource + ")",
                    roadEvidence.confidence(),
                    onExpressway || roadEvidence.confidence() < EXPLICIT_MIN_CONFIDENCE,
                    onExpressway
                            ? "ROAD_LIMIT_ON_EXPRESSWAY_SUPPRESSED"
                            : "ROAD_LIMIT_" + roadSource,
                    roadSource);
        }

        // ---- Explicit sign / zone arbitration (unchanged behavior) ---------
        if (haveExplicit) {
            boolean warnable = !onExpressway;
            return new SpeedAdvisory(
                    explicitLimitKmh,
                    explicitLimitKmh + " km/h (bien P.127)",
                    explicitConfidence,
                    !warnable,
                    warnable ? "EXPLICIT_P127" : "EXPLICIT_P127_ON_EXPRESSWAY_SUPPRESSED",
                    "P127");
        }

        if (onExpressway) {
            return SpeedAdvisory.unknown(
                    "Duong cao toc — gioi han theo bien cao toc, chua xac dinh",
                    zoneConfidence,
                    "EXPRESSWAY_SUSPENDS_ZONE_DEFAULT",
                    "ZONE");
        }

        if (state == null || state == ZoneState.UNKNOWN) {
            return SpeedAdvisory.unknown(
                    "Gioi han chua xac dinh (thieu chung cu vung)",
                    zoneConfidence,
                    "NO_ZONE_EVIDENCE",
                    "UNKNOWN");
        }

        boolean inside = state == ZoneState.INSIDE_DENSE;

        if (carriageway == null || carriageway == CarriagewayType.UNKNOWN) {
            if (inside) {
                return SpeedAdvisory.unknown(
                        "Khu dong dan cu — gioi han chua xac dinh (50/60 theo loai duong)",
                        Math.min(zoneConfidence, 0.4f),
                        "INSIDE_NO_CARRIAGEWAY",
                        "ZONE");
            }
            return SpeedAdvisory.unknown(
                    "Ngoai khu dong dan cu — gioi han chua xac dinh (80/90 theo loai duong)",
                    Math.min(zoneConfidence, 0.4f),
                    "OUTSIDE_NO_CARRIAGEWAY",
                    "ZONE");
        }

        int value;
        String basis;
        if (inside) {
            if (carriageway == CarriagewayType.DUAL_DIVIDED_OR_ONEWAY_2PLUS) {
                value = 60;
                basis = "INSIDE_DUAL_60";
            } else {
                value = 50;
                basis = "INSIDE_TWOWAY_50";
            }
        } else {
            if (carriageway == CarriagewayType.DUAL_DIVIDED_OR_ONEWAY_2PLUS) {
                value = 90;
                basis = "OUTSIDE_DUAL_90";
            } else {
                value = 80;
                basis = "OUTSIDE_TWOWAY_80";
            }
        }

        if (suppressed) {
            return SpeedAdvisory.unknown(
                    inside
                            ? "Khu dong dan cu — can xac minh (thieu R.421 / do tin cay thap)"
                            : "Ngoai khu dong dan cu — can xac minh (do tin cay thap)",
                    zoneConfidence,
                    "SUPPRESSED_" + basis,
                    "ZONE");
        }

        return new SpeedAdvisory(
                value,
                value + " km/h (" + (inside ? "trong" : "ngoai")
                        + " khu dong dan cu, " + basis + ")",
                zoneConfidence,
                false,
                basis,
                "ZONE_DEFAULT");
    }

    /**
     * Direction applicability of one road record.
     *
     * @return {@link Boolean#TRUE} when the record applies to the travel
     *         direction, {@link Boolean#FALSE} when it clearly applies to the
     *         opposite direction (ignored), or {@code null} when direction is
     *         unknown (must abstain rather than guess)
     */
    private static Boolean appliesToTravel(
            RoadLimit.Direction evidenceDirection, RoadLimit.Direction travelDirection) {
        if (evidenceDirection == RoadLimit.Direction.BOTH) {
            return Boolean.TRUE;
        }
        if (travelDirection == RoadLimit.Direction.UNKNOWN
                || evidenceDirection == RoadLimit.Direction.UNKNOWN) {
            return null;
        }
        return Boolean.valueOf(evidenceDirection == travelDirection);
    }

    /**
     * Overspeed predicate. Returns true only for a non-suppressed numeric
     * advisory with speed strictly above value + hysteresis. Guessed or
     * suppressed advisories (null value) never warn: zero false confident
     * alerts on negative tests.
     */
    public static boolean shouldWarnOverspeed(double speedKmh, SpeedAdvisory advisory) {
        if (advisory == null || advisory.valueKmh() == null || advisory.suppressed()) {
            return false;
        }
        return speedKmh > advisory.valueKmh() + WARN_HYSTERESIS_KMH;
    }
}
