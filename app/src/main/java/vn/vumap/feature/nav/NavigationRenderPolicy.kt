package vn.vumap.feature.nav

import vn.vumap.feature.nav.RouteCoordinator.NavigationState

/**
 * Rendering policy for the route line and camera (PR2 P2 follow-up).
 *
 * The map must repaint and re-fit only when the route under navigation actually
 * changes. Step-progress updates (index / quantized remaining distance) carry no
 * new geometry, so keying the render effect off the whole navigation state would
 * restart an 800 ms full-route camera animation every 10 m of travel and
 * overwrite a manual pan or zoom for an unchanged route.
 *
 * Pure JVM (no Android types) so the policy is unit-testable.
 */
object NavigationRenderPolicy {

    /**
     * Route to paint for [state], given the currently painted [painted] route
     * (null when nothing is painted yet).
     *
     * - [NavigationState.Navigating] swaps in its route: the only state carrying
     *   geometry.
     * - [NavigationState.Idle] drops the route so the line is cleared.
     * - [NavigationState.Calculating] / [NavigationState.Error] keep the current
     *   paint: a re-route attempt must not flicker the line away, and the error
     *   banner already explains the state.
     */
    fun paintedRoute(painted: Route?, state: NavigationState): Route? = when (state) {
        is NavigationState.Navigating -> state.route
        is NavigationState.Idle -> null
        else -> painted
    }
}
