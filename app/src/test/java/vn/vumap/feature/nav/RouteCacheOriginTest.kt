package vn.vumap.feature.nav

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.vumap.core.model.GeoPoint

/**
 * Cache-origin validation (PR2 P2): a cached A-to-B route must never navigate
 * an unrelated origin C, even for the same destination B. Only an origin that
 * can join the cached geometry reuses it; otherwise the chain fails honestly
 * (error state + external-map fallback) instead of navigating a stale route.
 */
class RouteCacheOriginTest {

    private fun readFixture(name: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/$name")
            ?: throw IllegalStateException("Fixture fixtures/$name not found on classpath")
        return stream.bufferedReader().use { it.readText() }
    }

    private class ScriptedProvider(var next: () -> RouteResult) : RoutingProvider {
        override val providerName: String = "Scripted"
        var calls = 0

        override suspend fun route(
            from: GeoPoint,
            to: GeoPoint,
            options: CarOptions,
        ): RouteResult {
            calls++
            return next()
        }
    }

    private fun seededChain(): Triple<RoutingChain, ScriptedProvider, Route> {
        val route = OsrmResponseParser.parse(readFixture("osrm_hanoi.json"))
        val provider = ScriptedProvider(next = { RouteResult.Success(route, "Scripted") })
        val chain = RoutingChain(listOf(provider))
        return Triple(chain, provider, route)
    }

    private fun goOffline(provider: ScriptedProvider) {
        provider.next = {
            RouteResult.Failure(
                error = RoutingError.NETWORK_OFFLINE,
                message = "offline",
                provider = "Scripted",
            )
        }
    }

    @Test
    fun `changed origin far from cached geometry fails instead of navigating stale route`() = runBlocking {
        val (chain, provider, route) = seededChain()
        val fromA = route.geometry.first()
        val toB = route.geometry.last()

        // Seed the A -> B cache while online.
        assertTrue(chain.route(fromA, toB) is RouteResult.Success)

        // Offline, same destination B but unrelated far origin C (HCMC, ~1,000 km away).
        goOffline(provider)
        val farC = GeoPoint(10.8000, 106.6500)
        val result = chain.route(farC, toB)
        assertTrue(
            "far origin must fail, never navigate with A's cached route (got $result)",
            result is RouteResult.Failure,
        )
        assertFalse(
            (result as? RouteResult.Success)?.isFromCache ?: false,
        )
    }

    @Test
    fun `same destination with joinable origin reuses the cached route`() = runBlocking {
        val (chain, provider, route) = seededChain()
        val fromA = route.geometry.first()
        val toB = route.geometry.last()
        assertTrue(chain.route(fromA, toB) is RouteResult.Success)

        goOffline(provider)

        // Same origin: cache hit, explicitly tagged.
        val same = chain.route(fromA, toB)
        assertTrue(same is RouteResult.Success && same.isFromCache)

        // Mid-route origin (can join the geometry): cache hit as well.
        val mid = chain.route(route.geometry[route.geometry.size / 2], toB)
        assertTrue(mid is RouteResult.Success && mid.isFromCache)
    }

    @Test
    fun `different destination never serves the cache`() = runBlocking {
        val (chain, provider, route) = seededChain()
        val fromA = route.geometry.first()
        val toB = route.geometry.last()
        assertTrue(chain.route(fromA, toB) is RouteResult.Success)

        goOffline(provider)
        val elsewhere = GeoPoint(10.7725, 106.6980) // Ben Thanh, HCMC
        val result = chain.route(fromA, elsewhere)
        assertTrue(result is RouteResult.Failure)
    }

    /** Straight synthetic A->B line so origins can be placed at an exact offset. */
    private fun syntheticRoute(): Route {
        val a = GeoPoint(21.0285, 105.8542)
        val b = GeoPoint(21.0285, 105.8642)
        return Route(
            geometry = listOf(a, b),
            distanceMeters = RoutingChain.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude),
            durationSeconds = 90.0,
            steps = listOf(
                RouteStep("Khoi hanh", 0.0, 0.0, a, "depart", null, ""),
                RouteStep("Den noi", 0.0, 0.0, b, "arrive", null, ""),
            ),
        )
    }

    /** [distanceMeters] due north of [point] (1 deg latitude ~ 111 194.93 m). */
    private fun offsetNorth(point: GeoPoint, distanceMeters: Double): GeoPoint =
        GeoPoint(point.latitude + distanceMeters / 111_194.93, point.longitude)

    @Test
    fun `origin inside the 40-100 m band is not served the cached route`() = runBlocking {
        val route = syntheticRoute()
        val provider = ScriptedProvider(next = { RouteResult.Success(route, "Scripted") })
        val chain = RoutingChain(listOf(provider))
        val fromA = route.geometry.first()
        val toB = route.geometry.last()
        assertTrue(chain.route(fromA, toB) is RouteResult.Success)

        goOffline(provider)

        // 60 m off the cached line: inside the old 100 m cache radius but outside
        // the 40 m navigability criterion the coordinator enforces.
        val offset = offsetNorth(fromA, 60.0)
        assertEquals(
            60.0,
            RoutingChain.distanceToSegmentMeters(offset, route.geometry[0], route.geometry[1]),
            1.0,
        )
        val result = chain.route(offset, toB)
        assertTrue("origin 60 m off the cached geometry must fail, got $result", result is RouteResult.Failure)

        // 30 m off is navigable, so the cache is still reused and honestly tagged.
        val joinable = offsetNorth(fromA, 30.0)
        val reuse = chain.route(joinable, toB)
        assertTrue("navigable origin must reuse the cache, got $reuse", reuse is RouteResult.Success && reuse.isFromCache)
    }

    @Test
    fun `origin in the 40-100 m band exposes the honest failure and stops rerouting`() = runTest {
        val route = syntheticRoute()
        val provider = ScriptedProvider(next = { RouteResult.Success(route, "Scripted") })
        var now = 100_000L
        val chain = RoutingChain(listOf(provider), timeProvider = { now })
        val coordinator = RouteCoordinator(
            routingChain = chain,
            ownerScope = this,
            timeProvider = { now },
        )
        val fromA = route.geometry.first()
        val toB = route.geometry.last()

        // Online: a navigable route is established (and cached).
        assertTrue(coordinator.requestRoute(fromA, toB, force = true) is RouteResult.Success)
        assertTrue(coordinator.state.value is RouteCoordinator.NavigationState.Navigating)

        // Providers fail and the traveler sits 60 m off the cached line: the
        // coordinator calls it off-route, so the chain must not answer with the
        // same unusable cached geometry.
        goOffline(provider)
        now += 5_000L
        assertTrue(
            "60 m off the cached line must start a re-route flight",
            coordinator.onGpsTick(offsetNorth(fromA, 60.0)),
        )
        advanceUntilIdle()

        val state = coordinator.state.value
        assertTrue(
            "cache must not be served to an origin the coordinator calls off-route, got $state",
            state is RouteCoordinator.NavigationState.Error,
        )
        assertEquals("the external-map fallback needs the destination", toB, coordinator.lastDestination())
        val callsAfterFailure = provider.calls

        // Later ticks must not pile reroutes onto the same unusable geometry.
        repeat(3) {
            now += 10_000L
            assertFalse(
                "failure is already exposed, no further reroute",
                coordinator.onGpsTick(offsetNorth(fromA, 60.0)),
            )
        }
        advanceUntilIdle()
        assertEquals("no provider calls after the failure is exposed", callsAfterFailure, provider.calls)
        assertEquals("seed + exactly one re-route attempt", 2, provider.calls)
        coordinator.close()
    }
}
