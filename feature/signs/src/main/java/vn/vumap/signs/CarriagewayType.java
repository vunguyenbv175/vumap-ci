package vn.vumap.signs;

/**
 * Verified road carriageway evidence for the TT 38/2024 Dieu 6 default table
 * (passenger cars, excluding expressways, no overriding specific sign).
 *
 * <ul>
 *   <li>INSIDE dense area: {@code DUAL} -&gt; 60 km/h;
 *       {@code TWO_WAY_SINGLE} -&gt; 50 km/h.</li>
 *   <li>OUTSIDE dense area: {@code DUAL} -&gt; 90 km/h;
 *       {@code TWO_WAY_SINGLE} -&gt; 80 km/h.</li>
 *   <li>{@code UNKNOWN} -&gt; no numeric default; the advisory must stay
 *       {@code 50/60 theo loai duong, chua xac dinh} (inside) or
 *       {@code 80/90 theo loai duong, chua xac dinh} (outside) and must
 *       never feed an overspeed warning.</li>
 * </ul>
 *
 * <p>Expressway (cao toc) segments suspend zone-derived defaults: the zone
 * state itself is retained, only the numeric conclusion is suppressed until an
 * expressway-specific limit is observed. A {@code motorway} hint must never
 * reset the zone to OUTSIDE (Gemini adjudication A2: REJECT UNSAFE).
 */
public enum CarriagewayType {
    /** No verified lane/divided evidence. */
    UNKNOWN,
    /**
     * Divided dual carriageway OR one-way road with &gt;= 2 motor-vehicle lanes.
     * Inside dense: 60; outside dense: 90.
     */
    DUAL_DIVIDED_OR_ONEWAY_2PLUS,
    /**
     * Two-way road OR one-way road with 1 motor-vehicle lane.
     * Inside dense: 50; outside dense: 80.
     */
    TWO_WAY_OR_ONEWAY_1LANE
}
