package vn.vumap.core.model

/**
 * A geographic point in WGS-84 degrees.
 *
 * P0 skeleton model (project plan section 4, core/model). Pure Kotlin, no Android dependency,
 * covered by JVM unit tests.
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }
    }
}
