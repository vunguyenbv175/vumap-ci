package vn.vumap.feature.search

import vn.vumap.core.model.GeoPoint
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Straight-line distance used for display/ranking ONLY when a real origin is
 * available (the caller passes a live GPS fix or null). It is never used to
 * fabricate an origin, and a null origin shows no distance at all.
 *
 * Pure Kotlin, JVM unit-tested.
 */
object SearchDistance {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** Great-circle (haversine) distance in meters. */
    fun meters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(h).coerceIn(0.0, 1.0))
    }

    /** Compact Vietnamese label, e.g. "cách bạn 850 m", "cách bạn 1,2 km". */
    fun label(meters: Double): String {
        val safe = if (meters.isFinite()) meters.coerceAtLeast(0.0) else 0.0
        return when {
            safe < 1_000.0 -> "cách bạn ${safe.roundToInt()} m"
            safe < 10_000.0 -> {
                val km = String.format(Locale.US, "%.1f", safe / 1_000.0)
                    .replace('.', ',')
                "cách bạn $km km"
            }
            else -> "cách bạn ${(safe / 1_000.0).roundToInt()} km"
        }
    }
}
