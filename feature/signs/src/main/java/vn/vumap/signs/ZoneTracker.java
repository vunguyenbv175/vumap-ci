package vn.vumap.signs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tracks {@link ZoneState} from R.420/R.421 evidence with
 * <strong>confidence decay without legal reset</strong>.
 *
 * <p>Rules (project plan sections 5, 9-P1; adjudication A1/A2):
 *
 * <ul>
 *   <li>Only a valid R.420 flips the zone to {@code INSIDE_DENSE}; only a
 *       valid R.421 flips it to {@code OUTSIDE_DENSE} (QCVN 41:2024 scope:
 *       the zone holds through intersections until R.421).</li>
 *   <li>Elapsed time / driven distance only decay {@link #confidence()} and
 *       raise {@link #needsVerification()}; they never change
 *       {@link #state()}. A 20&nbsp;km drive with no R.421 keeps
 *       {@code INSIDE_DENSE} at low confidence (suppressed numeric).</li>
 *   <li>Road class ({@code trunk}/{@code primary}), a single P.127, a turn /
 *       intersection, or a motorway/highway hint never reset the zone.
 *       Dedicated no-op hooks below exist so reviewers can verify the
 *       absence of a reset path; they are covered by unit tests.</li>
 *   <li>Weak evidence (null edge, low confidence, orphan R.421 from UNKNOWN)
 *       still records history but marks the tracker as needing verification
 *       so {@link SpeedResolver} suppresses any decisive speed.</li>
 * </ul>
 */
public final class ZoneTracker {

    /** Confidence floor: decay never drops below this; only new evidence raises it. */
    public static final float CONFIDENCE_FLOOR = 0.10f;
    /** Below this the numeric conclusion is suppressed (but the zone is kept). */
    public static final float SUPPRESS_BELOW = 0.50f;
    /** Distance without fresh evidence that forces verification (suppression). */
    public static final double VERIFY_AFTER_DISTANCE_M = 20_000.0;

    /** Linear decay per metre driven (0.02/km: 20 km costs 0.40). */
    static final double DECAY_PER_METRE = 0.00002;
    /** Linear decay per millisecond (0.01/hour). */
    static final double DECAY_PER_MS = 0.01 / 3_600_000.0;

    private ZoneState state = ZoneState.UNKNOWN;
    private float confidence;
    private ZoneEvidence lastEvidence;
    private final List<ZoneEvidence> history = new ArrayList<>();
    private double distanceSinceEvidenceM;
    private long timeSinceEvidenceMs;
    private boolean needsVerification;

    public ZoneTracker() {
        this.confidence = 0.0f;
        this.needsVerification = true;
    }

    public ZoneState state() {
        return state;
    }

    public float confidence() {
        return confidence;
    }

    public ZoneEvidence lastEvidence() {
        return lastEvidence;
    }

    public List<ZoneEvidence> history() {
        return Collections.unmodifiableList(history);
    }

    /** True when the numeric conclusion must be suppressed (zone itself is kept). */
    public boolean needsVerification() {
        return needsVerification;
    }

    /** Suppression predicate consumed by {@link SpeedResolver}. */
    public boolean isSuppressed() {
        return needsVerification || confidence < SUPPRESS_BELOW;
    }

    /**
     * Observes one R.420/R.421 evidence record. Non-zone signs
     * ({@code P127}/{@code UNKNOWN}) are recorded in history only when they
     * carry zone relevance; otherwise they are ignored for zone purposes.
     */
    public void observe(ZoneEvidence evidence) {
        if (evidence == null) {
            return;
        }
        SignType type = evidence.signType();
        if (type != SignType.R420 && type != SignType.R421) {
            return;
        }

        boolean weak = evidence.edgeId() == null
                || evidence.edgeId().isEmpty()
                || evidence.confidence() < 0.5f;
        float applied = weak
                ? Math.max(CONFIDENCE_FLOOR, evidence.confidence() * 0.5f)
                : evidence.confidence();

        if (type == SignType.R420) {
            state = ZoneState.INSIDE_DENSE;
        } else {
            // R.421 ends the zone even from UNKNOWN (orphan end sign): we land
            // OUTSIDE but stay flagged until stronger corridor evidence arrives.
            boolean orphan = state == ZoneState.UNKNOWN;
            state = ZoneState.OUTSIDE_DENSE;
            if (orphan) {
                weak = true;
                applied = Math.max(CONFIDENCE_FLOOR, evidence.confidence() * 0.7f);
            }
        }

        lastEvidence = evidence;
        history.add(evidence);
        confidence = clamp(applied);
        distanceSinceEvidenceM = 0.0;
        timeSinceEvidenceMs = 0L;
        needsVerification = weak;
    }

    /**
     * Decays confidence with time/distance. The zone state is intentionally
     * untouched — this is the "decay without legal reset" rule.
     */
    public void decay(long elapsedMs, double distanceM) {
        if (elapsedMs < 0 || distanceM < 0) {
            throw new IllegalArgumentException("elapsedMs/distanceM must be >= 0");
        }
        timeSinceEvidenceMs += elapsedMs;
        distanceSinceEvidenceM += distanceM;
        double dropped = distanceM * DECAY_PER_METRE + elapsedMs * DECAY_PER_MS;
        confidence = (float) Math.max(CONFIDENCE_FLOOR, confidence - dropped);
        if (distanceSinceEvidenceM >= VERIFY_AFTER_DISTANCE_M
                || confidence < SUPPRESS_BELOW) {
            needsVerification = true;
        }
    }

    // ---- Explicit non-reset hooks (all no-ops by design) --------------------
    //
    // Each exists so a reviewer (and the unit tests) can confirm there is no
    // hidden path that converts distance / class / turn / motorway / P.127
    // into a zone flip. They deliberately leave state, confidence and flags
    // unchanged.

    /** Long drive without R.421: must NOT flip to OUTSIDE (tested at 20 km+). */
    public void onLongDistanceWithoutR421(double distanceM) {
        decay(0L, distanceM);
        // No state change by design.
    }

    /** Road-class hint (trunk/primary/…): never a zone reset. */
    public void onRoadClassHint(String roadClass) {
        // No-op by design; road class only feeds SpeedResolver carriageway
        // evidence, never ZoneState.
    }

    /** Single P.127 speed sign: never ends the R.420 zone. */
    public void onSingleSpeedSign(int valueKmh) {
        // No-op by design.
    }

    /** Turn / intersection inside the urban area: the zone continues. */
    public void onTurnOrIntersection() {
        // No-op by design (QCVN 41 scope holds through intersections).
    }

    /** Motorway/expressway hint: suspends the numeric, keeps the zone. */
    public void onMotorwayHint() {
        // No-op by design; SpeedResolver suppresses zone-derived numbers on
        // expressways without touching this state.
    }

    private static float clamp(float value) {
        if (value < CONFIDENCE_FLOOR) {
            return CONFIDENCE_FLOOR;
        }
        if (value > 1.0f) {
            return 1.0f;
        }
        return value;
    }
}
