package vn.vumap.feature.nav

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.vumap.core.model.GeoPoint

/**
 * Deterministic step-progress tests (PR2 P1 follow-up): the upcoming-maneuver
 * pointer must advance from live fixes — departure, approach, pass, arrival —
 * move only forward, and never launch network from on-route ticks.
 */
class RouteProgressTest {

    private val lat = 10.7725

    // Straight 6-point line east; ~109 m per 0.001 deg lon here, ~547 m total.
    private fun pt(i: Int) = GeoPoint(lat, 106.6980 + i * 0.001)

    private val geometry = List(6, ::pt)
    private val destination = pt(5)

    private fun progressRoute() = Route(
        geometry = geometry,
        distanceMeters = 550.0,
        durationSeconds = 90.0,
        steps = listOf(
            RouteStep("Xuat phat, di thang", 200.0, 30.0, geometry[0], "depart", null, ""),
            RouteStep("Re phai", 200.0, 30.0, geometry[2], "turn", "right", ""),
            RouteStep("Re trai", 150.0, 30.0, geometry[4], "turn", "left", ""),
        ),
    )

    private class ImmediateProvider(val route: Route) : RoutingProvider {
        override val providerName: String = "Immediate"
        override suspend fun route(from: GeoPoint, to: GeoPoint, options: CarOptions): RouteResult =
            RouteResult.Success(route, providerName)
    }

    private fun navigatingCoordinator(route: Route): RouteCoordinator {
        val coordinator = RouteCoordinator(
            routingChain = RoutingChain(listOf(ImmediateProvider(route))),
            debounceIntervalMs = 0L,
        )
        return coordinator
    }

    private fun RouteCoordinator.navigating(): RouteCoordinator.NavigationState.Navigating {
        val state = state.value
        assertTrue("expected Navigating, got $state", state is RouteCoordinator.NavigationState.Navigating)
        return state as RouteCoordinator.NavigationState.Navigating
    }

    @Test
    fun `departure tick selects the first maneuver`() = runTest {
        val coordinator = navigatingCoordinator(progressRoute())
        // No ownerScope passed: no flight can launch in tests below without one.
        assertTrue(coordinator.requestRoute(pt(0), destination, force = true) is RouteResult.Success)
        val state = coordinator.navigating()
        assertEquals(0, state.stepIndex)
        assertFalse(state.hasArrived)
        coordinator.close()
    }

    @Test
    fun `approaching a turn advances the pointer with remaining distance`() = runTest {
        val coordinator = navigatingCoordinator(progressRoute())
        coordinator.requestRoute(pt(0), destination, force = true)

        // One segment (~109 m) along: past the 25 m pass radius of step 0,
        // still ~109 m short of the step-1 point.
        assertFalse("on-route tick must not launch a request", coordinator.onGpsTick(pt(1)))
        val state = coordinator.navigating()
        assertEquals(1, state.stepIndex)
        assertTrue("remaining=${state.distanceToNextStepM}", state.distanceToNextStepM in 90.0..120.0)
        assertFalse(state.hasArrived)
        coordinator.close()
    }

    @Test
    fun `passing a maneuver advances past it`() = runTest {
        val coordinator = navigatingCoordinator(progressRoute())
        coordinator.requestRoute(pt(0), destination, force = true)
        coordinator.onGpsTick(pt(1))

        // ~33 m beyond the step-1 point, still on the line: maneuver passed.
        val past = GeoPoint(lat, 106.6980 + 2 * 0.001 + 0.0003)
        assertFalse(coordinator.onGpsTick(past))
        val state = coordinator.navigating()
        assertEquals(2, state.stepIndex)
        assertFalse(state.hasArrived)
        coordinator.close()
    }

    @Test
    fun `reaching the destination flags arrival`() = runTest {
        val coordinator = navigatingCoordinator(progressRoute())
        coordinator.requestRoute(pt(0), destination, force = true)

        assertFalse(coordinator.onGpsTick(destination))
        val state = coordinator.navigating()
        assertTrue(state.hasArrived)
        assertEquals(2, state.stepIndex)
        coordinator.close()
    }

    @Test
    fun `pointer never moves backward on GPS jumps`() = runTest {
        val coordinator = navigatingCoordinator(progressRoute())
        coordinator.requestRoute(pt(0), destination, force = true)
        coordinator.onGpsTick(pt(1))
        assertEquals(1, coordinator.navigating().stepIndex)

        // Jump back to the start: the pointer stays on step 1, and the remaining
        // distance must describe that retained maneuver (~219 m ahead) instead of
        // borrowing the already-passed departure point's zero distance, which
        // would fire an immediate turn command for a maneuver still far away.
        coordinator.onGpsTick(pt(0))
        val jumpedBack = coordinator.navigating()
        assertEquals(1, jumpedBack.stepIndex)
        assertTrue(
            "remaining must describe the retained maneuver, got ${jumpedBack.distanceToNextStepM}",
            jumpedBack.distanceToNextStepM in 190.0..240.0,
        )

        val step = progressRoute().steps[1]
        val prompt = NavigationVoice.stepPrompt(step, jumpedBack.distanceToNextStepM)
        assertNotNull("expected an approach prompt, got null", prompt)
        assertTrue("expected an approach prompt, got '$prompt'", prompt!!.startsWith("Sau "))

        coordinator.onGpsTick(destination)
        assertTrue(coordinator.navigating().hasArrived)
        coordinator.onGpsTick(pt(0))
        assertTrue(coordinator.navigating().hasArrived)
        coordinator.close()
    }

    @Test
    fun `turn prompt is an immediate command only inside the pass radius`() {
        val step = progressRoute().steps[1]

        // Immediate command at <= 40 m, distance-qualified approach beyond it,
        // and silence while the maneuver is still far away.
        assertEquals(step.instruction, NavigationVoice.stepPrompt(step, 40.0))
        val approach = NavigationVoice.stepPrompt(step, 250.0)
        assertNotNull(approach)
        assertTrue("expected a distance-qualified prompt, got '$approach'", approach!!.startsWith("Sau "))
        assertNull(NavigationVoice.stepPrompt(step, 601.0))
    }
}
