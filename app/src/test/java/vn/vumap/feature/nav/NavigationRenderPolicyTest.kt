package vn.vumap.feature.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import vn.vumap.core.model.GeoPoint

/**
 * PR2 P2 regression: the painted route line and camera must key off the route
 * itself, never off step-progress updates. Repeated progress states of the same
 * route must return the identical paint (so no camera re-fit is triggered),
 * while a genuinely new route swaps in and Idle clears.
 */
class NavigationRenderPolicyTest {

    private val destination = GeoPoint(10.7740, 106.7000)

    private fun route(summary: String) = Route(
        geometry = listOf(GeoPoint(10.7725, 106.6980), GeoPoint(10.7732, 106.6990), destination),
        distanceMeters = 520.0,
        durationSeconds = 90.0,
        steps = emptyList(),
        summary = summary,
    )

    private fun navigating(
        route: Route,
        stepIndex: Int,
        remainingM: Double,
        hasArrived: Boolean = false,
    ) = RouteCoordinator.NavigationState.Navigating(
        route = route,
        destination = destination,
        provider = "OSRM (public)",
        isFromCache = false,
        stepIndex = stepIndex,
        distanceToNextStepM = remainingM,
        hasArrived = hasArrived,
    )

    @Test
    fun `progress updates keep the painted route unchanged`() {
        val route = route("seed")
        val painted = NavigationRenderPolicy.paintedRoute(null, navigating(route, 0, 520.0))

        // Same route, advanced maneuver pointer and quantized remaining distance.
        val afterProgress = NavigationRenderPolicy.paintedRoute(painted, navigating(route, 2, 240.0))
        assertSame("progress-only updates must neither repaint nor re-fit", painted, afterProgress)

        val afterArrival = NavigationRenderPolicy.paintedRoute(painted, navigating(route, 2, 0.0, hasArrived = true))
        assertSame(painted, afterArrival)
    }

    @Test
    fun `calculating and error keep the current paint`() {
        val route = route("seed")
        val painted = NavigationRenderPolicy.paintedRoute(null, navigating(route, 1, 300.0))

        assertSame(
            "a re-route attempt must not flicker the line away",
            painted,
            NavigationRenderPolicy.paintedRoute(painted, RouteCoordinator.NavigationState.Calculating(destination)),
        )
        assertSame(
            painted,
            NavigationRenderPolicy.paintedRoute(
                painted,
                RouteCoordinator.NavigationState.Error("offline", RoutingError.NETWORK_OFFLINE),
            ),
        )
    }

    @Test
    fun `idle clears the paint and a new route swaps in`() {
        val route = route("seed")
        val painted = NavigationRenderPolicy.paintedRoute(null, navigating(route, 0, 520.0))

        assertNull(NavigationRenderPolicy.paintedRoute(painted, RouteCoordinator.NavigationState.Idle))

        val rerouted = route("reroute")
        val swapped = NavigationRenderPolicy.paintedRoute(painted, navigating(rerouted, 0, 430.0))
        assertEquals(rerouted, swapped)
        assertNotEquals(painted, swapped)
    }
}
