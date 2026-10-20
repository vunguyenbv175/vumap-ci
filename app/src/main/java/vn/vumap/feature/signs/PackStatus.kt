package vn.vumap.feature.signs

/**
 * Health and availability status of an offline signs/roads pack.
 */
sealed interface PackStatus {
    /**
     * The pack is validated and ready for read-only queries.
     */
    data class Ready(
        val format: String,
        val formatVersion: Int,
        val schemaVersion: Int,
        val source: String,
        val license: String,
        val version: String,
        val isSynthetic: Boolean,
        val edgeCount: Int,
        val pointCount: Int,
        val sha256: String,
        val contentSha256: String?,
        /** Schema-v2 road evidence rows; 0 for v1 packs or packs without road data. */
        val roadLimitCount: Int = 0
    ) : PackStatus

    /**
     * The pack is missing, corrupted, incompatible, or failed validation.
     * Queries must return empty/UNKNOWN results with zero fabricated alerts.
     */
    data class Unavailable(
        val reason: String,
        val errors: List<String> = emptyList()
    ) : PackStatus
}
