package vn.vumap.signs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Topological map matcher skeleton for P1: picks a road edge from GNSS
 * candidates but <strong>abstains (UNKNOWN) on ambiguity</strong> instead of
 * snapping to the nearest edge.
 *
 * <p>Adjudication rule B (ACCEPT): nearest-edge + heading alone is not enough
 * for parallel roads / bridge decks / ramps. Scoring combines lateral
 * distance, heading alignment, previous-edge reachability, route-lock bias and
 * bridge-level continuity, with hysteresis so the fix does not jump between
 * two decks. Close runner-ups force {@code ABSTAIN}: the caller must keep the
 * raw GNSS fix and {@link SpeedResolver} must stay UNKNOWN.
 *
 * <p>Route-lock has an explicit escape: when the locked edge disappears from
 * the candidate set (real turn / reroute), the matcher falls through to the
 * best connected free-drive candidate instead of clamping forever.
 */
public final class MapMatcher {

    /** Max lateral distance (m) considered at all. */
    public static final double MAX_DISTANCE_M = 50.0;
    /** Below this score the fix is treated as a GPS jump / no reliable edge. */
    public static final double MIN_SCORE = 0.30;
    /** Top-two gap below this with both near + aligned forces abstention. */
    public static final double AMBIGUITY_GAP = 0.12;
    /** Window (m) inside which two close candidates count as "parallel". */
    public static final double PARALLEL_WINDOW_M = 25.0;
    /** Heading (deg) below which two candidates count as "same direction". */
    public static final double PARALLEL_HEADING_DEG = 45.0;

    private MapMatcher() {
    }

    /** One GNSS candidate edge. */
    public static final class Candidate {
        public final String edgeId;
        public final double lateralDistanceM;
        /** Absolute heading difference vs travel direction, 0..180. */
        public final double headingDiffDeg;
        /** Deck level: 0 ground, +1 overpass, -1 underpass, … */
        public final int level;
        /** Topologically reachable from the previous fix. */
        public final boolean connectedToPrevious;
        /** Lies on the active navigation route. */
        public final boolean routeLocked;

        public Candidate(
                String edgeId,
                double lateralDistanceM,
                double headingDiffDeg,
                int level,
                boolean connectedToPrevious,
                boolean routeLocked) {
            this.edgeId = edgeId;
            this.lateralDistanceM = lateralDistanceM;
            this.headingDiffDeg = headingDiffDeg;
            this.level = level;
            this.connectedToPrevious = connectedToPrevious;
            this.routeLocked = routeLocked;
        }
    }

    /** Match outcome; {@code edgeId == null} means abstain (UNKNOWN road). */
    public static final class Result {
        public final String edgeId;
        public final double confidence;
        public final boolean ambiguous;
        public final String reason;

        Result(String edgeId, double confidence, boolean ambiguous, String reason) {
            this.edgeId = edgeId;
            this.confidence = confidence;
            this.ambiguous = ambiguous;
            this.reason = reason;
        }

        public boolean matched() {
            return edgeId != null;
        }
    }

    /**
     * @param candidates      GNSS candidate edges (may be empty on no-fix)
     * @param previousEdgeId  last matched edge, or null in free drive start
     * @param previousLevel   deck level of the last fix, or null if unknown
     * @param routeEdgeId     active-route edge for route-lock bias, or null
     */
    public static Result match(
            List<Candidate> candidates,
            String previousEdgeId,
            Integer previousLevel,
            String routeEdgeId) {

        if (candidates == null || candidates.isEmpty()) {
            return new Result(null, 0.0, false, "NO_FIX");
        }

        List<Scored> scored = new ArrayList<>();
        for (Candidate c : candidates) {
            // Reject non-finite measurements before scoring: a NaN distance or
            // heading yields a NaN score, and Java comparisons then let
            // `top.score < MIN_SCORE` and the ambiguity checks all fail, so the
            // invalid candidate would be returned as matched with NaN
            // confidence (and sort ahead of finite candidates). Invalid GNSS
            // measurements must abstain (UNKNOWN), never match.
            if (c == null || c.edgeId == null
                    || !Double.isFinite(c.lateralDistanceM)
                    || c.lateralDistanceM < 0.0
                    || c.lateralDistanceM > MAX_DISTANCE_M
                    || !Double.isFinite(c.headingDiffDeg)
                    || c.headingDiffDeg < 0.0
                    || c.headingDiffDeg > 180.0) {
                continue;
            }
            scored.add(new Scored(c, score(c, previousLevel, routeEdgeId)));
        }
        if (scored.isEmpty()) {
            return new Result(null, 0.0, false, "ALL_CANDIDATES_OUT_OF_RANGE");
        }
        scored.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());

        Scored top = scored.get(0);
        if (top.score < MIN_SCORE) {
            return new Result(null, top.score, false, "GPS_JUMP_LOW_SCORE");
        }

        if (scored.size() >= 2) {
            Scored second = scored.get(1);
            boolean bothNear = top.candidate.lateralDistanceM <= PARALLEL_WINDOW_M
                    && second.candidate.lateralDistanceM <= PARALLEL_WINDOW_M;
            boolean bothAligned = top.candidate.headingDiffDeg <= PARALLEL_HEADING_DEG
                    && second.candidate.headingDiffDeg <= PARALLEL_HEADING_DEG;
            boolean levelsDiffer = top.candidate.level != second.candidate.level;
            double gap = top.score - second.score;
            if (gap < AMBIGUITY_GAP && bothNear && bothAligned) {
                if (levelsDiffer) {
                    return new Result(null, top.score, true, "BRIDGE_LEVEL_AMBIGUOUS");
                }
                return new Result(null, top.score, true, "PARALLEL_ROAD_AMBIGUOUS");
            }
            // Same position, different decks, neither clearly connected:
            // never guess the deck from distance alone.
            if (levelsDiffer
                    && Math.abs(top.candidate.lateralDistanceM
                            - second.candidate.lateralDistanceM)
                            < 5.0
                    && !top.candidate.connectedToPrevious
                    && !second.candidate.connectedToPrevious) {
                return new Result(null, top.score, true, "BRIDGE_LEVEL_AMBIGUOUS");
            }
        }

        // Hysteresis: keep the previous edge when it is still plausible so the
        // fix does not flicker between a main road and a parallel service road.
        // Reliability floor: the previous edge must itself score >= MIN_SCORE.
        // Without this, a far + wrong-heading previous edge (GPS_JUMP_LOW_SCORE
        // on its own) would be returned as matched with a below-floor
        // confidence whenever the top candidate is disconnected. Falling
        // through instead selects the reliable top edge under the topology
        // policy above.
        if (previousEdgeId != null) {
            for (Scored s : scored) {
                if (previousEdgeId.equals(s.candidate.edgeId)) {
                    if (top.candidate.edgeId.equals(previousEdgeId)) {
                        break;
                    }
                    double gap = top.score - s.score;
                    boolean topDisconnected = !top.candidate.connectedToPrevious;
                    if ((gap < 0.20 || topDisconnected) && s.score >= MIN_SCORE) {
                        return new Result(
                                previousEdgeId, s.score, false, "HYSTERESIS_KEEP_PREVIOUS");
                    }
                    break;
                }
            }
            // Previous edge gone from the set: unlock (escape route-lock and
            // free-drive clamping) and fall through to the best candidate.
        }

        return new Result(top.candidate.edgeId, top.score, false, "MATCHED");
    }

    private static double score(Candidate c, Integer previousLevel, String routeEdgeId) {
        double distPart = 1.0 - Math.min(c.lateralDistanceM, MAX_DISTANCE_M) / MAX_DISTANCE_M;
        double headingPart = 1.0 - clamp(c.headingDiffDeg, 0.0, 180.0) / 180.0;
        double score = 0.6 * distPart + 0.4 * headingPart;
        if (c.connectedToPrevious) {
            score += 0.25;
        }
        if (routeEdgeId != null && routeEdgeId.equals(c.edgeId)) {
            score += 0.15;
        }
        if (previousLevel != null && c.level != previousLevel && !c.connectedToPrevious) {
            score -= 0.40;
        }
        return score;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Scored {
        final Candidate candidate;
        final double score;

        Scored(Candidate candidate, double score) {
            this.candidate = candidate;
            this.score = score;
        }
    }

    /** Unmodifiable view helper for tests (not part of the match API). */
    static List<Candidate> copyOf(List<Candidate> input) {
        return Collections.unmodifiableList(new ArrayList<>(input));
    }
}
