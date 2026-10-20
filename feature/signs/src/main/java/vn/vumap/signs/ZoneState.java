package vn.vumap.signs;

/**
 * Zone state: which population-density zone the vehicle is believed to be in.
 *
 * <p>This is a <em>zone</em>, not a speed. Numeric limits are derived later by
 * {@link SpeedResolver} only when carriageway evidence exists. Transitions
 * happen exclusively on valid R.420/R.421 evidence (QCVN 41:2024 scope rule).
 * Distance, elapsed time, road class, a single P.127, a turn, or a motorway
 * hint must never flip this state — they only decay confidence / suppress the
 * numeric conclusion (see {@link ZoneTracker}).
 */
public enum ZoneState {
    /** No zone evidence yet, or conflicting evidence that abstains. */
    UNKNOWN,
    /** Inside densely populated area (after R.420, before matching R.421). */
    INSIDE_DENSE,
    /** Outside densely populated area (after R.421, or initial outside). */
    OUTSIDE_DENSE
}
