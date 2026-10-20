package vn.vumap.core.model

import kotlin.math.cos

/**
 * Geographic bounding box defined by WGS84 coordinate bounds.
 *
 * Used for bounding-box spatial queries in offline sign and road data.
 */
data class BoundingBox(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double
) {
    init {
        require(!minLat.isNaN() && !maxLat.isNaN() && !minLon.isNaN() && !maxLon.isNaN()) {
            "BoundingBox coordinates must not be NaN"
        }
        require(minLat <= maxLat) { "minLat ($minLat) must be <= maxLat ($maxLat)" }
        require(minLon <= maxLon) { "minLon ($minLon) must be <= maxLon ($maxLon)" }
    }

    fun contains(point: GeoPoint): Boolean =
        point.latitude in minLat..maxLat && point.longitude in minLon..maxLon

    companion object {
        private const val METERS_PER_DEGREE_LAT = 111_320.0

        /**
         * Creates a bounding box around [center] with radius in meters.
         */
        fun fromCenter(center: GeoPoint, radiusMeters: Double): BoundingBox {
            require(radiusMeters >= 0.0) { "radiusMeters must be non-negative: $radiusMeters" }
            val deltaLat = radiusMeters / METERS_PER_DEGREE_LAT
            val cosLat = cos(Math.toRadians(center.latitude)).coerceAtLeast(0.01)
            val deltaLon = radiusMeters / (METERS_PER_DEGREE_LAT * cosLat)
            return BoundingBox(
                minLat = (center.latitude - deltaLat).coerceAtLeast(-90.0),
                minLon = (center.longitude - deltaLon).coerceAtLeast(-180.0),
                maxLat = (center.latitude + deltaLat).coerceAtMost(90.0),
                maxLon = (center.longitude + deltaLon).coerceAtMost(180.0)
            )
        }
    }
}
