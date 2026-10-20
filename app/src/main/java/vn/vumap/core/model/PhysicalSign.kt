package vn.vumap.core.model

import vn.vumap.signs.SignType

/**
 * Physical road sign from an offline pack or local sensor observation.
 *
 * Maps to SQLite `points` table in Schema v1.
 */
data class PhysicalSign(
    val id: String,
    val type: SignType,
    val location: GeoPoint,
    val headingDeg: Double?,
    val valueKmh: Int?,
    val edgeId: String,
    val confidence: Float,
    val source: String,
    val isSynthetic: Boolean
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(edgeId.isNotBlank()) { "edgeId must not be blank" }
        require(source.isNotBlank()) { "source must not be blank" }
        require(!confidence.isNaN() && confidence in 0.0f..1.0f) {
            "confidence must be in 0..1: $confidence"
        }
        require(headingDeg == null || (!headingDeg.isNaN() && headingDeg in 0.0..360.0)) {
            "headingDeg must be null or in 0..360: $headingDeg"
        }
        if (type == SignType.P127) {
            require(valueKmh != null && valueKmh in 5..120) {
                "P.127 must have valueKmh between 5 and 120: $valueKmh"
            }
        } else if (type == SignType.R420 || type == SignType.R421) {
            require(valueKmh == null) {
                "Zone sign $type must not carry explicit valueKmh: $valueKmh"
            }
        }
    }
}
