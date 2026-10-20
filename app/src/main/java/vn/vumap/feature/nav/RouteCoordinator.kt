package vn.vumap.feature.nav

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vn.vumap.core.model.GeoPoint

/**
 * High-level coordinator for active navigation route and GPS tick filtering.
 *
 * RULES (project plan section 3, 9; requirement 2):
 * - Debounces routing requests (minimum 3 seconds).
 * - "Khong goi moi GPS tick": filters out 1Hz GPS location updates while on-route.
 * - Triggers re-route only if destination changes or driver wanders off-route (> 40m).
 *
 * CONCURRENCY (PR2 P1 fix): routing never runs in a GPS-keyed effect lifetime.
 * [onGpsTick] is non-suspending and launches a single flight in the stable
 * [ownerScope]; newer 1 Hz fixes can neither cancel it nor pile up behind it.
 * A cancelled flight restores the pre-flight state instead of wedging
 * navigation in Calculating (generation-guarded, so a superseding flight wins).
 */
class RouteCoordinator(
    private val routingChain: RoutingChain,
    ownerScope: CoroutineScope? = null,
    private val debounceIntervalMs: Long = 3_000L,
    /**
     * Distance from the route geometry at which the traveler counts as
     * off-route. Defaults to [RoutingChain.CACHE_JOIN_RADIUS_M], the shared
     * navigability criterion: a cached route is only served to an origin inside
     * this radius, so it can never be immediately re-routed on the same geometry.
     */
    private val offRouteThresholdMeters: Double = RoutingChain.CACHE_JOIN_RADIUS_M,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
) {

    sealed interface NavigationState {
        object Idle : NavigationState
        data class Calculating(val destination: GeoPoint) : NavigationState
        data class Navigating(
            val route: Route,
            val destination: GeoPoint,
            val provider: String,
            val isFromCache: Boolean,
            /** Index of the upcoming maneuver in [Route.steps]. */
            val stepIndex: Int = 0,
            /** Remaining meters to the upcoming maneuver, quantized to 10 m. */
            val distanceToNextStepM: Double = 0.0,
            /** True once the traveler is within arrival radius of the destination. */
            val hasArrived: Boolean = false,
        ) : NavigationState
        data class Error(val message: String, val error: RoutingError) : NavigationState
    }

    private val _state = MutableStateFlow<NavigationState>(NavigationState.Idle)
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private val ownsScope = ownerScope == null
    private val scope = ownerScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var lastRequestTimeMs = 0L
    private var lastRequestedDestination: GeoPoint? = null
    // Single-flight tracking. flightId bumps on every new/superseding request;
    // a cancelled flight restores state only when no newer flight has started.
    private var flightId = 0L
    private var routeJob: Job? = null

    // Latest traveler position, used to seed step progress when a route publishes.
    private var lastKnownLocation: GeoPoint? = null

    /**
     * Request a route explicitly (e.g. user selected a destination).
     * Runs in the caller's coroutine; cancelling the caller restores the
     * pre-request state instead of leaving Calculating behind.
     */
    suspend fun requestRoute(
        from: GeoPoint,
        destination: GeoPoint,
        force: Boolean = false,
    ): RouteResult {
        val now = timeProvider()
        if (!force && (now - lastRequestTimeMs) < debounceIntervalMs) {
            // Debounced
            return RouteResult.Failure(
                error = RoutingError.RATE_LIMITED,
                message = "Yêu cầu định tuyến quá nhanh, vui lòng chờ 3 giây.",
            )
        }

        // Explicit requests supersede any tick-triggered flight.
        routeJob?.cancel()
        val id = ++flightId
        val previous = _state.value
        lastRequestTimeMs = now
        lastRequestedDestination = destination
        lastKnownLocation = from
        _state.value = NavigationState.Calculating(destination)

        try {
            val result = routingChain.route(from, destination)
            if (flightId == id) {
                publish(result, destination)
            }
            return result
        } catch (e: CancellationException) {
            if (flightId == id && _state.value is NavigationState.Calculating) {
                _state.value = previous
            }
            throw e
        }
    }

    /**
     * Handle incoming GPS location tick (called ~1Hz from LocationTracker).
     *
     * Never suspends the caller: returns immediately with true when a re-route
     * flight was launched in the stable owner scope, false otherwise. While a
     * flight is active, further ticks are ignored (single-flight) instead of
     * cancelling it, so a slow provider can never wedge navigation.
     */
    fun onGpsTick(currentLocation: GeoPoint): Boolean {
        lastKnownLocation = currentLocation
        val currentState = _state.value
        if (currentState !is NavigationState.Navigating) return false

        // Advance the displayed/upcoming maneuver from every fresh fix,
        // independent of whether a re-route flight launches below.
        updateProgress(currentState, currentLocation)

        if (routeJob?.isActive == true) return false

        val now = timeProvider()
        if ((now - lastRequestTimeMs) < debounceIntervalMs) return false

        if (!isOffRoute(currentLocation, currentState.route.geometry, offRouteThresholdMeters)) {
            return false
        }

        val id = ++flightId
        lastRequestTimeMs = now
        lastRequestedDestination = currentState.destination
        _state.value = NavigationState.Calculating(currentState.destination)
        routeJob = scope.launch {
            try {
                val result = routingChain.route(currentLocation, currentState.destination)
                if (flightId == id) {
                    publish(result, currentState.destination)
                }
            } catch (e: CancellationException) {
                if (flightId == id && _state.value is NavigationState.Calculating) {
                    _state.value = currentState
                }
            }
        }
        return true
    }

    /**
     * Clear current route and return to Idle. Supersedes any in-flight request
     * without letting its cancellation restore a stale state over Idle.
     */
    fun stopNavigation() {
        flightId++
        routeJob?.cancel()
        routeJob = null
        _state.value = NavigationState.Idle
        lastRequestedDestination = null
    }

    /**
     * Last destination the user asked to route to (null after [stopNavigation]).
     * Used by the honest offline fallback (`geo:` Intent) when routing fails.
     */
    fun lastDestination(): GeoPoint? = lastRequestedDestination

    /**
     * Test hook: suspends until the current tick-triggered flight settles.
     */
    suspend fun awaitRouteIdle() {
        routeJob?.join()
    }

    /**
     * Releases the internally owned scope. Call from composition dispose.
     * Never cancels a caller-provided [ownerScope].
     */
    fun close() {
        if (ownsScope) {
            scope.cancel()
        }
    }

    private fun publish(result: RouteResult, destination: GeoPoint) {
        when (result) {
            is RouteResult.Success -> {
                val frame = lastKnownLocation?.let { progressFrame(result.route, it) }
                val stepIndex = frame?.let { rawStepIndex(result.route, it) } ?: 0
                _state.value = NavigationState.Navigating(
                    route = result.route,
                    destination = destination,
                    provider = result.provider,
                    isFromCache = result.isFromCache,
                    stepIndex = stepIndex,
                    distanceToNextStepM = frame?.let { remainingToStep(it, stepIndex) } ?: 0.0,
                    hasArrived = frame?.arrived ?: false,
                )
            }
            is RouteResult.Failure -> {
                _state.value = NavigationState.Error(
                    message = result.message,
                    error = result.error,
                )
            }
        }
    }

    /**
     * Advances the upcoming-maneuver pointer from a fresh traveler fix.
     *
     * Pure position bookkeeping (no network): projects the fix onto the route
     * geometry, marks every maneuver whose point has been passed, and flags
     * arrival near the destination. Only moves forward, never backward.
     */
    private fun updateProgress(current: NavigationState.Navigating, location: GeoPoint) {
        val frame = progressFrame(current.route, location) ?: return
        // The index only moves forward, so the distance must be measured for the
        // *retained* maneuver. After a backward GPS jump the raw index points
        // back at an already-passed maneuver; copying its near-zero remaining
        // distance would announce an immediate turn for a maneuver still
        // hundreds of meters away. Index and distance always describe the same
        // maneuver.
        val stepIndex = maxOf(current.stepIndex, rawStepIndex(current.route, frame))
        val remaining = remainingToStep(frame, stepIndex)
        val arrived = current.hasArrived || frame.arrived
        if (stepIndex != current.stepIndex ||
            remaining != current.distanceToNextStepM ||
            arrived != current.hasArrived
        ) {
            _state.value = current.copy(
                stepIndex = stepIndex,
                distanceToNextStepM = remaining,
                hasArrived = arrived,
            )
        }
    }

    /** Position of a fix relative to the route geometry. */
    private class ProgressFrame(
        val cumulativeM: DoubleArray,
        val stepGeometryIndex: List<Int>,
        val traveledM: Double,
        val arrived: Boolean,
    )

    /**
     * Projects [location] onto [route]: cumulative length at every geometry
     * vertex, the vertex nearest each maneuver, and how far along the route the
     * traveler is. Returns null when the route cannot carry progress.
     */
    private fun progressFrame(route: Route, location: GeoPoint): ProgressFrame? {
        val geometry = route.geometry
        if (geometry.size < 2 || route.steps.isEmpty()) return null
        val cumulative = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) {
            cumulative[i] = cumulative[i - 1] + RoutingChain.distanceMeters(
                geometry[i - 1].latitude, geometry[i - 1].longitude,
                geometry[i].latitude, geometry[i].longitude,
            )
        }
        val traveled = projectTraveled(location, geometry, cumulative)
        return ProgressFrame(
            cumulativeM = cumulative,
            stepGeometryIndex = route.steps.map { nearestGeometryIndex(it.location, geometry) },
            traveledM = traveled,
            arrived = traveled >= cumulative.last() - ARRIVAL_RADIUS_M,
        )
    }

    /** Upcoming maneuver for the raw (non-monotonic) [frame] position. */
    private fun rawStepIndex(route: Route, frame: ProgressFrame): Int {
        var index = 0
        while (index < route.steps.lastIndex &&
            frame.traveledM >= frame.cumulativeM[frame.stepGeometryIndex[index]] + STEP_PASS_RADIUS_M
        ) {
            index++
        }
        return index
    }

    /** Meters left to the maneuver at [stepIndex], quantized to 10 m. */
    private fun remainingToStep(frame: ProgressFrame, stepIndex: Int): Double {
        val remaining = (frame.cumulativeM[frame.stepGeometryIndex[stepIndex]] - frame.traveledM)
            .coerceAtLeast(0.0)
        // Quantize to 10 m so GPS jitter does not churn UI state and TTS.
        return (remaining / 10.0).toInt() * 10.0
    }

    /** Distance traveled along [geometry]: cumulative meters of the closest projection. */
    private fun projectTraveled(
        point: GeoPoint,
        geometry: List<GeoPoint>,
        cumulative: DoubleArray,
    ): Double {
        var bestDist = Double.MAX_VALUE
        var bestTraveled = 0.0
        for (i in 0 until geometry.size - 1) {
            val a = geometry[i]
            val b = geometry[i + 1]
            val dx = b.longitude - a.longitude
            val dy = b.latitude - a.latitude
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) {
                0.0
            } else {
                (((point.longitude - a.longitude) * dx + (point.latitude - a.latitude) * dy) / len2)
                    .coerceIn(0.0, 1.0)
            }
            val projLat = a.latitude + t * dy
            val projLon = a.longitude + t * dx
            val dist = RoutingChain.distanceMeters(point.latitude, point.longitude, projLat, projLon)
            if (dist < bestDist) {
                bestDist = dist
                val segLen = RoutingChain.distanceMeters(
                    a.latitude, a.longitude, b.latitude, b.longitude,
                )
                bestTraveled = cumulative[i] + t * segLen
            }
        }
        return bestTraveled
    }

    private fun nearestGeometryIndex(point: GeoPoint, geometry: List<GeoPoint>): Int {
        var bestIndex = 0
        var bestDist = Double.MAX_VALUE
        for (i in geometry.indices) {
            val dist = RoutingChain.distanceMeters(
                point.latitude, point.longitude,
                geometry[i].latitude, geometry[i].longitude,
            )
            if (dist < bestDist) {
                bestDist = dist
                bestIndex = i
            }
        }
        return bestIndex
    }

    /**
     * Determines whether [point] is further than [thresholdMeters] from any segment in [geometry].
     */
    fun isOffRoute(point: GeoPoint, geometry: List<GeoPoint>, thresholdMeters: Double): Boolean {
        if (geometry.isEmpty()) return true
        if (geometry.size == 1) {
            return RoutingChain.distanceMeters(
                point.latitude,
                point.longitude,
                geometry[0].latitude,
                geometry[0].longitude,
            ) > thresholdMeters
        }

        var minDistance = Double.MAX_VALUE
        for (i in 0 until geometry.size - 1) {
            val d = distanceToSegment(point, geometry[i], geometry[i + 1])
            if (d < minDistance) minDistance = d
            if (minDistance <= thresholdMeters) return false
        }

        return minDistance > thresholdMeters
    }

    private fun distanceToSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val x0 = p.longitude
        val y0 = p.latitude
        val x1 = a.longitude
        val y1 = a.latitude
        val x2 = b.longitude
        val y2 = b.latitude

        val dx = x2 - x1
        val dy = y2 - y1

        if (dx == 0.0 && dy == 0.0) {
            return RoutingChain.distanceMeters(p.latitude, p.longitude, a.latitude, a.longitude)
        }

        // Project point onto segment
        val t = ((x0 - x1) * dx + (y0 - y1) * dy) / (dx * dx + dy * dy)
        val clampedT = t.coerceIn(0.0, 1.0)
        val projLat = y1 + clampedT * dy
        val projLon = x1 + clampedT * dx

        return RoutingChain.distanceMeters(p.latitude, p.longitude, projLat, projLon)
    }

    companion object {
        /** A maneuver counts as passed once traveled this far beyond its point. */
        const val STEP_PASS_RADIUS_M = 25.0

        /** Arrival flags once this close (along-route) to the destination. */
        const val ARRIVAL_RADIUS_M = 30.0
    }
}
