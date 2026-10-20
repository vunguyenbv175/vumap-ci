package vn.vumap.core.model

import vn.vumap.signs.CarriagewayType

/**
 * Road edge segment stored in an offline pack.
 *
 * Maps to SQLite `edges` table in Schema v1.
 */
data class RoadEdge(
    val edgeId: String,
    val carriageway: CarriagewayType,
    val level: Int,
    val roadClass: String? = null,
    val lanes: Int? = null,
    val headingDeg: Double? = null
) {
    init {
        require(edgeId.isNotBlank()) { "edgeId must not be blank" }
        require(headingDeg == null || (!headingDeg.isNaN() && headingDeg in 0.0..360.0)) {
            "headingDeg must be null or in 0..360: $headingDeg"
        }
        require(lanes == null || lanes >= 1) {
            "lanes must be null or >= 1: $lanes"
        }
    }
}
