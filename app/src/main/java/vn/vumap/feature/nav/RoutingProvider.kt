package vn.vumap.feature.nav

import vn.vumap.core.model.GeoPoint

/**
 * Driving profile options (project plan section 4 contract).
 */
data class CarOptions(
    val avoidTolls: Boolean = false,
    val avoidHighways: Boolean = false,
    val avoidFerries: Boolean = false,
    val language: String = "vi",
)

/**
 * Result of a route calculation.
 *
 * Distinguishes success from failure, and explicitly tags cached routes so that
 * cached prior routes are never falsely reported as offline routing (project plan section 0.5).
 */
sealed interface RouteResult {
    data class Success(
        val route: Route,
        val provider: String,
        val calculatedAtMs: Long = System.currentTimeMillis(),
        val isFromCache: Boolean = false,
    ) : RouteResult

    data class Failure(
        val error: RoutingError,
        val message: String,
        val provider: String? = null,
        val httpStatusCode: Int? = null,
    ) : RouteResult
}

/**
 * Typed routing failure reasons.
 */
enum class RoutingError {
    NETWORK_OFFLINE,
    TIMEOUT,
    RATE_LIMITED,       // HTTP 429
    UNAUTHORIZED,       // HTTP 401/403
    NO_ROUTE_FOUND,
    SERVER_UNAVAILABLE, // HTTP 5xx
    KEY_MISSING,        // Optional key not configured; provider skipped cleanly
    COOLDOWN_ACTIVE,    // Host currently in cooldown due to previous 429/timeout
    ALL_PROVIDERS_FAILED,
}

/**
 * Computed navigation route.
 *
 * Distance in meters, duration in seconds (standard OSRM/Valhalla units).
 * Geometry decoded to a list of WGS-84 [GeoPoint] coordinates.
 */
data class Route(
    val geometry: List<GeoPoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<RouteStep>,
    val summary: String = "",
    val polylinePrecision: Int = 6,
)

/**
 * Turn-by-turn route step.
 */
data class RouteStep(
    val instruction: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val location: GeoPoint,
    val maneuverType: String,
    val maneuverModifier: String? = null,
    val streetName: String = "",
)

/**
 * Contract for online and fallback routing providers (project plan section 4).
 *
 * Implementations must be thread-safe and non-blocking (suspend fun).
 */
interface RoutingProvider {
    val providerName: String

    /**
     * Compute a driving route between [from] and [to].
     */
    suspend fun route(
        from: GeoPoint,
        to: GeoPoint,
        options: CarOptions = CarOptions(),
    ): RouteResult
}
