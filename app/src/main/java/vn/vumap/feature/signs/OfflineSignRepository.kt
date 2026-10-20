package vn.vumap.feature.signs

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vn.vumap.core.model.BoundingBox
import vn.vumap.core.model.GeoPoint
import vn.vumap.core.model.PhysicalSign
import vn.vumap.core.model.RoadEdge
import vn.vumap.feature.nav.RoutingChain
import vn.vumap.feature.signs.db.SignPackDb
import vn.vumap.feature.signs.db.SignPackDbFactory
import vn.vumap.signs.CarriagewayType
import vn.vumap.signs.RoadLimit
import vn.vumap.signs.SignType
import vn.vumap.signs.SpeedAdvisory
import vn.vumap.signs.SpeedResolver
import vn.vumap.signs.ZoneEvidence
import vn.vumap.signs.ZoneState
import vn.vumap.signs.ZoneTracker
import java.io.Closeable
import java.io.File
import kotlin.math.abs

/**
 * Background read-only repository for offline sign and road edge queries.
 *
 * All operations execute off the main thread via [ioDispatcher].
 * Failure to validate or open the pack results in [PackStatus.Unavailable]
 * and safe fallback to empty/UNKNOWN with zero fabricated alerts.
 *
 * When consuming synthetic fixture packs ([PackStatus.Ready.isSynthetic] = true),
 * all generated [SpeedAdvisory] instances have `suppressed = true` and visible
 * demo/test-only labeling to prevent synthetic data from emitting legal overspeed
 * warnings into real driving.
 */
class OfflineSignRepository(
    private val packFile: File,
    private val manifestJson: String?,
    private val dbFactory: SignPackDbFactory,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val syntheticOnly: Boolean = true
) : SignProvider, Closeable {

    // No road-evidence producer is wired to the UI in this slice: the pack
    // reader below is the storage contract only.
    companion object {
        /**
         * Bound for one edge's road evidence rows. The pack validator rejects
         * packs above this bound, so the reader's LIMIT cannot truncate a
         * validated evidence list.
         */
        const val MAX_ROAD_LIMITS_PER_EDGE = OfflinePackValidator.MAX_ROAD_LIMITS_PER_EDGE
    }

    private val mutex = Mutex()
    private var db: SignPackDb? = null
    private var status: PackStatus = PackStatus.Unavailable("Not initialized")
    private var isInitialized = false

    /**
     * Initializes and validates the offline pack. Safe to call multiple times.
     */
    suspend fun initialize(): PackStatus = withContext(ioDispatcher) {
        mutex.withLock {
            if (isInitialized) return@withLock status

            val validation = try {
                val validator = OfflinePackValidator(dbFactory, syntheticOnly = syntheticOnly)
                validator.validate(packFile, manifestJson)
            } catch (e: Exception) {
                OfflinePackValidationResult.reject(listOf("VALIDATION_ERROR:${e.message}"))
            }

            if (!validation.isOk || validation.metadata == null) {
                status = PackStatus.Unavailable(
                    reason = "Pack validation failed with ${validation.errors.size} error(s)",
                    errors = validation.errors
                )
                isInitialized = true
                return@withLock status
            }

            try {
                val openedDb = dbFactory.openReadOnly(packFile)
                db = openedDb
                status = validation.metadata
            } catch (e: Exception) {
                status = PackStatus.Unavailable(
                    reason = "Database open failed: ${e.message}",
                    errors = listOf("SQLITE_OPEN_FAILED:${e.message}")
                )
            }
            isInitialized = true
            status
        }
    }

    override suspend fun packStatus(): PackStatus = withContext(ioDispatcher) {
        if (!isInitialized) initialize()
        status
    }

    override suspend fun nearby(area: BoundingBox, limit: Int): List<PhysicalSign> = withContext(ioDispatcher) {
        if (!isInitialized) initialize()
        val currentDb = db ?: return@withContext emptyList()
        val readyStatus = status as? PackStatus.Ready ?: return@withContext emptyList()

        val boundedLimit = limit.coerceIn(1, SignProvider.MAX_LIMIT)
        val sql = "SELECT point_id, type, lat, lon, heading_deg, value_kmh, edge_id, confidence, source " +
                "FROM points WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? ORDER BY point_id LIMIT ?"
        val args = arrayOf(
            area.minLat.toString(),
            area.maxLat.toString(),
            area.minLon.toString(),
            area.maxLon.toString(),
            boundedLimit.toString()
        )

        val results = mutableListOf<PhysicalSign>()
        try {
            currentDb.rawQuery(sql, args).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val typeStr = cursor.getString(1) ?: continue
                    val lat = cursor.getDouble(2) ?: continue
                    val lon = cursor.getDouble(3) ?: continue
                    val heading = cursor.getDouble(4)
                    val valueKmh = cursor.getInt(5)
                    val edgeId = cursor.getString(6) ?: continue
                    val confidence = cursor.getDouble(7)?.toFloat() ?: 0.0f
                    val source = cursor.getString(8) ?: "UNKNOWN"

                    val signType = try {
                        SignType.valueOf(typeStr)
                    } catch (_: IllegalArgumentException) {
                        continue
                    }

                    results.add(
                        PhysicalSign(
                            id = id,
                            type = signType,
                            location = GeoPoint(lat, lon),
                            headingDeg = heading,
                            valueKmh = valueKmh,
                            edgeId = edgeId,
                            confidence = confidence,
                            source = source,
                            isSynthetic = readyStatus.isSynthetic
                        )
                    )
                }
            }
        } catch (_: Exception) {
            return@withContext emptyList()
        }
        results
    }

    override suspend fun nearby(
        point: GeoPoint,
        radiusMeters: Double,
        limit: Int
    ): List<PhysicalSign> = withContext(ioDispatcher) {
        if (radiusMeters < 0.0) return@withContext emptyList()
        val area = BoundingBox.fromCenter(point, radiusMeters)
        val candidates = nearby(area, limit = SignProvider.MAX_LIMIT)
        val boundedLimit = limit.coerceIn(1, SignProvider.MAX_LIMIT)

        candidates
            .map { sign ->
                val dist = RoutingChain.distanceMeters(
                    point.latitude, point.longitude,
                    sign.location.latitude, sign.location.longitude
                )
                Pair(dist, sign)
            }
            .filter { (dist, _) -> dist <= radiusMeters }
            .sortedBy { (dist, _) -> dist }
            .take(boundedLimit)
            .map { (_, sign) -> sign }
    }

    override suspend fun getEdge(edgeId: String): RoadEdge? = withContext(ioDispatcher) {
        if (!isInitialized) initialize()
        val currentDb = db ?: return@withContext null
        if (edgeId.isBlank()) return@withContext null

        val sql = "SELECT edge_id, carriageway, level, road_class, lanes, heading_deg FROM edges WHERE edge_id = ? LIMIT 1"
        try {
            currentDb.rawQuery(sql, arrayOf(edgeId)).use { cursor ->
                if (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: return@withContext null
                    val cwStr = cursor.getString(1) ?: return@withContext null
                    val level = cursor.getInt(2) ?: 0
                    val roadClass = cursor.getString(3)
                    val lanes = cursor.getInt(4)
                    val heading = cursor.getDouble(5)

                    val cw = try {
                        CarriagewayType.valueOf(cwStr)
                    } catch (_: IllegalArgumentException) {
                        CarriagewayType.UNKNOWN
                    }

                    return@withContext RoadEdge(
                        edgeId = id,
                        carriageway = cw,
                        level = level,
                        roadClass = roadClass,
                        lanes = lanes,
                        headingDeg = heading
                    )
                }
            }
        } catch (_: Exception) {
            return@withContext null
        }
        null
    }

    override suspend fun nearbyEdges(area: BoundingBox, limit: Int): List<RoadEdge> = withContext(ioDispatcher) {
        val signs = nearby(area, limit = limit)
        val edgeIds = signs.map { it.edgeId }.distinct()
        val edges = mutableListOf<RoadEdge>()
        for (edgeId in edgeIds) {
            getEdge(edgeId)?.let { edges.add(it) }
        }
        edges.take(limit.coerceIn(1, SignProvider.MAX_LIMIT))
    }

    /**
     * Typed road evidence for one exact edge (schema-v2 packs only).
     *
     * Returns the road-limit rows stored for [edgeId] already typed to the
     * [RoadLimit] resolver contract. There is no nearest-edge or radius
     * substitution: callers must pass the verified current edge id, and rows
     * stay bounded/ordered so resolution is deterministic.
     *
     * Unsupported applicability (conditional/lane/vehicle tags) and unknown
     * directions are returned as-is; [SpeedResolver] abstains on them. This
     * reader is deliberately **not** connected to the UI yet: v1 packs and
     * packs without road data return an empty list, synthetic packs still force
     * suppressed demo advisories, and no weak nearest-edge warning path exists.
     */
    suspend fun roadLimitsForEdge(edgeId: String): List<RoadLimit> = withContext(ioDispatcher) {
        if (!isInitialized) initialize()
        if (edgeId.isBlank()) return@withContext emptyList()
        val readyStatus = status as? PackStatus.Ready ?: return@withContext emptyList()
        if (readyStatus.schemaVersion != OfflinePackValidator.SCHEMA_VERSION_ROAD_LIMITS) {
            return@withContext emptyList()
        }
        val currentDb = db ?: return@withContext emptyList()

        val sql = "SELECT direction, value_kmh, applicability, confidence, provenance " +
            "FROM road_limits WHERE edge_id = ? ORDER BY road_limit_id LIMIT ?"
        val args = arrayOf(edgeId, MAX_ROAD_LIMITS_PER_EDGE.toString())
        val results = mutableListOf<RoadLimit>()
        try {
            currentDb.rawQuery(sql, args).use { cursor ->
                while (cursor.moveToNext()) {
                    val directionText = cursor.getString(0) ?: continue
                    val valueKmh = cursor.getInt(1)
                    val applicabilityText = cursor.getString(2) ?: continue
                    val confidence = cursor.getDouble(3)?.toFloat() ?: continue
                    val provenance = cursor.getString(4) ?: ""

                    val direction = try {
                        RoadLimit.Direction.valueOf(directionText)
                    } catch (_: IllegalArgumentException) {
                        continue
                    }
                    val applicability = try {
                        RoadLimit.Applicability.valueOf(applicabilityText)
                    } catch (_: IllegalArgumentException) {
                        continue
                    }
                    val evidence = try {
                        RoadLimit(
                            edgeId,
                            RoadLimit.Source.OSM,
                            provenance,
                            direction,
                            valueKmh,
                            confidence,
                            applicability
                        )
                    } catch (_: IllegalArgumentException) {
                        // Defense in depth: the validator already rejects these.
                        continue
                    }
                    results.add(evidence)
                }
            }
        } catch (_: Exception) {
            return@withContext emptyList()
        }
        results
    }

    /**
     * Resolves a speed advisory for the given location, road edge and zone tracker.
     *
     * <p>Road/OSM evidence ([roadLimits]) is arbitrated against the explicit P.127
     * sign evidence found on the exact current edge: agreeing values keep one
     * numeric conclusion, conflicting values return AMBIGUOUS/UNKNOWN with no
     * numeric value and no overspeed warning, and conditional/lane/vehicle-specific
     * road tags abstain. Road evidence never feeds [ZoneTracker]: R.420/R.421 state
     * and confidence decay remain sign-zone-only.
     *
     * Guarantee: If the offline pack is unavailable, returns UNKNOWN.
     * If the pack is synthetic, the advisory is visibly marked demo/test only
     * and suppressed (`suppressed = true`), so [SpeedResolver.shouldWarnOverspeed]
     * will never return true for synthetic fixtures.
     */
    suspend fun resolveSpeedAdvisory(
        currentLocation: GeoPoint,
        travelHeadingDeg: Double?,
        currentEdge: RoadEdge?,
        zoneTracker: ZoneTracker? = null,
        radiusMeters: Double = 100.0,
        onExpressway: Boolean = false,
        currentTimeMs: Long = System.currentTimeMillis(),
        roadLimits: List<RoadLimit> = emptyList()
    ): SpeedAdvisory = withContext(ioDispatcher) {
        if (!isInitialized) initialize()
        val readyStatus = status as? PackStatus.Ready
        if (readyStatus == null) {
            return@withContext SpeedAdvisory.unknown(
                "Dữ liệu biển báo ngoại tuyến chưa sẵn sàng",
                0.0f,
                "PACK_UNAVAILABLE",
                "OFFLINE_PACK"
            )
        }

        // Require a verified edge match for road-level speed advisories
        if (currentEdge == null) {
            return@withContext SpeedAdvisory.unknown(
                "Đường chưa xác minh — giới hạn chưa xác định",
                0.0f,
                "UNVERIFIED_ROAD_MATCH",
                "ROAD"
            )
        }

        // Require verified travel heading/direction evidence
        if (travelHeadingDeg == null) {
            return@withContext SpeedAdvisory.unknown(
                "Hướng di chuyển chưa xác minh — giới hạn chưa xác định",
                0.0f,
                "MISSING_DIRECTION_EVIDENCE",
                "ROAD"
            )
        }

        // Check if travel direction contradicts the nominal road edge heading
        if (currentEdge.headingDeg != null && !isHeadingAligned(travelHeadingDeg, currentEdge.headingDeg)) {
            return@withContext SpeedAdvisory.unknown(
                "Hướng di chuyển ngược chiều đường — giới hạn chưa xác định",
                0.0f,
                "DIRECTION_MISMATCH",
                "ROAD"
            )
        }

        // Search for nearby signs
        val nearbySigns = nearby(currentLocation, radiusMeters, limit = 10)

        // Strict topological & directional match:
        // Sign must belong to currentEdge AND match the travel heading.
        // Prevents parallel deck/road sign leakage (e.g. S07/S16 on E_P_LEFT matching E_P_RIGHT)
        // and upper/lower deck level leakage (e.g. S08 on E_BR_UP matching E_BR_LOW).
        val matchedSigns = nearbySigns.filter { sign ->
            sign.edgeId == currentEdge.edgeId &&
                isHeadingAligned(travelHeadingDeg, sign.headingDeg)
        }

        // Look for direction-matched explicit P.127
        val explicitSign = matchedSigns.firstOrNull { it.type == SignType.P127 }
        val explicitLimitKmh = explicitSign?.valueKmh
        val explicitConfidence = explicitSign?.confidence ?: 0.0f

        // Feed zone sign evidence into zoneTracker ONLY for verified matched signs
        if (zoneTracker != null) {
            for (sign in matchedSigns) {
                if (sign.type == SignType.R420 || sign.type == SignType.R421) {
                    zoneTracker.observe(
                        ZoneEvidence(
                            sign.type,
                            sign.source,
                            currentTimeMs,
                            sign.edgeId,
                            sign.confidence,
                            sign.location.latitude,
                            sign.location.longitude
                        )
                    )
                }
            }
        }

        val zoneState = zoneTracker?.state() ?: ZoneState.UNKNOWN
        val zoneConfidence = zoneTracker?.confidence() ?: 0.0f
        val isSuppressed = zoneTracker?.isSuppressed ?: false

        val carriageway = currentEdge.carriageway

        // Road/OSM evidence is only meaningful for this exact edge (no nearest-road
        // substitution). Direction comes from the verified heading match above:
        // opposite travel already returned DIRECTION_MISMATCH, and a missing edge
        // heading leaves the travel direction unknown so directional road evidence
        // must abstain instead of guessing.
        val travelDirection = if (currentEdge.headingDeg == null) {
            RoadLimit.Direction.UNKNOWN
        } else {
            RoadLimit.Direction.FORWARD
        }
        val edgeRoadLimits = roadLimits.filter { it.edgeId() == currentEdge.edgeId }

        val rawAdvisory = SpeedResolver.resolve(
            zoneState,
            zoneConfidence,
            isSuppressed,
            carriageway,
            explicitLimitKmh,
            explicitConfidence,
            onExpressway,
            edgeRoadLimits,
            travelDirection
        )

        // Synthetic safety guard:
        // Synthetic fixture data must never emit real overspeed warnings.
        if (readyStatus.isSynthetic) {
            SpeedAdvisory(
                rawAdvisory.valueKmh(),
                "[THỬ NGHIỆM/DEMO ONLY] ${rawAdvisory.displayLabel()}",
                rawAdvisory.confidence(),
                /* suppressed = */ true, // Enforce suppression for all synthetic data
                "SYNTHETIC_DEMO_SUPPRESSED:${rawAdvisory.reason()}",
                "SYNTHETIC_PACK"
            )
        } else {
            rawAdvisory
        }
    }

    private fun isHeadingAligned(travelHeadingDeg: Double?, signHeadingDeg: Double?): Boolean {
        if (travelHeadingDeg == null) return false
        if (signHeadingDeg == null) return true
        val diff = abs(travelHeadingDeg - signHeadingDeg) % 360.0
        val angle = if (diff > 180.0) 360.0 - diff else diff
        return angle <= 45.0
    }

    override fun close() {
        try {
            db?.close()
        } catch (_: Exception) {
        } finally {
            db = null
        }
    }
}
