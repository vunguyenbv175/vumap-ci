package vn.vumap.feature.signs

import org.json.JSONObject

/**
 * Metadata record for the currently committed and active offline sign pack generation.
 */
data class ActivePackPointer(
    val generationId: String,
    val packRelativePath: String,
    val manifestRelativePath: String,
    val sha256: String,
    val version: String,
    val source: String,
    val license: String,
    val isSynthetic: Boolean,
    val pointCount: Int,
    val edgeCount: Int,
    val importedAtMs: Long
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("generationId", generationId)
        obj.put("packRelativePath", packRelativePath)
        obj.put("manifestRelativePath", manifestRelativePath)
        obj.put("sha256", sha256)
        obj.put("version", version)
        obj.put("source", source)
        obj.put("license", license)
        obj.put("isSynthetic", isSynthetic)
        obj.put("pointCount", pointCount)
        obj.put("edgeCount", edgeCount)
        obj.put("importedAtMs", importedAtMs)
        return obj.toString(2)
    }

    companion object {
        fun fromJson(jsonStr: String): ActivePackPointer? {
            return try {
                val obj = JSONObject(jsonStr)
                ActivePackPointer(
                    generationId = obj.getString("generationId"),
                    packRelativePath = obj.getString("packRelativePath"),
                    manifestRelativePath = obj.getString("manifestRelativePath"),
                    sha256 = obj.getString("sha256"),
                    version = obj.getString("version"),
                    source = obj.getString("source"),
                    license = obj.optString("license", ""),
                    isSynthetic = obj.getBoolean("isSynthetic"),
                    pointCount = obj.getInt("pointCount"),
                    edgeCount = obj.getInt("edgeCount"),
                    importedAtMs = obj.getLong("importedAtMs")
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
