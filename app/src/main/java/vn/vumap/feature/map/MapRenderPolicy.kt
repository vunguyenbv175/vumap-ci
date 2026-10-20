package vn.vumap.feature.map

import vn.vumap.core.location.GpsState
import vn.vumap.core.model.GeoPoint

/**
 * Decides what the map renders from GPS state.
 *
 * Both [GpsState.Fix] and [GpsState.Stale] retain a point and must render the dot
 * (Stale freezes it where updates ceased); all other states render nothing.
 * Only a live Fix may drive camera follow — a stale point must never recenter the map.
 * Pure policy — covered by JVM unit tests.
 */
object MapRenderPolicy {

    fun renderedPoint(state: GpsState): GeoPoint? = when (state) {
        is GpsState.Fix -> state.fix.point
        is GpsState.Stale -> state.fix.point
        else -> null
    }

    fun shouldFollow(state: GpsState, followSetting: Boolean): Boolean =
        state is GpsState.Fix && followSetting

    /**
     * Origin for starting navigation from a search pick: a live Fix only.
     *
     * Stale retains a point for the frozen dot but must never start guidance
     * from it; NoFix/NoPermission/Unavailable have no position at all. Null
     * means the UI must refuse to route and tell the user to wait for GPS —
     * never fall back to an invented default point.
     * Pure policy — covered by JVM unit tests.
     */
    fun routeOrigin(state: GpsState): GeoPoint? = when (state) {
        is GpsState.Fix -> state.fix.point
        else -> null
    }
}
