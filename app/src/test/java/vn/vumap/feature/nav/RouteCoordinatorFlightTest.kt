package vn.vumap.feature.nav

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.vumap.core.model.GeoPoint

/**
 * Single-flight regression tests for the PR2 P1 fix: GPS ticks must never run
 * routing in the effect lifetime, and a cancelled request must restore a usable
 * state instead of wedging navigation in Calculating.
 */
class RouteCoordinatorFlightTest {

    private val origin = GeoPoint(10.7725, 106.6980)
    private val destination = GeoPoint(10.7768, 106.7032)

    // ~5 km from the seed geometry: reliably off-route (> 40 m).
    private val farAway = GeoPoint(10.8000, 106.6500)

    private fun seedRoute(summary: String) = Route(
        geometry = listOf(origin, GeoPoint(10.7740, 106.7000), destination),
        distanceMeters = 800.0,
        durationSeconds = 120.0,
        steps = emptyList(),
        summary = summary,
    )

    private class GateProvider : RoutingProvider {
        override val providerName: String = "Gate"
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null
        var reply: () -> RouteResult = {
            RouteResult.Success(
                Route(
                    geometry = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0)),
                    distanceMeters = 1.0,
                    durationSeconds = 1.0,
                    steps = emptyList(),
                ),
                "Gate",
            )
        }

        override suspend fun route(
            from: GeoPoint,
            to: GeoPoint,
            options: CarOptions,
        ): RouteResult {
            calls++
            gate?.await()
            return reply()
        }
    }

    @Test
    fun `ticks during an in-flight reroute neither cancel it nor wedge state`() = runTest {
        var now = 100_000L
        val provider = GateProvider()
        val rerouted = seedRoute("reroute")
        provider.reply = { RouteResult.Success(rerouted, "Gate") }
        val chain = RoutingChain(listOf(provider), timeProvider = { now })
        val coordinator = RouteCoordinator(
            routingChain = chain,
            ownerScope = this,
            debounceIntervalMs = 0L,
            timeProvider = { now },
        )

        // Seed an active navigation.
        assertTrue(coordinator.requestRoute(origin, destination, force = true) is RouteResult.Success)
        assertEquals(1, provider.calls)

        // Slow network from here on: the next provider call blocks on a gate.
        provider.gate = CompletableDeferred()

        // First off-route tick launches the single flight...
        now += 10_000L
        assertTrue(coordinator.onGpsTick(farAway))
        advanceUntilIdle()

        // ...then a burst of 1 Hz fixes arrives while it is in flight.
        // They must be ignored, never cancelling the flight.
        repeat(5) {
            now += 1_000L
            assertFalse("tick during flight must not launch or cancel", coordinator.onGpsTick(farAway))
        }
        advanceUntilIdle()
        assertTrue(
            "flight must still be pending, not wedged",
            coordinator.state.value is RouteCoordinator.NavigationState.Calculating,
        )

        // The slow response lands: result is published, navigation keeps working.
        provider.gate!!.complete(Unit)
        coordinator.awaitRouteIdle()
        val state = coordinator.state.value
        assertTrue("expected Navigating, got $state", state is RouteCoordinator.NavigationState.Navigating)
        assertEquals("reroute", (state as RouteCoordinator.NavigationState.Navigating).route.summary)
        // Seed call + exactly one re-route flight: no duplicate requests.
        assertEquals(2, provider.calls)
        coordinator.close()
    }

    @Test
    fun `cancelling an explicit request restores the previous navigating state`() = runTest {
        var now = 50_000L
        val provider = GateProvider()
        provider.reply = { RouteResult.Success(seedRoute("seed"), "Gate") }
        val chain = RoutingChain(listOf(provider), timeProvider = { now })
        val coordinator = RouteCoordinator(
            routingChain = chain,
            ownerScope = this,
            debounceIntervalMs = 0L,
            timeProvider = { now },
        )

        // Seed an active navigation with a fast provider...
        assertTrue(coordinator.requestRoute(origin, destination, force = true) is RouteResult.Success)

        // ...then slow the network and start an explicit re-route in a child job.
        provider.gate = CompletableDeferred()
        val job = launch {
            coordinator.requestRoute(farAway, destination, force = true)
        }
        advanceUntilIdle()
        assertTrue(
            coordinator.state.value is RouteCoordinator.NavigationState.Calculating,
        )

        // Cancelling the caller (e.g. a torn-down UI scope) must restore
        // the previous Navigating state, never leave Calculating behind.
        job.cancel()
        advanceUntilIdle()
        val state = coordinator.state.value
        assertTrue("expected restored Navigating, got $state", state is RouteCoordinator.NavigationState.Navigating)
        assertEquals("seed", (state as RouteCoordinator.NavigationState.Navigating).route.summary)
        coordinator.close()
    }
}
