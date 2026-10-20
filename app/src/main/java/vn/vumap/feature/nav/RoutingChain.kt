package vn.vumap.feature.nav

import vn.vumap.core.model.GeoPoint
import java.util.concurrent.ConcurrentHashMap

/**
 * Prioritized routing chain with per-host cooldown, fallback, and honest offline caching.
 *
 * RULES (project plan section 0.3, 0.4, 0.5, 4, 9; requirement 2 & 5):
 * - Tries providers in order (e.g. ORS if key configured -> OSRM public -> Valhalla public).
 * - Per-host cooldown: when a host hits 429 Rate Limited, Timeout, or Server 5xx, it enters a
 *   temporary cooldown (default 30s) and subsequent requests skip to the next provider.
 * - Missing ORS key cleanly skips to public providers without cooldown.
 * - "Khong nhan OFFLINE ROUTING khi chi cache": if all network providers fail, a cached route
 *   may be served, but it is explicitly tagged with `isFromCache = true` and clearly marked
 *   as temporary cached data, never claiming true offline routing capability.
 */
class RoutingChain(
    val providers: List<RoutingProvider>,
    val cooldownDurationMs: Long = DEFAULT_COOLDOWN_MS,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
) {

    // Provider name -> cooldown expiry timestamp in milliseconds
    private val cooldownMap = ConcurrentHashMap<String, Long>()

    // Simple in-memory LRU / last route cache for offline fallback
    private var lastCachedRoute: Pair<RouteKey, Route>? = null

    data class RouteKey(val from: GeoPoint, val to: GeoPoint)

    suspend fun route(
        from: GeoPoint,
        to: GeoPoint,
        options: CarOptions = CarOptions(),
    ): RouteResult {
        val now = timeProvider()
        val failures = mutableListOf<RouteResult.Failure>()

        for (provider in providers) {
            val cooldownUntil = cooldownMap[provider.providerName] ?: 0L
            if (now < cooldownUntil) {
                val remainingSec = ((cooldownUntil - now) / 1000).coerceAtLeast(1)
                failures.add(
                    RouteResult.Failure(
                        error = RoutingError.COOLDOWN_ACTIVE,
                        message = "${provider.providerName} đang trong thời gian chờ (còn ${remainingSec}s)",
                        provider = provider.providerName,
                    ),
                )
                continue
            }

            val result = provider.route(from, to, options)

            when (result) {
                is RouteResult.Success -> {
                    // Cache successful route
                    lastCachedRoute = RouteKey(from, to) to result.route
                    return result
                }
                is RouteResult.Failure -> {
                    failures.add(result)

                    when (result.error) {
                        RoutingError.RATE_LIMITED,
                        RoutingError.TIMEOUT,
                        RoutingError.SERVER_UNAVAILABLE,
                        -> {
                            // Trigger per-host cooldown
                            cooldownMap[provider.providerName] = now + cooldownDurationMs
                        }
                        RoutingError.KEY_MISSING -> {
                            // Clean skip, do NOT put in cooldown
                        }
                        else -> Unit
                    }
                }
            }
        }

        // All providers failed: check if we have a cached route for this
        // destination that the current origin can actually follow. Both parts
        // are required: a request from unrelated C to B must never navigate
        // with A's stale route, and an origin the coordinator would call
        // off-route must not be handed a cached route it cannot follow.
        // When either check fails we fall through to the failure below so the
        // UI shows the error state with the external-map fallback.
        val cached = lastCachedRoute
        if (cached != null &&
            isNearby(cached.first.to, to, CACHE_RADIUS_M) &&
            isWithinGeometry(from, cached.second.geometry, CACHE_JOIN_RADIUS_M)
        ) {
            return RouteResult.Success(
                route = cached.second,
                provider = "Tuyến đã lưu tạm (Cache)",
                isFromCache = true,
            )
        }

        val primaryReason = failures.lastOrNull { it.error != RoutingError.KEY_MISSING }
            ?: failures.lastOrNull()
        val errorType = primaryReason?.error ?: RoutingError.ALL_PROVIDERS_FAILED
        val message = primaryReason?.message
            ?: "Tất cả các dịch vụ định tuyến đều không khả dụng."

        return RouteResult.Failure(
            error = errorType,
            message = message,
            provider = null,
            httpStatusCode = primaryReason?.httpStatusCode,
        )
    }

    /**
     * Checks if [providerName] is currently cooling down.
     */
    fun isProviderCoolingDown(providerName: String): Boolean {
        val now = timeProvider()
        val until = cooldownMap[providerName] ?: return false
        return now < until
    }

    /**
     * Clears all cooldowns (useful in tests).
     */
    fun resetCooldowns() {
        cooldownMap.clear()
        lastCachedRoute = null
    }

    private fun isNearby(p1: GeoPoint, p2: GeoPoint, thresholdMeters: Double): Boolean {
        val dist = distanceMeters(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
        return dist <= thresholdMeters
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 30_000L // 30 seconds

        /**
         * Destination proximity for cache reuse: the cached route serves only
         * when the requested destination is within this distance of the cached
         * destination.
         */
        const val CACHE_RADIUS_M = 100.0

        /**
         * Origin navigability criterion, shared with the off-route threshold of
         * [RouteCoordinator]: the current origin must be within this distance of
         * the cached geometry for the cache to be served.
         *
         * The two values must stay equal. An origin in the band between this
         * radius and [CACHE_RADIUS_M] would otherwise be served the cached route
         * and then immediately declared off-route by the coordinator, which
         * re-requests the same unusable geometry on every eligible GPS tick and
         * never exposes the honest failure/external-map fallback.
         */
        const val CACHE_JOIN_RADIUS_M = 40.0

        /**
         * True when [point] lies within [thresholdMeters] of any segment of
         * [geometry] (equirectangular projection is fine at these radii).
         */
        fun isWithinGeometry(point: GeoPoint, geometry: List<GeoPoint>, thresholdMeters: Double): Boolean {
            if (geometry.isEmpty()) return false
            if (geometry.size == 1) {
                return distanceMeters(
                    point.latitude, point.longitude,
                    geometry[0].latitude, geometry[0].longitude,
                ) <= thresholdMeters
            }
            for (i in 0 until geometry.size - 1) {
                if (distanceToSegmentMeters(point, geometry[i], geometry[i + 1]) <= thresholdMeters) {
                    return true
                }
            }
            return false
        }

        /** Cross-track distance from [p] to segment [a]–[b], in meters. */
        fun distanceToSegmentMeters(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
            val dx = b.longitude - a.longitude
            val dy = b.latitude - a.latitude
            if (dx == 0.0 && dy == 0.0) {
                return distanceMeters(p.latitude, p.longitude, a.latitude, a.longitude)
            }
            val t = (((p.longitude - a.longitude) * dx + (p.latitude - a.latitude) * dy) / (dx * dx + dy * dy))
                .coerceIn(0.0, 1.0)
            return distanceMeters(p.latitude, p.longitude, a.latitude + t * dy, a.longitude + t * dx)
        }

        /** Haversine formula for distance in meters. */
        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0 // Earth radius in meters
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
            val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
            return r * c
        }
    }
}
