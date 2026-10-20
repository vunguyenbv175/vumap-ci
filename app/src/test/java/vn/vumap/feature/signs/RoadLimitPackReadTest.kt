package vn.vumap.feature.signs

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import vn.vumap.core.model.GeoPoint
import vn.vumap.feature.signs.db.JdbcSignPackDbFactory
import vn.vumap.signs.RoadLimit
import vn.vumap.signs.SpeedResolver
import java.io.File
import java.sql.DriverManager

/**
 * Schema-v2 road evidence: validated pack -> typed [RoadLimit] reader ->
 * existing [SpeedResolver] contract.
 *
 * The reader is intentionally not wired to the UI, so these tests pass its
 * output explicitly to [OfflineSignRepository.resolveSpeedAdvisory]. Synthetic
 * packs still force suppressed demo advisories; nothing here enables a real
 * warning path or a nearest-edge fallback.
 */
class RoadLimitPackReadTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dbFactory = JdbcSignPackDbFactory()
    private val validator = OfflinePackValidator(dbFactory, syntheticOnly = true)
    private val location = GeoPoint(10.82310, 106.62970)

    private val edges = listOf(
        TestEdge(edgeId = "E_RL1", headingDeg = 90.0),
        TestEdge(edgeId = "E_RL2", headingDeg = 90.0),
        TestEdge(edgeId = "E_RL3", headingDeg = 90.0),
        TestEdge(edgeId = "E_RL4", headingDeg = 90.0)
    )

    private val points = listOf(
        TestPoint(
            pointId = "S_RL1",
            type = "P127",
            lat = 10.82310,
            lon = 106.62970,
            headingDeg = 90.0,
            valueKmh = 60,
            edgeId = "E_RL1"
        ),
        TestPoint(
            pointId = "S_RL2",
            type = "P127",
            lat = 10.82312,
            lon = 106.62972,
            headingDeg = 90.0,
            valueKmh = 60,
            edgeId = "E_RL2"
        )
    )

    private val roadLimits = listOf(
        TestRoadLimit(
            roadLimitId = "RL1_FWD",
            edgeId = "E_RL1",
            direction = "FORWARD",
            valueKmh = 60,
            applicability = "SUPPORTED"
        ),
        TestRoadLimit(
            roadLimitId = "RL2_BOTH",
            edgeId = "E_RL2",
            direction = "BOTH",
            valueKmh = 50,
            applicability = "SUPPORTED"
        ),
        TestRoadLimit(
            roadLimitId = "RL3_COND",
            edgeId = "E_RL3",
            direction = "FORWARD",
            applicability = "CONDITIONAL_UNSUPPORTED"
        ),
        TestRoadLimit(
            roadLimitId = "RL3_LANE",
            edgeId = "E_RL3",
            direction = "BOTH",
            applicability = "LANE_UNSUPPORTED"
        ),
        TestRoadLimit(
            roadLimitId = "RL4_BWD",
            edgeId = "E_RL4",
            direction = "BACKWARD",
            valueKmh = 80,
            applicability = "SUPPORTED"
        )
    )

    private fun buildV2Pack(
        edges: List<TestEdge> = this.edges,
        points: List<TestPoint> = this.points,
        roadLimits: List<TestRoadLimit> = this.roadLimits
    ): Pair<File, String> {
        val dir = tempFolder.newFolder()
        val pack = File(dir, "road_limits.vupack")
        val manifest = File(dir, "road_limits.vupack.manifest.json")
        TestPackBuilder.buildPack(
            packFile = pack,
            manifestFile = manifest,
            edges = edges,
            points = points,
            roadLimits = roadLimits,
            schemaVersion = 2
        )
        return pack to manifest.readText(Charsets.UTF_8)
    }

    private fun reSign(pack: File, manifestJson: String): String =
        JSONObject(manifestJson).apply {
            put("sha256", TestPackBuilder.computeSha256(pack))
            put("packBytes", pack.length())
        }.toString()

    private fun editPack(pack: File, sql: String) {
        DriverManager.getConnection("jdbc:sqlite:" + pack.absolutePath).use { conn ->
            conn.createStatement().use {
                // Test packs are re-validated after deliberately malformed
                // values; the like-for-like CHECKs would block the injection.
                it.execute("PRAGMA ignore_check_constraints = ON")
                it.execute(sql)
            }
        }
    }

    private suspend fun resolveWithPackEvidence(
        repo: OfflineSignRepository,
        edgeId: String,
        travelHeadingDeg: Double = 90.0
    ): vn.vumap.signs.SpeedAdvisory {
        val edge = repo.getEdge(edgeId)
        assertNotNull("edge $edgeId must exist", edge)
        return repo.resolveSpeedAdvisory(
            currentLocation = location,
            travelHeadingDeg = travelHeadingDeg,
            currentEdge = edge,
            radiusMeters = 50.0,
            roadLimits = repo.roadLimitsForEdge(edgeId)
        )
    }

    @Test
    fun v2PackValidatesAndReaderReturnsTypedEvidence() = runBlocking {
        val (pack, manifestJson) = buildV2Pack()
        val validation = validator.validate(pack, manifestJson)
        assertTrue("v2 pack must validate: ${validation.errors}", validation.isOk)
        assertEquals(2, validation.metadata!!.schemaVersion)
        assertEquals(5, validation.metadata.roadLimitCount)

        OfflineSignRepository(pack, manifestJson, dbFactory).use { repo ->
            val status = repo.packStatus() as PackStatus.Ready
            assertEquals(2, status.schemaVersion)
            assertEquals(5, status.roadLimitCount)

            val evidence = repo.roadLimitsForEdge("E_RL1")
            assertEquals(1, evidence.size)
            assertEquals("E_RL1", evidence[0].edgeId())
            assertEquals(RoadLimit.Direction.FORWARD, evidence[0].direction())
            assertEquals(60, evidence[0].valueKmh())
            assertTrue(evidence[0].isSupported())

            val unsupported = repo.roadLimitsForEdge("E_RL3")
            assertEquals(2, unsupported.size)
            assertTrue(unsupported.none { it.isSupported() })
            assertTrue(unsupported.all { it.valueKmh() == null })
            assertTrue(
                "other edges must never be substituted",
                repo.roadLimitsForEdge("E_NOT_IN_PACK").isEmpty()
            )
        }
    }

    @Test
    fun perEdgeRoadLimitBoundKeepsAllEvidenceForArbitration() = runBlocking {
        val limits = (0 until OfflinePackValidator.MAX_ROAD_LIMITS_PER_EDGE).map { index ->
            TestRoadLimit(
                roadLimitId = "BOUND_$index",
                edgeId = "E_RL1",
                direction = "BOTH",
                valueKmh = if (index == OfflinePackValidator.MAX_ROAD_LIMITS_PER_EDGE - 1) 50 else 60,
                applicability = "SUPPORTED"
            )
        }
        val (pack, manifestJson) = buildV2Pack(points = emptyList(), roadLimits = limits)
        val validation = validator.validate(pack, manifestJson)
        assertTrue("bounded edge evidence must validate: ${validation.errors}", validation.isOk)
        assertEquals(OfflinePackValidator.MAX_ROAD_LIMITS_PER_EDGE, validation.metadata!!.roadLimitCount)

        OfflineSignRepository(pack, manifestJson, dbFactory).use { repo ->
            val evidence = repo.roadLimitsForEdge("E_RL1")
            assertEquals(OfflinePackValidator.MAX_ROAD_LIMITS_PER_EDGE, evidence.size)
            assertTrue("the last conflict must not be truncated", evidence.any { it.valueKmh() == 50 })

            val advisory = resolveWithPackEvidence(repo, "E_RL1")
            assertNull(advisory.valueKmh())
            assertTrue(advisory.reason().contains("ROAD_SOURCE_AMBIGUOUS"))
        }
    }

    @Test
    fun tooManyRoadLimitsForOneEdgeIsRejected() {
        val limits = (0 until OfflinePackValidator.MAX_ROAD_LIMITS_PER_EDGE + 1).map { index ->
            TestRoadLimit(
                roadLimitId = "OVER_$index",
                edgeId = "E_RL1",
                direction = "BOTH",
                valueKmh = if (index == OfflinePackValidator.MAX_ROAD_LIMITS_PER_EDGE) 50 else 60,
                applicability = "SUPPORTED"
            )
        }
        val (pack, manifestJson) = buildV2Pack(points = emptyList(), roadLimits = limits)
        val validation = validator.validate(pack, manifestJson)
        assertFalse(validation.isOk)
        assertTrue(
            "unexpected errors: ${validation.errors}",
            validation.errors.any {
                it.startsWith("TOO_MANY_ROAD_LIMITS_PER_EDGE:E_RL1:")
            }
        )
    }

    @Test
    fun readerEvidenceFeedsResolverAgreement() = runBlocking {
        val (pack, manifestJson) = buildV2Pack()
        OfflineSignRepository(pack, manifestJson, dbFactory).use { repo ->
            val advisory = resolveWithPackEvidence(repo, "E_RL1")
            assertEquals(60, advisory.valueKmh())
            assertTrue(advisory.reason().contains("ROAD_SIGN_AGREE"))
            assertTrue("synthetic packs stay suppressed", advisory.suppressed())
            assertFalse(SpeedResolver.shouldWarnOverspeed(200.0, advisory))
        }
    }

    @Test
    fun readerEvidenceFeedsResolverConflictAbstention() = runBlocking {
        val (pack, manifestJson) = buildV2Pack()
        OfflineSignRepository(pack, manifestJson, dbFactory).use { repo ->
            val advisory = resolveWithPackEvidence(repo, "E_RL2")
            assertNull("conflicting road/sign values must not expose a number", advisory.valueKmh())
            assertTrue(advisory.reason().contains("ROAD_SIGN_CONFLICT_AMBIGUOUS"))
            assertFalse(SpeedResolver.shouldWarnOverspeed(200.0, advisory))
        }
    }

    @Test
    fun readerEvidenceFeedsResolverUnsupportedAbstention() = runBlocking {
        val (pack, manifestJson) = buildV2Pack()
        OfflineSignRepository(pack, manifestJson, dbFactory).use { repo ->
            val advisory = resolveWithPackEvidence(repo, "E_RL3")
            assertNull(advisory.valueKmh())
            assertTrue(
                "unsupported tags must abstain: ${advisory.reason()}",
                advisory.reason().contains("ROAD_LIMIT_APPLICABILITY_UNSUPPORTED")
            )
            assertFalse(SpeedResolver.shouldWarnOverspeed(200.0, advisory))
        }
    }

    @Test
    fun oppositeDirectionEvidenceIsIgnoredNotApplied() = runBlocking {
        val (pack, manifestJson) = buildV2Pack()
        OfflineSignRepository(pack, manifestJson, dbFactory).use { repo ->
            val travelEvidence = repo.roadLimitsForEdge("E_RL4")
            assertEquals(1, travelEvidence.size)
            assertEquals(RoadLimit.Direction.BACKWARD, travelEvidence[0].direction())
            val advisory = resolveWithPackEvidence(repo, "E_RL4")
            assertNull(
                "opposite-direction evidence must never become the limit",
                advisory.valueKmh()
            )
            assertFalse(advisory.reason().startsWith("ROAD_LIMIT_"))
        }
    }

    @Test
    fun v1PackStillValidatesAndReturnsNoRoadEvidence() = runBlocking {
        val dir = tempFolder.newFolder()
        val pack = File(dir, "v1.vupack")
        val manifest = File(dir, "v1.vupack.manifest.json")
        TestPackBuilder.buildPack(
            packFile = pack,
            manifestFile = manifest,
            edges = listOf(TestEdge(edgeId = "E01")),
            points = listOf(
                TestPoint(
                    pointId = "P01",
                    type = "R420",
                    lat = 10.823,
                    lon = 106.630,
                    edgeId = "E01"
                )
            )
        )
        val manifestJson = manifest.readText(Charsets.UTF_8)
        val validation = validator.validate(pack, manifestJson)
        assertTrue("v1 pack must stay accepted: ${validation.errors}", validation.isOk)
        assertEquals(1, validation.metadata!!.schemaVersion)
        assertEquals(0, validation.metadata.roadLimitCount)

        OfflineSignRepository(pack, manifestJson, dbFactory).use { repo ->
            assertTrue(repo.roadLimitsForEdge("E01").isEmpty())
        }
    }

    @Test
    fun roadOnlyV2PackWithoutSignsIsAccepted() = runBlocking {
        val (pack, manifestJson) = buildV2Pack(points = emptyList())
        val validation = validator.validate(pack, manifestJson)
        assertTrue(
            "road-evidence-only pack must validate: ${validation.errors}",
            validation.isOk
        )
        assertEquals(0, validation.metadata!!.pointCount)
        assertEquals(5, validation.metadata.roadLimitCount)
        OfflineSignRepository(pack, manifestJson, dbFactory).use { repo ->
            assertEquals(1, repo.roadLimitsForEdge("E_RL1").size)
        }
    }

    @Test
    fun emptyV2PackIsRejected() {
        val (pack, manifestJson) = buildV2Pack(points = emptyList(), roadLimits = emptyList())
        val validation = validator.validate(pack, manifestJson)
        assertFalse(validation.isOk)
        assertTrue(validation.errors.contains("TRUNCATED_NO_EVIDENCE"))
    }

    @Test
    fun missingRoadLimitsTableIsRejected() {
        val (pack, manifestJson) = buildV2Pack()
        editPack(pack, "DROP TABLE road_limits")
        val result = validator.validate(pack, reSign(pack, manifestJson))
        assertFalse(result.isOk)
        assertTrue(result.errors.contains("MISSING_TABLE:road_limits"))
    }

    @Test
    fun malformedRoadLimitRowsAreRejected() {
        val (pack, manifestJson) = buildV2Pack()
        editPack(pack, "UPDATE road_limits SET direction = 'SIDEWAYS' WHERE road_limit_id = 'RL1_FWD'")
        val result = validator.validate(pack, reSign(pack, manifestJson))
        assertFalse(result.isOk)
        assertTrue(
            "unexpected errors: ${result.errors}",
            result.errors.any { it.contains("BAD_DIRECTION") }
        )
    }

    @Test
    fun supportedRoadLimitWithoutValueIsRejected() {
        val (pack, manifestJson) = buildV2Pack()
        editPack(pack, "UPDATE road_limits SET value_kmh = NULL WHERE road_limit_id = 'RL1_FWD'")
        val result = validator.validate(pack, reSign(pack, manifestJson))
        assertFalse(result.isOk)
        assertTrue(result.errors.any { it.contains("BAD_ROAD_LIMIT_VALUE") })
    }

    @Test
    fun outOfRangeRoadLimitIsRejected() {
        val (pack, manifestJson) = buildV2Pack()
        editPack(pack, "UPDATE road_limits SET value_kmh = 200 WHERE road_limit_id = 'RL1_FWD'")
        val result = validator.validate(pack, reSign(pack, manifestJson))
        assertFalse(result.isOk)
        assertTrue(result.errors.any { it.contains("BAD_ROAD_LIMIT_VALUE") })
    }

    @Test
    fun unsupportedRoadLimitCarryingValueIsRejected() {
        val (pack, manifestJson) = buildV2Pack()
        editPack(pack, "UPDATE road_limits SET value_kmh = 60 WHERE road_limit_id = 'RL3_COND'")
        val result = validator.validate(pack, reSign(pack, manifestJson))
        assertFalse(result.isOk)
        assertTrue(
            result.errors.any {
                it.contains("UNSUPPORTED_ROAD_LIMIT_MUST_NOT_CARRY_VALUE")
            }
        )
    }

    @Test
    fun unknownEdgeAndBadConfidenceAreRejected() {
        val (pack, manifestJson) = buildV2Pack()
        editPack(pack, "UPDATE road_limits SET edge_id = 'NOPE' WHERE road_limit_id = 'RL1_FWD'")
        editPack(pack, "UPDATE road_limits SET confidence = 1.5 WHERE road_limit_id = 'RL2_BOTH'")
        val result = validator.validate(pack, reSign(pack, manifestJson))
        assertFalse(result.isOk)
        assertTrue(result.errors.any { it.contains("UNKNOWN_EDGE:NOPE") })
        assertTrue(result.errors.any { it.contains("BAD_CONFIDENCE") })
    }

    @Test
    fun nonIntegerSupportedValueIsRejected() {
        val (pack, manifestJson) = buildV2Pack()
        editPack(pack, "UPDATE road_limits SET value_kmh = '60 km/h' WHERE road_limit_id = 'RL1_FWD'")
        val result = validator.validate(pack, reSign(pack, manifestJson))
        assertFalse(result.isOk)
        assertTrue(result.errors.any { it.contains("BAD_ROAD_LIMIT_VALUE") })
    }

    @Test
    fun roadLimitCountMismatchesAreRejected() {
        val (pack, manifestJson) = buildV2Pack()
        val tampered = JSONObject(manifestJson).apply { put("roadLimitCount", 99) }.toString()
        val result = validator.validate(pack, reSign(pack, tampered))
        assertFalse(result.isOk)
        assertTrue(result.errors.any { it.contains("MANIFEST_ROAD_LIMIT_COUNT_MISMATCH") })
    }

    @Test
    fun schemaVersionMismatchBetweenManifestAndDatabaseIsRejected() {
        val (pack, manifestJson) = buildV2Pack()
        editPack(pack, "PRAGMA user_version = 1")
        val result = validator.validate(pack, reSign(pack, manifestJson))
        assertFalse(result.isOk)
        assertTrue(result.errors.any { it.contains("SCHEMA_DB_MISMATCH") })
    }

    @Test
    fun syntheticSafetyStillSuppressesRoadOnlyAdvisory() = runBlocking {
        val (pack, manifestJson) = buildV2Pack(points = emptyList())
        OfflineSignRepository(pack, manifestJson, dbFactory).use { repo ->
            val advisory = repo.resolveSpeedAdvisory(
                currentLocation = location,
                travelHeadingDeg = 90.0,
                currentEdge = repo.getEdge("E_RL1"),
                radiusMeters = 50.0,
                roadLimits = repo.roadLimitsForEdge("E_RL1")
            )
            assertEquals(60, advisory.valueKmh())
            assertTrue(advisory.suppressed())
            assertTrue(advisory.displayLabel().contains("DEMO") || advisory.displayLabel().contains("THỬ NGHIỆM"))
            assertFalse(SpeedResolver.shouldWarnOverspeed(200.0, advisory))
        }
    }
}
