package vn.vumap.signs;

/**
 * Physical sign types relevant to P1 ZoneState.
 *
 * <p>Legal basis: QCVN 41:2024/BGTVT — R.420 starts a densely-populated-area
 * zone, R.421 ends it. Neither encodes a numeric km/h by itself; the numeric
 * default comes from Thong tu 38/2024/TT-BGTVT Dieu 6 combined with verified
 * carriageway evidence (see {@link CarriagewayType} and {@link SpeedResolver}).
 */
public enum SignType {
    /** R.420 — begin densely populated area. */
    R420,
    /** R.421 — end densely populated area. */
    R421,
    /** P.127 speed-limit sign (carries an explicit numeric value). */
    P127,
    /** Any other / unrecognised sign; never drives ZoneState. */
    UNKNOWN
}
