package vn.vumap.feature.signs

import vn.vumap.core.model.BoundingBox
import vn.vumap.core.model.GeoPoint
import vn.vumap.core.model.PhysicalSign
import vn.vumap.core.model.RoadEdge

/**
 * Read-only interface for querying offline signs and road edges.
 *
 * Contract per project plan section 4. All implementations must execute
 * database queries off the main thread (e.g. [kotlinx.coroutines.Dispatchers.IO]).
 */
interface SignProvider {
    /**
     * Query physical signs within [area], bounded to [limit] records.
     */
    suspend fun nearby(area: BoundingBox, limit: Int = DEFAULT_LIMIT): List<PhysicalSign>

    /**
     * Query physical signs within [radiusMeters] of [point], bounded to [limit] records.
     */
    suspend fun nearby(point: GeoPoint, radiusMeters: Double, limit: Int = DEFAULT_LIMIT): List<PhysicalSign>

    /**
     * Look up a road edge by its unique identifier [edgeId].
     */
    suspend fun getEdge(edgeId: String): RoadEdge?

    /**
     * Query road edges within [area], bounded to [limit] records.
     */
    suspend fun nearbyEdges(area: BoundingBox, limit: Int = DEFAULT_LIMIT): List<RoadEdge>

    /**
     * Returns current pack availability and provenance status.
     */
    suspend fun packStatus(): PackStatus

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
    }
}
