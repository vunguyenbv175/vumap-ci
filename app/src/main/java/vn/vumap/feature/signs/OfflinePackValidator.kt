package vn.vumap.feature.signs

import org.json.JSONObject
import vn.vumap.feature.nav.RoutingChain
import vn.vumap.feature.signs.db.SignPackCursor
import vn.vumap.feature.signs.db.SignPackDb
import vn.vumap.feature.signs.db.SignPackDbFactory
import vn.vumap.signs.CarriagewayType
import vn.vumap.signs.SignType
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Result of validating an offline pack and its manifest.
 */
data class OfflinePackValidationResult(
    val isOk: Boolean,
    val errors: List<String>,
    val metadata: PackStatus.Ready?
) {
    companion object {
        fun ok(metadata: PackStatus.Ready): OfflinePackValidationResult =
            OfflinePackValidationResult(isOk = true, errors = emptyList(), metadata = metadata)

        fun reject(errors: List<String>): OfflinePackValidationResult =
            OfflinePackValidationResult(isOk = false, errors = errors, metadata = null)
    }
}

/**
 * Validates offline SQLite `.vupack` files against schema v1/v2 and companion manifests.
 *
 * Enforces the contract defined in project plan section 6 and `feature/signs/tools/pack/`:
 * bounded file size, raw SHA-256 integrity, SQLite `PRAGMA integrity_check`,
 * schema version (v1 sign/zone packs and v2 packs that add the optional
 * `road_limits` evidence table), required tables, row types/ranges, bounding
 * box (WGS84 VN), referential integrity, and synthetic fixture declarations.
 *
 * Road evidence rows are typed exactly like the Java `RoadLimit` contract:
 * supported rows must carry an integer 5..120 value; conditional/lane/vehicle
 * rows must carry no value; unknown direction is allowed and abstains later.
 */
class OfflinePackValidator(
    private val dbFactory: SignPackDbFactory,
    private val syntheticOnly: Boolean = true
) {
    companion object {
        const val FORMAT = "vupack"
        const val FORMAT_VERSION = 1
        /** Schema v1: sign/zone tables only. */
        const val SCHEMA_VERSION = 1
        /** Schema v2: v1 tables plus the optional road_limits evidence table. */
        const val SCHEMA_VERSION_ROAD_LIMITS = 2
        val SUPPORTED_SCHEMA_VERSIONS = setOf(SCHEMA_VERSION, SCHEMA_VERSION_ROAD_LIMITS)

        const val MAX_BYTES = 5L * 1024 * 1024 // 5 MiB cap
        const val MIN_POINTS = 1
        const val MAX_POINTS_SYNTHETIC = 50
        const val MAX_POINTS_REAL = 500_000

        const val MIN_LAT = 8.0
        const val MAX_LAT = 23.5
        const val MIN_LON = 102.0
        const val MAX_LON = 110.0

        const val DUPLICATE_METERS = 2.0
        const val P127_MIN_KMH = 5
        const val P127_MAX_KMH = 120

        // Road evidence (schema v2), mirroring the host pack_format bounds.
        const val ROAD_LIMIT_MIN_KMH = 5
        const val ROAD_LIMIT_MAX_KMH = 120
        const val MAX_ROAD_LIMITS_SYNTHETIC = 50
        const val MAX_ROAD_LIMITS_REAL = 500_000
        /**
         * Maximum rows the reader fetches for one exact edge. Packs above this
         * bound are rejected so a query LIMIT can never truncate evidence.
         */
        const val MAX_ROAD_LIMITS_PER_EDGE = 32
        val ROAD_DIRECTIONS = setOf("FORWARD", "BACKWARD", "BOTH", "UNKNOWN")
        val ROAD_APPLICABILITIES = setOf(
            "SUPPORTED",
            "CONDITIONAL_UNSUPPORTED",
            "LANE_UNSUPPORTED",
            "VEHICLE_UNSUPPORTED"
        )

        private val REQUIRED_MANIFEST_KEYS = listOf(
            "format", "formatVersion", "schemaVersion",
            "source", "license", "version", "synthetic", "sha256"
        )
        private val REQUIRED_TABLES = listOf("pack_manifest", "edges", "points")
        private val REQUIRED_TABLES_V2 = REQUIRED_TABLES + "road_limits"
    }

    fun validate(packFile: File, manifestJson: String?): OfflinePackValidationResult {
        val errors = mutableListOf<String>()

        if (!packFile.exists()) {
            return OfflinePackValidationResult.reject(listOf("PACK_MISSING"))
        }
        if (!packFile.isFile) {
            return OfflinePackValidationResult.reject(listOf("PACK_NOT_A_FILE"))
        }

        val fileSize = packFile.length()
        if (fileSize <= 0) {
            errors.add("TRUNCATED_EMPTY_FILE")
        } else if (fileSize > MAX_BYTES) {
            errors.add("TOO_LARGE:$fileSize")
        }

        // 1. Manifest verification
        val manifestObj: JSONObject? = if (manifestJson.isNullOrBlank()) {
            errors.add("MISSING_MANIFEST")
            null
        } else {
            try {
                JSONObject(manifestJson)
            } catch (e: Exception) {
                errors.add("MANIFEST_PARSE_ERROR:${e.message}")
                null
            }
        }

        var declaredSha = ""
        var declaredFormat = ""
        var declaredFormatVersion = -1
        var declaredSchemaVersion = -1
        var declaredSource = ""
        var declaredLicense = ""
        var declaredVersion = ""
        var declaredSynthetic: Boolean? = null
        var declaredEdgeCount: Int? = null
        var declaredPointCount: Int? = null
        var declaredRoadLimitCount: Int? = null
        var declaredContentSha: String? = null

        if (manifestObj != null) {
            for (key in REQUIRED_MANIFEST_KEYS) {
                if (!manifestObj.has(key) || manifestObj.isNull(key) || manifestObj.optString(key).isEmpty()) {
                    errors.add("MISSING_MANIFEST_FIELD:$key")
                }
            }

            declaredFormat = manifestObj.optString("format", "")
            if (declaredFormat.isNotEmpty() && declaredFormat != FORMAT) {
                errors.add("UNSUPPORTED_FORMAT:$declaredFormat")
            }

            // Manifest numbers are evaluated at full 64-bit width and compared
            // exactly before any narrowing: optInt would wrap an oversized JSON
            // integer (2^32 + 1 -> 1) and accept a fractional value (1.0 -> 1),
            // letting a malformed manifest pass the supported-version gates.
            // Only integral JSON numbers are accepted, matching the host
            // pack validator, which rejects floats and oversized counts too.
            val formatVersionRaw = integralManifestNumber(manifestObj, "formatVersion")
            if (formatVersionRaw != FORMAT_VERSION.toLong()) {
                if (formatVersionRaw == null && manifestObj.has("formatVersion") && !manifestObj.isNull("formatVersion")) {
                    errors.add("MANIFEST_FIELD_TYPE:formatVersion")
                } else {
                    errors.add("UNSUPPORTED_FORMAT_VERSION:${formatVersionRaw ?: -1}")
                }
                declaredFormatVersion = -1
            } else {
                // Exactly equal to FORMAT_VERSION, so this narrowing is lossless
                declaredFormatVersion = formatVersionRaw.toInt()
            }

            val schemaVersionRaw = integralManifestNumber(manifestObj, "schemaVersion")
            if (schemaVersionRaw == null || schemaVersionRaw !in SUPPORTED_SCHEMA_VERSIONS.map { it.toLong() }) {
                if (schemaVersionRaw == null && manifestObj.has("schemaVersion") && !manifestObj.isNull("schemaVersion")) {
                    errors.add("MANIFEST_FIELD_TYPE:schemaVersion")
                } else {
                    errors.add("UNSUPPORTED_SCHEMA_MANIFEST:${schemaVersionRaw ?: -1}")
                }
                declaredSchemaVersion = -1
            } else {
                // Supported and exactly representable, so this narrowing is lossless
                declaredSchemaVersion = schemaVersionRaw.toInt()
            }

            declaredSource = manifestObj.optString("source", "")
            declaredLicense = manifestObj.optString("license", "")
            declaredVersion = manifestObj.optString("version", "")
            declaredSha = manifestObj.optString("sha256", "")

            if (manifestObj.has("synthetic")) {
                declaredSynthetic = manifestObj.optBoolean("synthetic")
                if (syntheticOnly && declaredSynthetic != true) {
                    errors.add("MANIFEST_SYNTHETIC_NOT_TRUE")
                }
            }

            if (manifestObj.has("edgeCount") && !manifestObj.isNull("edgeCount")) {
                val rawEdgeCount = integralManifestNumber(manifestObj, "edgeCount")
                if (rawEdgeCount == null) {
                    errors.add("MANIFEST_FIELD_TYPE:edgeCount")
                } else if (rawEdgeCount < 0L || rawEdgeCount > Int.MAX_VALUE) {
                    // Out of Int range, so it can never equal a real table size:
                    // report the full 64-bit value instead of a wrapped one
                    errors.add("MANIFEST_EDGE_COUNT_MISMATCH:$rawEdgeCount")
                } else {
                    declaredEdgeCount = rawEdgeCount.toInt()
                }
            }
            if (manifestObj.has("pointCount") && !manifestObj.isNull("pointCount")) {
                val rawPointCount = integralManifestNumber(manifestObj, "pointCount")
                if (rawPointCount == null) {
                    errors.add("MANIFEST_FIELD_TYPE:pointCount")
                } else if (rawPointCount < 0L || rawPointCount > Int.MAX_VALUE) {
                    errors.add("MANIFEST_POINT_COUNT_MISMATCH:$rawPointCount")
                } else {
                    declaredPointCount = rawPointCount.toInt()
                }
            }
            if (manifestObj.has("roadLimitCount") && !manifestObj.isNull("roadLimitCount")) {
                val rawRoadLimitCount = integralManifestNumber(manifestObj, "roadLimitCount")
                if (rawRoadLimitCount == null) {
                    errors.add("MANIFEST_FIELD_TYPE:roadLimitCount")
                } else if (rawRoadLimitCount < 0L || rawRoadLimitCount > Int.MAX_VALUE) {
                    errors.add("MANIFEST_ROAD_LIMIT_COUNT_MISMATCH:$rawRoadLimitCount")
                } else {
                    declaredRoadLimitCount = rawRoadLimitCount.toInt()
                }
            }
            if (manifestObj.has("contentSha256") && !manifestObj.isNull("contentSha256")) {
                declaredContentSha = manifestObj.optString("contentSha256")
            }
        }

        // 2. SHA-256 verification of pack bytes
        if (declaredSha.isBlank()) {
            errors.add("CHECKSUM_UNVERIFIED")
        } else if (packFile.exists() && fileSize > 0) {
            try {
                val actualSha = calculateSha256(packFile)
                if (!actualSha.equals(declaredSha, ignoreCase = true)) {
                    errors.add("CHECKSUM_MISMATCH")
                }
            } catch (e: Exception) {
                errors.add("CHECKSUM_READ_FAILED:${e.message}")
            }
        }

        // If file is missing, empty, oversized, or unreadable, reject early before SQLite operations
        if (fileSize <= 0 || fileSize > MAX_BYTES || errors.any { it.startsWith("CHECKSUM_READ_FAILED") }) {
            return OfflinePackValidationResult.reject(errors)
        }

        // 3. SQLite database structure & integrity
        var db: SignPackDb? = null
        try {
            db = dbFactory.openReadOnly(packFile)
        } catch (e: Exception) {
            errors.add("SQLITE_OPEN_FAILED:${e.message}")
            return OfflinePackValidationResult.reject(errors)
        }

        // Road evidence rows actually present in the pack (schema v2). Used for
        // the Ready metadata when the manifest omits an optional count.
        var actualRoadLimitCount = 0

        try {
            val integrity = db.pragma("integrity_check")
            if (integrity == null || !integrity.equals("ok", ignoreCase = true)) {
                errors.add("INTEGRITY_CHECK_FAILED:$integrity")
            }

            val userVersion = db.userVersion()
            if (userVersion !in SUPPORTED_SCHEMA_VERSIONS) {
                errors.add("UNSUPPORTED_SCHEMA:$userVersion")
            } else if (declaredSchemaVersion in SUPPORTED_SCHEMA_VERSIONS &&
                userVersion != declaredSchemaVersion
            ) {
                errors.add("SCHEMA_DB_MISMATCH:db=$userVersion manifest=$declaredSchemaVersion")
            }

            // Tables check
            val existingTables = mutableSetOf<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.let { existingTables.add(it) }
                }
            }
            val requiredTables = if (declaredSchemaVersion == SCHEMA_VERSION_ROAD_LIMITS) {
                REQUIRED_TABLES_V2
            } else {
                REQUIRED_TABLES
            }
            for (req in requiredTables) {
                if (!existingTables.contains(req)) {
                    errors.add("MISSING_TABLE:$req")
                }
            }

            if (errors.any { it.startsWith("MISSING_TABLE") }) {
                return OfflinePackValidationResult.reject(errors)
            }

            // In-pack pack_manifest metadata
            val inPackMeta = mutableMapOf<String, String>()
            db.rawQuery("SELECT key, value FROM pack_manifest").use { cursor ->
                while (cursor.moveToNext()) {
                    val k = cursor.getString(0)
                    val v = cursor.getString(1)
                    if (k != null && v != null) {
                        inPackMeta[k] = v
                    }
                }
            }

            if (inPackMeta["format"] != FORMAT) {
                errors.add("PACK_FORMAT_MISMATCH:${inPackMeta["format"]}")
            }
            if (inPackMeta["formatVersion"] != FORMAT_VERSION.toString()) {
                errors.add("UNSUPPORTED_FORMAT_VERSION_META:${inPackMeta["formatVersion"]}")
            }
            if (inPackMeta["schemaVersion"] !in SUPPORTED_SCHEMA_VERSIONS.map { it.toString() }) {
                errors.add("UNSUPPORTED_SCHEMA_META:${inPackMeta["schemaVersion"]}")
            } else if (declaredSchemaVersion in SUPPORTED_SCHEMA_VERSIONS &&
                inPackMeta["schemaVersion"] != declaredSchemaVersion.toString()
            ) {
                errors.add(
                    "SCHEMA_META_MISMATCH:meta=${inPackMeta["schemaVersion"]} " +
                        "manifest=$declaredSchemaVersion"
                )
            }
            if (inPackMeta["synthetic"] != "true") {
                errors.add("MISSING_SYNTHETIC_MARKER")
            }

            val inPackSynthetic = inPackMeta["synthetic"] == "true"
            if (declaredSynthetic != null && declaredSynthetic != inPackSynthetic) {
                errors.add("SYNTHETIC_MISMATCH")
            }

            if (declaredSource.isNotEmpty() && inPackMeta["source"] != declaredSource) {
                errors.add("MANIFEST_META_MISMATCH:source")
            }
            if (declaredLicense.isNotEmpty() && inPackMeta["license"] != declaredLicense) {
                errors.add("MANIFEST_META_MISMATCH:license")
            }
            if (declaredVersion.isNotEmpty() && inPackMeta["version"] != declaredVersion) {
                errors.add("MANIFEST_META_MISMATCH:version")
            }

            // 4. Edges table validation
            //
            // SQLite tables here are NOT STRICT, so a nominally numeric column
            // can hold text/blob/real storage classes. Storage classes are
            // confirmed from two independent sources before any typed getter is
            // called: the cursor adapter's getStorageType (Android
            // Cursor.getType; JDBC object identity) and the engine-evaluated
            // `typeof()` column. Both must agree, and malformed classes are
            // rejected before conversion so neither adapter can coerce
            // (60.5 -> 60, 'east' -> 0.0).
            val edgeIds = mutableSetOf<String>()
            var actualEdgeCount = 0
            val edgesSql = "SELECT edge_id, carriageway, level, road_class, lanes, heading_deg, " +
                "typeof(edge_id), typeof(carriageway), typeof(level), typeof(road_class), typeof(lanes), typeof(heading_deg) " +
                "FROM edges ORDER BY edge_id"
            db.rawQuery(edgesSql).use { cursor ->
                while (cursor.moveToNext()) {
                    val idx = actualEdgeCount++
                    val where = "EDGE[$idx]"

                    val edgeIdType = confirmStorageType(cursor, 0, 6, where, errors)
                    val cwType = confirmStorageType(cursor, 1, 7, where, errors)
                    val levelType = confirmStorageType(cursor, 2, 8, where, errors)
                    val roadClassType = confirmStorageType(cursor, 3, 9, where, errors)
                    val lanesType = confirmStorageType(cursor, 4, 10, where, errors)
                    val headingType = confirmStorageType(cursor, 5, 11, where, errors)

                    val edgeId = if (edgeIdType == "text") cursor.getString(0) else null
                    val cwStr = if (cwType == "text") cursor.getString(1) else null
                    val level = if (levelType == "integer") cursor.getLong(2) else null
                    val roadClass = if (roadClassType == "text") cursor.getString(3) else null
                    val lanes = if (lanesType == "integer") cursor.getLong(4) else null
                    val heading = if (headingType == "real" || headingType == "integer") cursor.getDouble(5) else null

                    if (edgeIdType != "text" || edgeId.isNullOrBlank()) {
                        errors.add("$where:BAD_EDGE_ID_TYPE")
                    } else {
                        edgeIds.add(edgeId)
                    }

                    if (cwType != "text" || cwStr.isNullOrBlank()) {
                        errors.add("$where:BAD_CARRIAGEWAY_TYPE")
                    } else {
                        try {
                            CarriagewayType.valueOf(cwStr)
                        } catch (e: IllegalArgumentException) {
                            errors.add("$where:UNKNOWN_CARRIAGEWAY:$cwStr")
                        }
                    }

                    // SQLite INTEGER is signed 64-bit: the full stored range is
                    // checked on the Long value before any narrowing, so an
                    // oversized integer can never truncate into an accepted
                    // in-range value.
                    if (levelType != "integer" || level == null || level < Int.MIN_VALUE || level > Int.MAX_VALUE) {
                        errors.add("$where:BAD_LEVEL_TYPE")
                    }

                    if (roadClassType != "null" && roadClassType != "text") {
                        errors.add("$where:BAD_ROAD_CLASS_TYPE")
                    } else if (roadClassType == "text" && roadClass.isNullOrBlank()) {
                        errors.add("$where:BAD_ROAD_CLASS_TYPE")
                    }

                    if (lanesType != "null") {
                        if (lanesType != "integer" || lanes == null || lanes > Int.MAX_VALUE) {
                            errors.add("$where:BAD_LANES_TYPE")
                        } else if (lanes < 1L) {
                            errors.add("$where:BAD_LANES_VALUE:$lanes")
                        }
                    }

                    if (headingType != "null") {
                        if (headingType != "real" && headingType != "integer") {
                            errors.add("$where:BAD_EDGE_HEADING_TYPE")
                        } else if (heading == null || heading < 0.0 || heading > 360.0) {
                            errors.add("$where:BAD_EDGE_HEADING:$heading")
                        }
                    }
                }
            }

            if (inPackMeta.containsKey("edgeCount") && inPackMeta["edgeCount"] != actualEdgeCount.toString()) {
                errors.add("EDGE_COUNT_MISMATCH:meta=${inPackMeta["edgeCount"]} actual=$actualEdgeCount")
            }
            if (declaredEdgeCount != null && declaredEdgeCount != actualEdgeCount) {
                errors.add("MANIFEST_EDGE_COUNT_MISMATCH:$declaredEdgeCount")
            }

            // 5. Points table validation
            data class TempPoint(val id: String, val type: String, val lat: Double, val lon: Double)
            val validatedPoints = mutableListOf<TempPoint>()
            val seenPointIds = mutableSetOf<String>()
            var actualPointCount = 0

            val pointsSql = "SELECT point_id, type, lat, lon, heading_deg, value_kmh, edge_id, confidence, source, " +
                "typeof(point_id), typeof(type), typeof(lat), typeof(lon), typeof(heading_deg), typeof(value_kmh), typeof(edge_id), typeof(confidence), typeof(source) " +
                "FROM points ORDER BY point_id"

            db.rawQuery(pointsSql).use { cursor ->
                while (cursor.moveToNext()) {
                    val idx = actualPointCount++
                    val where = "POINT[$idx]"

                    val pointIdType = confirmStorageType(cursor, 0, 9, where, errors)
                    val typeType = confirmStorageType(cursor, 1, 10, where, errors)
                    val latType = confirmStorageType(cursor, 2, 11, where, errors)
                    val lonType = confirmStorageType(cursor, 3, 12, where, errors)
                    val headingType = confirmStorageType(cursor, 4, 13, where, errors)
                    val valueKmhType = confirmStorageType(cursor, 5, 14, where, errors)
                    val edgeIdType = confirmStorageType(cursor, 6, 15, where, errors)
                    val confidenceType = confirmStorageType(cursor, 7, 16, where, errors)
                    val sourceType = confirmStorageType(cursor, 8, 17, where, errors)

                    // Guarded reads: only convert through typed getters when the
                    // stored class matches the schema v1 field type, so neither
                    // adapter can silently coerce a malformed value.
                    val pointId = if (pointIdType == "text") cursor.getString(0) else null
                    val typeStr = if (typeType == "text") cursor.getString(1) else null
                    val lat = if (latType == "real" || latType == "integer") cursor.getDouble(2) else null
                    val lon = if (lonType == "real" || lonType == "integer") cursor.getDouble(3) else null
                    val heading = if (headingType == "real" || headingType == "integer") cursor.getDouble(4) else null
                    // SQLite INTEGER is signed 64-bit: read as Long so the full
                    // stored range is checked before any narrowing to Int.
                    val valueKmh = if (valueKmhType == "integer") cursor.getLong(5) else null
                    val edgeId = if (edgeIdType == "text") cursor.getString(6) else null
                    val confidence = if (confidenceType == "real" || confidenceType == "integer") cursor.getDouble(7) else null
                    val source = if (sourceType == "text") cursor.getString(8) else null

                    var idOk = true
                    if (pointIdType != "text" || pointId.isNullOrBlank()) {
                        errors.add("$where:BAD_ID_TYPE")
                        idOk = false
                    } else if (!seenPointIds.add(pointId)) {
                        errors.add("$where:DUPLICATE_ID:$pointId")
                    }

                    var signType: SignType? = null
                    if (typeType != "text" || typeStr.isNullOrBlank()) {
                        errors.add("$where:BAD_TYPE:null")
                    } else {
                        try {
                            signType = SignType.valueOf(typeStr)
                        } catch (e: IllegalArgumentException) {
                            errors.add("$where:BAD_TYPE:$typeStr")
                        }
                    }

                    var coordsOk = true
                    val latNumeric = latType == "real" || latType == "integer"
                    val lonNumeric = lonType == "real" || lonType == "integer"
                    if (!latNumeric || !lonNumeric || lat == null || lon == null) {
                        errors.add("$where:BAD_COORD_TYPE")
                        coordsOk = false
                    } else if (lat < MIN_LAT || lat > MAX_LAT || lon < MIN_LON || lon > MAX_LON) {
                        errors.add("$where:OUT_OF_VN_RANGE:$lat,$lon")
                    }

                    val confNumeric = confidenceType == "real" || confidenceType == "integer"
                    if (!confNumeric || confidence == null || confidence < 0.0 || confidence > 1.0) {
                        errors.add("$where:BAD_CONFIDENCE:$confidence")
                    }

                    if (headingType != "null") {
                        val headingNumeric = headingType == "real" || headingType == "integer"
                        if (!headingNumeric || heading == null) {
                            errors.add("$where:BAD_HEADING_TYPE")
                        } else if (heading < 0.0 || heading >= 360.0) {
                            errors.add("$where:BAD_HEADING:$heading")
                        }
                    }

                    if (edgeIdType != "text" || edgeId.isNullOrBlank()) {
                        errors.add("$where:BAD_EDGE_TYPE")
                    } else if (!edgeIds.contains(edgeId)) {
                        errors.add("$where:UNKNOWN_EDGE:$edgeId")
                    }

                    if (sourceType != "text" || source.isNullOrBlank()) {
                        errors.add("$where:MISSING_SOURCE:$pointId")
                    }

                    if (signType == SignType.P127) {
                        // Range is enforced on the full 64-bit stored value before
                        // any narrowing, so 2^32 + 60 can never truncate to 60.
                        if (valueKmhType != "integer" || valueKmh == null ||
                            valueKmh < P127_MIN_KMH.toLong() || valueKmh > P127_MAX_KMH.toLong()
                        ) {
                            errors.add("$where:BAD_P127_VALUE:$valueKmh")
                        }
                    } else if (signType == SignType.R420 || signType == SignType.R421) {
                        if (valueKmhType != "null") {
                            if (valueKmhType != "integer") {
                                errors.add("$where:BAD_VALUE_TYPE")
                            } else {
                                errors.add("$where:ZONE_SIGN_MUST_NOT_CARRY_VALUE:$pointId")
                            }
                        }
                    }

                    if (idOk && coordsOk && signType != null && pointId != null && typeStr != null && lat != null && lon != null) {
                        validatedPoints.add(TempPoint(pointId, typeStr, lat, lon))
                    }
                }
            }

            if (inPackMeta.containsKey("pointCount") && inPackMeta["pointCount"] != actualPointCount.toString()) {
                errors.add("POINT_COUNT_MISMATCH:meta=${inPackMeta["pointCount"]} actual=$actualPointCount")
            }
            if (declaredPointCount != null && declaredPointCount != actualPointCount) {
                errors.add("MANIFEST_POINT_COUNT_MISMATCH:$declaredPointCount")
            }

            val maxBudget = if (inPackSynthetic) MAX_POINTS_SYNTHETIC else MAX_POINTS_REAL
            if (actualPointCount > maxBudget) {
                errors.add("TOO_MANY_POINTS:$actualPointCount")
            }

            // 6. Road evidence rows (schema v2 only). Same two-source storage
            // class confirmation as points/edges: unsupported applicability may
            // never carry a value, and a supported value must be in range.
            if (declaredSchemaVersion == SCHEMA_VERSION_ROAD_LIMITS) {
                val seenRoadLimitIds = mutableSetOf<String>()
                val roadLimitCountsByEdge = mutableMapOf<String, Int>()
                val roadLimitsSql =
                    "SELECT road_limit_id, edge_id, direction, value_kmh, applicability, " +
                        "confidence, provenance, typeof(road_limit_id), typeof(edge_id), " +
                        "typeof(direction), typeof(value_kmh), typeof(applicability), " +
                        "typeof(confidence), typeof(provenance) FROM road_limits ORDER BY road_limit_id"
                db.rawQuery(roadLimitsSql).use { cursor ->
                    while (cursor.moveToNext()) {
                        val idx = actualRoadLimitCount++
                        val where = "ROAD_LIMIT[$idx]"

                        val idType = confirmStorageType(cursor, 0, 7, where, errors)
                        val edgeType = confirmStorageType(cursor, 1, 8, where, errors)
                        val directionType = confirmStorageType(cursor, 2, 9, where, errors)
                        val valueType = confirmStorageType(cursor, 3, 10, where, errors)
                        val applicabilityType = confirmStorageType(cursor, 4, 11, where, errors)
                        val confidenceType = confirmStorageType(cursor, 5, 12, where, errors)
                        val provenanceType = confirmStorageType(cursor, 6, 13, where, errors)

                        val id = if (idType == "text") cursor.getString(0) else null
                        val rowEdgeId = if (edgeType == "text") cursor.getString(1) else null
                        val direction = if (directionType == "text") cursor.getString(2) else null
                        val valueKmh = if (valueType == "integer") cursor.getLong(3) else null
                        val applicability =
                            if (applicabilityType == "text") cursor.getString(4) else null
                        val confidence =
                            if (confidenceType == "real" || confidenceType == "integer") {
                                cursor.getDouble(5)
                            } else {
                                null
                            }
                        val provenance = if (provenanceType == "text") cursor.getString(6) else null

                        if (idType != "text" || id.isNullOrBlank()) {
                            errors.add("$where:BAD_ID_TYPE")
                        } else if (!seenRoadLimitIds.add(id)) {
                            errors.add("$where:DUPLICATE_ID:$id")
                        }

                        if (edgeType != "text" || rowEdgeId.isNullOrBlank()) {
                            errors.add("$where:BAD_EDGE_TYPE")
                        } else if (!edgeIds.contains(rowEdgeId)) {
                            errors.add("$where:UNKNOWN_EDGE:$rowEdgeId")
                        } else {
                            val edgeCount = (roadLimitCountsByEdge[rowEdgeId] ?: 0) + 1
                            roadLimitCountsByEdge[rowEdgeId] = edgeCount
                            if (edgeCount == MAX_ROAD_LIMITS_PER_EDGE + 1) {
                                errors.add(
                                    "TOO_MANY_ROAD_LIMITS_PER_EDGE:$rowEdgeId:$edgeCount"
                                )
                            }
                        }

                        if (directionType != "text" || direction !in ROAD_DIRECTIONS) {
                            errors.add("$where:BAD_DIRECTION:$direction")
                        }

                        val applicabilityOk =
                            applicabilityType == "text" && applicability in ROAD_APPLICABILITIES
                        if (!applicabilityOk) {
                            errors.add("$where:BAD_APPLICABILITY:$applicability")
                        }

                        if (applicabilityOk && applicability == "SUPPORTED") {
                            if (valueType != "integer" || valueKmh == null ||
                                valueKmh < ROAD_LIMIT_MIN_KMH.toLong() ||
                                valueKmh > ROAD_LIMIT_MAX_KMH.toLong()
                            ) {
                                errors.add("$where:BAD_ROAD_LIMIT_VALUE:$valueKmh")
                            }
                        } else if (valueType != "null") {
                            if (valueType != "integer") {
                                errors.add("$where:BAD_VALUE_TYPE")
                            } else {
                                errors.add(
                                    "$where:UNSUPPORTED_ROAD_LIMIT_MUST_NOT_CARRY_VALUE:$id"
                                )
                            }
                        }

                        val confidenceOk = confidenceType == "real" || confidenceType == "integer"
                        if (!confidenceOk || confidence == null ||
                            confidence < 0.0 || confidence > 1.0
                        ) {
                            errors.add("$where:BAD_CONFIDENCE")
                        }

                        if (provenanceType != "text" || provenance.isNullOrBlank()) {
                            errors.add("$where:MISSING_PROVENANCE")
                        }
                    }
                }
                if (inPackMeta.containsKey("roadLimitCount") &&
                    inPackMeta["roadLimitCount"] != actualRoadLimitCount.toString()
                ) {
                    errors.add(
                        "ROAD_LIMIT_COUNT_MISMATCH:meta=${inPackMeta["roadLimitCount"]} " +
                            "actual=$actualRoadLimitCount"
                    )
                }
                if (declaredRoadLimitCount != null &&
                    declaredRoadLimitCount != actualRoadLimitCount
                ) {
                    errors.add(
                        "MANIFEST_ROAD_LIMIT_COUNT_MISMATCH:$declaredRoadLimitCount"
                    )
                }
                val roadBudget =
                    if (inPackSynthetic) MAX_ROAD_LIMITS_SYNTHETIC else MAX_ROAD_LIMITS_REAL
                if (actualRoadLimitCount > roadBudget) {
                    errors.add("TOO_MANY_ROAD_LIMITS:$actualRoadLimitCount")
                }
            }

            // v1 requires at least one sign point; v2 also accepts a
            // road-evidence-only pack, but never an entirely empty pack.
            if (declaredSchemaVersion == SCHEMA_VERSION_ROAD_LIMITS) {
                if (actualPointCount + actualRoadLimitCount < MIN_POINTS) {
                    errors.add("TRUNCATED_NO_EVIDENCE")
                }
            } else if (actualPointCount < MIN_POINTS) {
                errors.add("TRUNCATED_NO_POINTS")
            }

            // 7. Near-duplicate scan (same type within 2 meters)
            for (i in 0 until validatedPoints.size) {
                if (errors.size >= 50) break
                val a = validatedPoints[i]
                for (j in i + 1 until validatedPoints.size) {
                    val b = validatedPoints[j]
                    if (a.type != b.type) continue
                    val dist = RoutingChain.distanceMeters(a.lat, a.lon, b.lat, b.lon)
                    if (dist < DUPLICATE_METERS) {
                        errors.add("DUPLICATE_LOCATION:${a.id}~${b.id}")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            errors.add("SQLITE_ERROR:${e.message}")
        } finally {
            try {
                db.close()
            } catch (_: Exception) {
            }
        }

        if (errors.isNotEmpty()) {
            return OfflinePackValidationResult.reject(errors)
        }

        val metadata = PackStatus.Ready(
            format = declaredFormat,
            formatVersion = declaredFormatVersion,
            schemaVersion = declaredSchemaVersion,
            source = declaredSource,
            license = declaredLicense,
            version = declaredVersion,
            isSynthetic = declaredSynthetic ?: true,
            edgeCount = declaredEdgeCount ?: 0,
            pointCount = declaredPointCount ?: 0,
            roadLimitCount = declaredRoadLimitCount ?: actualRoadLimitCount,
            sha256 = declaredSha,
            contentSha256 = declaredContentSha
        )
        return OfflinePackValidationResult.ok(metadata)
    }

    /**
     * Confirms the storage class of the value at [valueColumnIndex] using two
     * independent sources — the cursor adapter's [SignPackCursor.getStorageType]
     * (Android `Cursor.getType`, JDBC object identity) and the engine-evaluated
     * `typeof()` column at [typeofColumnIndex] — and returns the agreed SQLite
     * type name.
     *
     * A disagreement or an UNKNOWN classification records a deterministic
     * rejection and returns null: the value must never be coerced through a
     * numeric getter on the strength of one source alone. Every field type
     * check happens before the corresponding getInt/getDouble call.
     */
    private fun confirmStorageType(
        cursor: SignPackCursor,
        valueColumnIndex: Int,
        typeofColumnIndex: Int,
        where: String,
        errors: MutableList<String>
    ): String? {
        val adapterType = cursor.getStorageType(valueColumnIndex).sqliteName
        val engineType = cursor.getString(typeofColumnIndex) ?: "null"
        if (adapterType != engineType) {
            errors.add("$where:STORAGE_TYPE_MISMATCH:adapter=$adapterType engine=$engineType")
            return null
        }
        return engineType
    }

    /**
     * Reads a manifest field that must be a JSON integer and returns it at full
     * 64-bit width, or null when the field is absent or is not an integral JSON
     * number (float, string, boolean, array, object). org.json's optInt/optLong
     * would silently wrap or truncate such values, so callers must range-check
     * and compare this Long before converting it to Int.
     */
    private fun integralManifestNumber(obj: JSONObject, key: String): Long? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return when (val raw = obj.opt(key)) {
            is Int -> raw.toLong()
            is Long -> raw
            is Short -> raw.toLong()
            is Byte -> raw.toLong()
            else -> null
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(65536)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val hash = digest.digest()
        val sb = StringBuilder(hash.size * 2)
        for (b in hash) {
            sb.append(String.format(Locale.ROOT, "%02x", b))
        }
        return sb.toString()
    }
}
