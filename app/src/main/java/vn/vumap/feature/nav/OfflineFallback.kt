package vn.vumap.feature.nav

import vn.vumap.core.model.GeoPoint
import java.net.URLEncoder
import java.util.Locale

/**
 * Honest offline fallback: VUMAP P0 cannot compute routes on-device, so when all
 * online providers fail it hands the destination to an installed offline map app
 * (Organic Maps / OsmAnd / any `geo:` handler) instead of claiming offline routing.
 *
 * Pure JVM functions (project plan section 3, 9) — covered by unit tests.
 */
object OfflineFallback {

    /**
     * Builds an Android `geo:` URI for [destination], e.g.
     * `geo:10.776800,106.703200?q=10.776800,106.703200`. An optional human label
     * is appended as `q=lat,lon(label)` with minimal percent-encoding.
     */
    fun geoUri(destination: GeoPoint, label: String? = null): String {
        val lat = formatCoord(destination.latitude)
        val lon = formatCoord(destination.longitude)
        val query = if (label.isNullOrBlank()) {
            "$lat,$lon"
        } else {
            "$lat,$lon(${encodeLabel(label)})"
        }
        return "geo:$lat,$lon?q=$query"
    }

    private fun formatCoord(value: Double): String =
        String.format(Locale.US, "%.6f", value)

    private fun encodeLabel(label: String): String =
        URLEncoder.encode(label.trim(), "UTF-8").replace("+", "%20")
}
