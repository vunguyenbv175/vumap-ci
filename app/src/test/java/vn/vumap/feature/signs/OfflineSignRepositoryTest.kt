package vn.vumap.feature.signs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import vn.vumap.core.model.BoundingBox
import vn.vumap.core.model.GeoPoint
import vn.vumap.feature.signs.db.JdbcSignPackDbFactory
import vn.vumap.signs.CarriagewayType
import vn.vumap.signs.RoadLimit
import vn.vumap.signs.SignType
import vn.vumap.signs.SpeedAdvisory
import vn.vumap.signs.SpeedResolver
import vn.vumap.signs.ZoneEvidence
import vn.vumap.signs.ZoneState
import vn.vumap.signs.ZoneTracker
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONObject

class OfflineSignRepositoryTest {

    private lateinit var projectRoot: File
    private lateinit var assetPack: File
    private lateinit var assetManifest: File
    private lateinit var manifestJson: String
    private val dbFactory = JdbcSignPackDbFactory()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        projectRoot = findProjectRoot()
        assetPack = File(projectRoot, "app/src/main/assets/signs/synthetic_vn_signs_p1.vupack")
        assetManifest = File(projectRoot, "app/src/main/assets/signs/synthetic_vn_signs_p1.vupack.manifest.json")
        manifestJson = assetManifest.readText(Charsets.UTF_8)
    }

    @Test
    fun initializesAndExposesReadyStatus() = runBlocking {
        val repo = OfflineSignRepository(assetPack, manifestJson, dbFactory)
        repo.use {
            val status = repo.packStatus()
            assertTrue("Expected Ready status: $status", status is PackStatus.Ready)
            val ready = status as PackStatus.Ready
            assertEquals("vupack", ready.format)
            assertEquals(1, ready.formatVersion)
            assertEquals(1, ready.schemaVersion)
            assertEquals("synthetic-vumap-p1", ready.source)
            assertTrue(ready.isSynthetic)
            assertEquals(18, ready.pointCount)
            assertEquals(13, ready.edgeCount)
        }
    }

    @Test
    fun queriesNearbySignsByBoundingBox() = runBlocking {
        val repo = OfflineSignRepository(assetPack, manifestJson, dbFactory)
        repo.use {
            // Bounding box around HCMC points (S01, S02, S03, S04)
            val hcmBbox = BoundingBox(
                minLat = 10.820,
                minLon = 106.620,
                maxLat = 10.835,
                maxLon = 106.645
            )
            val signs = repo.nearby(hcmBbox, limit = 50)
            assertTrue("Should find multiple HCMC signs, found ${signs.size}", signs.size >= 4)

            val s01 = signs.firstOrNull { it.id == "S01" }
            assertNotNull(s01)
            assertEquals(SignType.R420, s01!!.type)
            assertEquals("E_Q1", s01.edgeId)
            assertNull(s01.valueKmh)
            assertTrue(s01.isSynthetic)

            val s02 = signs.firstOrNull { it.id == "S02" }
            assertNotNull(s02)
            assertEquals(SignType.P127, s02!!.type)
            assertEquals(60, s02.valueKmh)
            assertEquals(90.0, s02.headingDeg!!, 0.01)
            assertEquals("E_Q1", s02.edgeId)
        }
    }

    @Test
    fun queriesNearbySignsByPointAndRadius() = runBlocking {
        val repo = OfflineSignRepository(assetPack, manifestJson, dbFactory)
        repo.use {
            // S01 is at (10.82310, 106.62970)
            val center = GeoPoint(10.82310, 106.62970)

            // 15 meters radius: only S01 should match
            val tight = repo.nearby(center, radiusMeters = 15.0)
            assertEquals(1, tight.size)
            assertEquals("S01", tight[0].id)

            // 100 meters radius: S01, S02 (~44m), S04 (~33m) should match
            val medium = repo.nearby(center, radiusMeters = 100.0)
            assertTrue(medium.size >= 3)
            assertEquals("S01", medium[0].id) // Closest first
            assertTrue(medium.any { it.id == "S02" })
            assertTrue(medium.any { it.id == "S04" })
        }
    }

    @Test
    fun lookupsRoadEdgeById() = runBlocking {
        val repo = OfflineSignRepository(assetPack, manifestJson, dbFactory)
        repo.use {
            val edge = repo.getEdge("E_Q1")
            assertNotNull(edge)
            assertEquals("E_Q1", edge!!.edgeId)
            assertEquals(CarriagewayType.DUAL_DIVIDED_OR_ONEWAY_2PLUS, edge.carriageway)
            assertEquals(0, edge.level)
            assertEquals("primary", edge.roadClass)
            assertEquals(2, edge.lanes)
            assertEquals(90.0, edge.headingDeg!!, 0.01)

            val unknownCarriageway = repo.getEdge("E_U1")
            assertNotNull(unknownCarriageway)
            assertEquals(CarriagewayType.UNKNOWN, unknownCarriageway!!.carriageway)

            val missing = repo.getEdge("NON_EXISTENT")
            assertNull(missing)
        }
    }

    @Test
    fun queriesNearbyEdges() = runBlocking {
        val repo = OfflineSignRepository(assetPack, manifestJson, dbFactory)
        repo.use {
            val hcmBbox = BoundingBox(10.820, 106.620, 10.835, 106.645)
            val edges = repo.nearbyEdges(hcmBbox)
            assertTrue(edges.isNotEmpty())
            assertTrue(edges.any { it.edgeId == "E_Q1" })
            assertTrue(edges.any { it.edgeId == "E_Q2" })
        }
    }

    @Test
    fun enforcesBoundedLimits() = runBlocking {
        val repo = OfflineSignRepository(assetPack, manifestJson, dbFactory)
        repo.use {
            val hcmBbox = BoundingBox(10.820, 106.620, 10.835, 106.645)
            val limit1 = repo.nearby(hcmBbox, limit = 1)
            assertEquals(1, limit1.size)

            val limit2 = repo.nearby(hcmBbox, limit = 2)
            assertEquals(2, limit2.size)

            val limit0 = repo.nearby(hcmBbox, limit = 0)
            assertEquals(1, limit0.size) // Coerced to at least 1
        }
    }

    @Test
    fun safeFallbackWhenPackIsCorruptedOrMissing() = runBlocking {
        val missingFile = File(projectRoot, "missing_signs.vupack")
        val repo = OfflineSignRepository(missingFile, manifestJson, dbFactory)
        repo.use {
            val status = repo.packStatus()
            assertTrue(status is PackStatus.Unavailable)

            val area = BoundingBox(10.0, 106.0, 11.0, 107.0)
            val signs = repo.nearby(area)
            assertTrue(signs.isEmpty())

            val edge = repo.getEdge("E_Q1")
            assertNull(edge)

            val advisory = repo.resolveSpeedAdvisory(
                currentLocation = GeoPoint(10.8231, 106.6297),
                travelHeadingDeg = 90.0,
                currentEdge = null
            )
            assertNotNull(advisory)
            assertNull("Corrupt pack must not emit numeric limit", advisory.valueKmh())
            assertTrue(advisory.suppressed())
            assertEquals("PACK_UNAVAILABLE", advisory.reason())
            assertFalse(
                "Must never warn on unavailable pack",
                SpeedResolver.shouldWarnOverspeed(120.0, advisory)
            )
        }
    }

    @Test
    fun syntheticWarningSafetyGuaranteesNoOverspeedAlerts() = runBlocking {
        val repo = OfflineSignRepository(assetPack, manifestJson, dbFactory)
        repo.use {
            val tracker = ZoneTracker()
            val edge = repo.getEdge("E_Q1")
            val location = GeoPoint(10.82310, 106.62970)

            // Resolve advisory near S01/S02 on E_Q1
            val advisory = repo.resolveSpeedAdvisory(
                currentLocation = location,
                travelHeadingDeg = 90.0,
                currentEdge = edge,
                zoneTracker = tracker,
                radiusMeters = 50.0
            )

            assertNotNull(advisory)
            // Visibly marked demo/test only:
            assertTrue(
                "Label must clearly indicate demo/test only: ${advisory.displayLabel()}",
                advisory.displayLabel().contains("THỬ NGHIỆM") || advisory.displayLabel().contains("DEMO")
            )
            // MUST be suppressed:
            assertTrue("Synthetic advisory must be suppressed", advisory.suppressed())
            assertTrue(advisory.reason().startsWith("SYNTHETIC_DEMO_SUPPRESSED"))

            // Absolute requirement: never silently feed synthetic legal numeric speed warnings into real driving!
            // Even driving at 200 km/h, shouldWarnOverspeed must return false!
            assertFalse(
                "Overspeed warning must NEVER fire for synthetic fixture data",
                SpeedResolver.shouldWarnOverspeed(200.0, advisory)
            )
        }
    }

    @Test
    fun handlesConcurrentQueriesAcrossCoroutines() = runBlocking {
        val repo = OfflineSignRepository(assetPack, manifestJson, dbFactory)
        repo.use {
            val area = BoundingBox(10.820, 106.620, 10.835, 106.645)
            val deferreds = (1..20).map {
                async(Dispatchers.Default) {
                    val signs = repo.nearby(area)
                    val edge = repo.getEdge("E_Q1")
                    Pair(signs.size, edge?.edgeId)
                }
            }
            val results = deferreds.awaitAll()
            assertEquals(20, results.size)
            for (res in results) {
                assertTrue(res.first >= 4)
                assertEquals("E_Q1", res.second)
            }
        }
    }

    @Test
    fun noMainThreadIoGuaranteedByDispatcher() = runBlocking {
        val ioThreadNames = Collections.synchronizedSet(mutableSetOf<String>())
        val customIoDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "dedicated-bg-io-worker")
        }.asCoroutineDispatcher()

        try {
            val trackingDbFactory = vn.vumap.feature.signs.db.SignPackDbFactory { file ->
                ioThreadNames.add(Thread.currentThread().name)
                dbFactory.openReadOnly(file)
            }

            val repo = OfflineSignRepository(
                packFile = assetPack,
                manifestJson = manifestJson,
                dbFactory = trackingDbFactory,
                ioDispatcher = customIoDispatcher
            )

            repo.use {
                val callerThreadName = Thread.currentThread().name
                val area = BoundingBox(10.820, 106.620, 10.835, 106.645)
                val signs = repo.nearby(area)
                val edge = repo.getEdge("E_Q1")

                assertTrue(signs.isNotEmpty())
                assertNotNull(edge)
                assertTrue(
                    "DB operations must run on background IO thread: $ioThreadNames",
                    ioThreadNames.any { it.startsWith("dedicated-bg-io-worker") }
                )
                assertFalse(
                    "DB operations must NOT run on calling thread: $callerThreadName",
                    ioThreadNames.contains(callerThreadName)
                )
            }
        } finally {
            customIoDispatcher.close()
        }
    }

    @Test
    fun parallelRoadDoesNotMatchSignFromNeighboringDeck() = runBlocking {
        val repo = OfflineSignRepository(assetPack, manifestJson, dbFactory)
        repo.use {
            // S07 (P127 60) and S16 (R420) are placed exclusively on E_P_LEFT (parallel deck pair)
            val locationNearParallel = GeoPoint(10.82500, 106.63000)

            // When traveling on E_P_RIGHT, signs on E_P_LEFT must NOT match or mutate ZoneTracker
            val edgeRight = repo.getEdge("E_P_RIGHT")
            assertNotNull(edgeRight)
            val trackerRight = ZoneTracker()

            val advisoryRight = repo.resolveSpeedAdvisory(
                currentLocation = locationNearParallel,
                travelHeadingDeg = 90.0,
                currentEdge = edgeRight,
                zoneTracker = trackerRight
            )

            // S16 (R420 on E_P_LEFT) must NOT leak into trackerRight
            assertEquals(ZoneState.UNKNOWN, trackerRight.state())
            assertTrue(trackerRight.history().isEmpty())
            // S07 (P127 60 on E_P_LEFT) must NOT apply to E_P_RIGHT
            assertNull(advisoryRight.valueKmh())
            assertTrue(advisoryRight.reason().contains("NO_ZONE_EVIDENCE"))

            // In contrast, when traveling on E_P_LEFT, both S16 and S07 must match
            val edgeLeft = repo.getEdge("E_P_LEFT")
            assertNotNull(edgeLeft)
            val trackerLeft = ZoneTracker()

            val advisoryLeft = repo.resolveSpeedAdvisory(
                currentLocation = locationNearParallel,
                travelHeadingDeg = 90.0,
                currentEdge = edgeLeft,
                zoneTracker = trackerLeft
            )

            assertEquals(ZoneState.INSIDE_DENSE, trackerLeft.state())
            assertEquals(Integer.valueOf(60), advisoryLeft.valueKmh())
            assertTrue(advisoryLeft.reason().contains("EXPLICIT_P127"))
        }
    }

    @Test
    fun bridgeDeckLevelSeparation() = runBlocking {
        val repo = OfflineSignRepository(assetPack, manifestJson, dbFactory)
        repo.use {
            // S08 (R420) is on upper deck E_BR_UP (level 1)
            // S09 (P127 50) is on ground road E_BR_LOW (level 0)
            val bridgeLocation = GeoPoint(10.82600, 106.63100)

            // Ground road (level 0): S09 matches (50 km/h); S08 on upper deck must NOT leak down
            val edgeLow = repo.getEdge("E_BR_LOW")
            assertNotNull(edgeLow)
            val trackerLow = ZoneTracker()

            val advisoryLow = repo.resolveSpeedAdvisory(
                currentLocation = bridgeLocation,
                travelHeadingDeg = 90.0,
                currentEdge = edgeLow,
                zoneTracker = trackerLow
            )

            assertEquals(Integer.valueOf(50), advisoryLow.valueKmh())
            assertTrue(advisoryLow.reason().contains("EXPLICIT_P127"))
            assertEquals(ZoneState.UNKNOWN, trackerLow.state())
            assertTrue(trackerLow.history().isEmpty())

            // Upper deck (level 1): S08 matches (zone INSIDE_DENSE); S09 (50 km/h) on lower deck must NOT leak up
            val edgeUp = repo.getEdge("E_BR_UP")
            assertNotNull(edgeUp)
            val trackerUp = ZoneTracker()

            val advisoryUp = repo.resolveSpeedAdvisory(
                currentLocation = bridgeLocation,
                travelHeadingDeg = 90.0,
                currentEdge = edgeUp,
                zoneTracker = trackerUp
            )

            assertEquals(ZoneState.INSIDE_DENSE, trackerUp.state())
            // Zone default on dual divided road is 60 km/h, NOT the lower deck's explicit 50 km/h
            assertEquals(Integer.valueOf(60), advisoryUp.valueKmh())
            assertTrue(advisoryUp.reason().contains("INSIDE_DUAL_60"))
        }
    }

    @Test
    fun unverifiedRoadOrDirectionReturnsUnknownWithoutMutatingZoneTracker() = runBlocking {
        val repo = OfflineSignRepository(assetPack, manifestJson, dbFactory)
        repo.use {
            val location = GeoPoint(10.82310, 106.62970) // near S01/S02 on E_Q1
            val edgeQ1 = repo.getEdge("E_Q1")
            assertNotNull(edgeQ1)

            // Case 1: unverified road match (currentEdge == null)
            val trackerNullEdge = ZoneTracker()
            val advisoryNullEdge = repo.resolveSpeedAdvisory(
                currentLocation = location,
                travelHeadingDeg = 90.0,
                currentEdge = null,
                zoneTracker = trackerNullEdge
            )
            assertNull(advisoryNullEdge.valueKmh())
            assertTrue(advisoryNullEdge.suppressed())
            assertEquals("UNVERIFIED_ROAD_MATCH", advisoryNullEdge.reason())
            assertEquals(ZoneState.UNKNOWN, trackerNullEdge.state())
            assertTrue(trackerNullEdge.history().isEmpty())

            // Case 2: missing direction evidence (travelHeadingDeg == null)
            val trackerNullHeading = ZoneTracker()
            val advisoryNullHeading = repo.resolveSpeedAdvisory(
                currentLocation = location,
                travelHeadingDeg = null,
                currentEdge = edgeQ1,
                zoneTracker = trackerNullHeading
            )
            assertNull(advisoryNullHeading.valueKmh())
            assertTrue(advisoryNullHeading.suppressed())
            assertEquals("MISSING_DIRECTION_EVIDENCE", advisoryNullHeading.reason())
            assertEquals(ZoneState.UNKNOWN, trackerNullHeading.state())
            assertTrue(trackerNullHeading.history().isEmpty())

            // Case 3: traveling opposite direction of one-way road (270 vs E_Q1 heading 90)
            val trackerOpposite = ZoneTracker()
            val advisoryOpposite = repo.resolveSpeedAdvisory(
                currentLocation = location,
                travelHeadingDeg = 270.0,
                currentEdge = edgeQ1,
                zoneTracker = trackerOpposite
            )
            assertNull(advisoryOpposite.valueKmh())
            assertTrue(advisoryOpposite.suppressed())
            assertEquals("DIRECTION_MISMATCH", advisoryOpposite.reason())
            assertEquals(ZoneState.UNKNOWN, trackerOpposite.state())
            assertTrue(trackerOpposite.history().isEmpty())
        }
    }

    @Test
    fun initializationDoesNotThrowForDirectoryOrUnreadablePack() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "vumap_dir_pack_" + System.nanoTime()).apply { mkdirs() }
        try {
            val repo = OfflineSignRepository(tempDir, manifestJson, dbFactory)
            repo.use {
                val status = repo.initialize()
                assertTrue(
                    "Directory pack path must yield Unavailable status without throwing: $status",
                    status is PackStatus.Unavailable
                )
                val unavailable = status as PackStatus.Unavailable
                assertTrue(unavailable.errors.contains("PACK_NOT_A_FILE"))

                val area = BoundingBox(10.0, 106.0, 11.0, 107.0)
                assertTrue(repo.nearby(area).isEmpty())
                assertNull(repo.getEdge("E_Q1"))

                val advisory = repo.resolveSpeedAdvisory(
                    currentLocation = GeoPoint(10.8231, 106.6297),
                    travelHeadingDeg = 90.0,
                    currentEdge = null
                )
                assertNotNull(advisory)
                assertNull(advisory.valueKmh())
                assertTrue(advisory.suppressed())
            }
        } finally {
            tempDir.delete()
        }
    }

    @Test
    fun malformedStorageTypePackYieldsUnavailableAndUnknownFallback() = runBlocking {
        // Regression: a re-signed pack whose P.127 value_kmh holds REAL 60.5
        // (or a text heading) must be rejected, never coerced into a usable
        // sign. This is the repository-level counterpart to the validator
        // storage-type checks: no Ready status, no fabricated numeric limit.
        val mutatedPack = File.createTempFile("malformed_storage", ".vupack")
        try {
            assetPack.copyTo(mutatedPack, overwrite = true)
            DriverManager.getConnection("jdbc:sqlite:" + mutatedPack.absolutePath).use { conn ->
                conn.createStatement().execute("UPDATE points SET value_kmh = 60.5 WHERE point_id = 'S02'")
                conn.createStatement().execute("UPDATE points SET heading_deg = 'east' WHERE point_id = 'S01'")
            }
            val reSignedManifest = JSONObject(manifestJson).apply {
                put("sha256", computeSha256(mutatedPack))
                put("packBytes", mutatedPack.length())
            }.toString()

            val repo = OfflineSignRepository(mutatedPack, reSignedManifest, dbFactory)
            repo.use {
                val status = repo.initialize()
                assertTrue(
                    "Malformed storage types must yield Unavailable: $status",
                    status is PackStatus.Unavailable
                )
                val unavailable = status as PackStatus.Unavailable
                assertTrue(
                    "Expected BAD_P127_VALUE rejection: ${unavailable.errors}",
                    unavailable.errors.any { it.contains("BAD_P127_VALUE") }
                )

                // Empty query fallback: no sign evidence is exposed
                val area = BoundingBox(10.820, 106.620, 10.835, 106.645)
                assertTrue(repo.nearby(area).isEmpty())
                assertNull(repo.getEdge("E_Q1"))

                // UNKNOWN advisory fallback: no fabricated numeric limit
                val advisory = repo.resolveSpeedAdvisory(
                    currentLocation = GeoPoint(10.8231, 106.6297),
                    travelHeadingDeg = 90.0,
                    currentEdge = null
                )
                assertNotNull(advisory)
                assertNull("Malformed pack must not emit numeric limit", advisory.valueKmh())
                assertTrue(advisory.suppressed())
                assertEquals("PACK_UNAVAILABLE", advisory.reason())
                assertFalse(SpeedResolver.shouldWarnOverspeed(200.0, advisory))
            }
        } finally {
            mutatedPack.delete()
        }
    }

    @Test
    fun oversizedIntegerPackYieldsUnavailableAndUnknownFallback() = runBlocking {
        // Regression: SQLite INTEGER is 64-bit; a re-signed pack with
        // S02.value_kmh = 2^32 + 60 passes an integer storage-class check but
        // would truncate to 60 on a 32-bit read. It must yield Unavailable and
        // never expose an invented numeric limit.
        val mutatedPack = File.createTempFile("oversized_integer", ".vupack")
        try {
            assetPack.copyTo(mutatedPack, overwrite = true)
            DriverManager.getConnection("jdbc:sqlite:" + mutatedPack.absolutePath).use { conn ->
                conn.createStatement().execute("UPDATE points SET value_kmh = 4294967356 WHERE point_id = 'S02'")
            }
            val reSignedManifest = JSONObject(manifestJson).apply {
                put("sha256", computeSha256(mutatedPack))
                put("packBytes", mutatedPack.length())
            }.toString()

            val repo = OfflineSignRepository(mutatedPack, reSignedManifest, dbFactory)
            repo.use {
                val status = repo.initialize()
                assertTrue(
                    "Oversized 64-bit integer must yield Unavailable: $status",
                    status is PackStatus.Unavailable
                )
                val unavailable = status as PackStatus.Unavailable
                assertTrue(
                    "Expected BAD_P127_VALUE rejection: ${unavailable.errors}",
                    unavailable.errors.any { it.contains("BAD_P127_VALUE:4294967356") }
                )

                val area = BoundingBox(10.820, 106.620, 10.835, 106.645)
                assertTrue(repo.nearby(area).isEmpty())
                assertNull(repo.getEdge("E_Q1"))

                val advisory = repo.resolveSpeedAdvisory(
                    currentLocation = GeoPoint(10.8231, 106.6297),
                    travelHeadingDeg = 90.0,
                    currentEdge = null
                )
                assertNotNull(advisory)
                assertNull("Oversized pack must not emit numeric limit", advisory.valueKmh())
                assertTrue(advisory.suppressed())
                assertEquals("PACK_UNAVAILABLE", advisory.reason())
                assertFalse(SpeedResolver.shouldWarnOverspeed(200.0, advisory))
            }
        } finally {
            mutatedPack.delete()
        }
    }

    @Test
    fun oversizedManifestVersionYieldsUnavailableAndUnknownFallback() = runBlocking {
        // Regression: manifest metadata is JSON, not SQLite, but it narrows the
        // same way via optInt. Declaring formatVersion = 2^32 + 1 used to wrap
        // to 1 and pass the gate; it must yield Unavailable with empty queries
        // and an UNKNOWN advisory rather than a Ready pack.
        val mutatedManifest = JSONObject(manifestJson).apply {
            put("formatVersion", 4294967297L)
        }.toString()

        val repo = OfflineSignRepository(assetPack, mutatedManifest, dbFactory)
        repo.use {
            val status = repo.initialize()
            assertTrue(
                "Oversized manifest version must yield Unavailable: $status",
                status is PackStatus.Unavailable
            )
            val unavailable = status as PackStatus.Unavailable
            assertTrue(
                "Expected UNSUPPORTED_FORMAT_VERSION rejection: ${unavailable.errors}",
                unavailable.errors.any { it.contains("UNSUPPORTED_FORMAT_VERSION:4294967297") }
            )

            val area = BoundingBox(10.820, 106.620, 10.835, 106.645)
            assertTrue(repo.nearby(area).isEmpty())
            assertNull(repo.getEdge("E_Q1"))

            val advisory = repo.resolveSpeedAdvisory(
                currentLocation = GeoPoint(10.8231, 106.6297),
                travelHeadingDeg = 90.0,
                currentEdge = null
            )
            assertNotNull(advisory)
            assertNull("Rejected manifest must not emit numeric limit", advisory.valueKmh())
            assertTrue(advisory.suppressed())
            assertEquals("PACK_UNAVAILABLE", advisory.reason())
            assertFalse(SpeedResolver.shouldWarnOverspeed(200.0, advisory))
        }
    }

    // ---- Source-conflict arbitration (road/OSM evidence vs explicit sign) ----

    private suspend fun resolveWithRoadLimits(
        repo: OfflineSignRepository,
        edgeId: String,
        roadLimits: List<RoadLimit>,
        location: GeoPoint = GeoPoint(10.82310, 106.62970),
        tracker: ZoneTracker? = null,
        onExpressway: Boolean = false
    ): SpeedAdvisory {
        val edge = repo.getEdge(edgeId)
        assertNotNull("edge $edgeId must exist", edge)
        return repo.resolveSpeedAdvisory(
            currentLocation = location,
            travelHeadingDeg = 90.0,
            currentEdge = edge,
            zoneTracker = tracker,
            radiusMeters = 50.0,
            onExpressway = onExpressway,
            roadLimits = roadLimits
        )
    }

    private fun buildSyntheticPack(
        edges: List<TestEdge>,
        points: List<TestPoint>
    ): Pair<File, String> {
        val dir = tempFolder.newFolder()
        val pack = File(dir, "arbitration.vupack")
        val manifest = File(dir, "arbitration.vupack.manifest.json")
        TestPackBuilder.buildPack(
            packFile = pack,
            manifestFile = manifest,
            edges = edges,
            points = points
        )
        return pack to manifest.readText(Charsets.UTF_8)
    }

    @Test
    fun roadLimitConflictingWithSignOnExactEdgeIsAmbiguousAndNeverWarns() = runBlocking {
        val repo = OfflineSignRepository(assetPack, manifestJson, dbFactory)
        repo.use {
            val advisory = resolveWithRoadLimits(
                repo,
                edgeId = "E_Q1",
                roadLimits = listOf(
                    RoadLimit.supported(
                        "E_Q1",
                        RoadLimit.Source.OSM,
                        "synthetic-osm-v1",
                        RoadLimit.Direction.FORWARD,
                        50,
                        0.8f
                    )
                )
            )
            assertNull("conflicting sources must not expose a numeric limit", advisory.valueKmh())
            assertTrue("conflicting sources must be suppressed", advisory.suppressed())
            assertTrue(
                "arbitration reason must survive the synthetic guard: ${advisory.reason()}",
                advisory.reason().contains("ROAD_SIGN_CONFLICT_AMBIGUOUS")
            )
            assertFalse(
                "conflicting sources must never drive overspeed warnings",
                SpeedResolver.shouldWarnOverspeed(200.0, advisory)
            )
            assertTrue(
                "synthetic fixture must stay visibly demo/test only",
                advisory.displayLabel().contains("THỬ NGHIỆM") || advisory.displayLabel().contains("DEMO")
            )
        }
    }

    @Test
    fun roadLimitAgreeingWithSignKeepsNumericEvidenceFromBothSources() = runBlocking {
        val repo = OfflineSignRepository(assetPack, manifestJson, dbFactory)
        repo.use {
            val advisory = resolveWithRoadLimits(
                repo,
                edgeId = "E_Q1",
                roadLimits = listOf(
                    RoadLimit.supported(
                        "E_Q1",
                        RoadLimit.Source.OSM,
                        "synthetic-osm-v1",
                        RoadLimit.Direction.FORWARD,
                        60,
                        0.8f
                    )
                )
            )
            assertEquals("agreeing P.127 60 and road 60 keep the numeric limit", 60, advisory.valueKmh())
            assertTrue(
                "agree reason must be preserved: ${advisory.reason()}",
                advisory.reason().contains("ROAD_SIGN_AGREE")
            )
            assertTrue("synthetic pack still suppresses the advisory", advisory.suppressed())
            assertFalse(SpeedResolver.shouldWarnOverspeed(200.0, advisory))
        }
    }

    @Test
    fun roadOnlyExactEdgeEvidenceGivesNumericWithRoadSource() = runBlocking {
        val (pack, manifest) = buildSyntheticPack(
            edges = listOf(TestEdge(edgeId = "E_R1", headingDeg = 90.0)),
            points = listOf(
                TestPoint(
                    pointId = "S_R420_1",
                    type = "R420",
                    lat = 10.823,
                    lon = 106.630,
                    edgeId = "E_R1"
                )
            )
        )
        val repo = OfflineSignRepository(pack, manifest, dbFactory)
        repo.use {
            val advisory = resolveWithRoadLimits(
                repo,
                edgeId = "E_R1",
                roadLimits = listOf(
                    RoadLimit.supported(
                        "E_R1",
                        RoadLimit.Source.OSM,
                        "synthetic-osm-v1",
                        RoadLimit.Direction.FORWARD,
                        50,
                        0.8f
                    )
                ),
                location = GeoPoint(10.823, 106.630)
            )
            assertEquals("exact-edge road evidence is a positive control", 50, advisory.valueKmh())
            assertTrue(
                "road-only reason must name the road source: ${advisory.reason()}",
                advisory.reason().contains("ROAD_LIMIT_OSM")
            )
            assertTrue("synthetic pack stays demo-suppressed", advisory.suppressed())
            assertFalse(SpeedResolver.shouldWarnOverspeed(200.0, advisory))
        }
    }

    @Test
    fun unsupportedConditionalLaneVehicleRoadTagsAbstain() = runBlocking {
        val repo = OfflineSignRepository(assetPack, manifestJson, dbFactory)
        repo.use {
            for (applicability in listOf(
                RoadLimit.Applicability.CONDITIONAL_UNSUPPORTED,
                RoadLimit.Applicability.LANE_UNSUPPORTED,
                RoadLimit.Applicability.VEHICLE_UNSUPPORTED
            )) {
                val advisory = resolveWithRoadLimits(
                    repo,
                    edgeId = "E_Q1",
                    roadLimits = listOf(
                        RoadLimit.unsupported(
                            "E_Q1",
                            RoadLimit.Source.OSM,
                            "synthetic-osm-v1",
                            RoadLimit.Direction.FORWARD,
                            applicability
                        )
                    )
                )
                assertNull("$applicability must not expose a numeric limit", advisory.valueKmh())
                assertTrue("$applicability must be suppressed", advisory.suppressed())
                assertTrue(
                    "$applicability reason: ${advisory.reason()}",
                    advisory.reason().contains("ROAD_LIMIT_APPLICABILITY_UNSUPPORTED")
                )
                assertFalse(
                    "$applicability must never drive overspeed warnings",
                    SpeedResolver.shouldWarnOverspeed(200.0, advisory)
                )
            }
        }
    }

    @Test
    fun roadLimitWithUnknownDirectionAbstainsInsteadOfGuessing() = runBlocking {
        val repo = OfflineSignRepository(assetPack, manifestJson, dbFactory)
        repo.use {
            val advisory = resolveWithRoadLimits(
                repo,
                edgeId = "E_Q1",
                roadLimits = listOf(
                    RoadLimit.supported(
                        "E_Q1",
                        RoadLimit.Source.OSM,
                        "synthetic-osm-v1",
                        RoadLimit.Direction.UNKNOWN,
                        50,
                        0.9f
                    )
                )
            )
            assertNull("unknown road direction must not expose a numeric limit", advisory.valueKmh())
            assertTrue(advisory.suppressed())
            assertTrue(
                "direction applicability must abstain explicitly: ${advisory.reason()}",
                advisory.reason().contains("ROAD_LIMIT_DIRECTION_AMBIGUOUS")
            )
            assertFalse(SpeedResolver.shouldWarnOverspeed(200.0, advisory))
        }
    }

    @Test
    fun roadLimitForDifferentEdgeDoesNotAffectCurrentEdge() = runBlocking {
        val repo = OfflineSignRepository(assetPack, manifestJson, dbFactory)
        repo.use {
            val advisory = resolveWithRoadLimits(
                repo,
                edgeId = "E_Q1",
                roadLimits = listOf(
                    RoadLimit.supported(
                        "E_OTHER",
                        RoadLimit.Source.OSM,
                        "synthetic-osm-v1",
                        RoadLimit.Direction.FORWARD,
                        50,
                        0.9f
                    )
                )
            )
            assertEquals("exact-edge sign evidence must still decide", 60, advisory.valueKmh())
            assertTrue(
                "sign evidence must be untouched by wrong-edge road data: ${advisory.reason()}",
                advisory.reason().contains("EXPLICIT_P127")
            )
            assertFalse(
                "wrong-edge road data must not fabricate a conflict",
                advisory.reason().contains("AMBIGUOUS")
            )
        }
    }

    @Test
    fun oppositeDirectionRoadLimitIsNeverApplied() = runBlocking {
        val (pack, manifest) = buildSyntheticPack(
            edges = listOf(TestEdge(edgeId = "E_R2", headingDeg = 90.0)),
            points = listOf(
                TestPoint(
                    pointId = "S_R420_2",
                    type = "R420",
                    lat = 10.823,
                    lon = 106.630,
                    edgeId = "E_R2"
                )
            )
        )
        val repo = OfflineSignRepository(pack, manifest, dbFactory)
        repo.use {
            val tracker = ZoneTracker()
            val advisory = resolveWithRoadLimits(
                repo,
                edgeId = "E_R2",
                roadLimits = listOf(
                    RoadLimit.supported(
                        "E_R2",
                        RoadLimit.Source.OSM,
                        "synthetic-osm-v1",
                        RoadLimit.Direction.BACKWARD,
                        50,
                        0.9f
                    )
                ),
                location = GeoPoint(10.823, 106.630),
                tracker = tracker
            )
            assertEquals("opposite-direction evidence must be ignored", 60, advisory.valueKmh())
            assertTrue(
                "R.420 zone default must stand: ${advisory.reason()}",
                advisory.reason().contains("INSIDE_DUAL_60")
            )
            assertEquals("R.420 zone must be preserved", ZoneState.INSIDE_DENSE, tracker.state())
        }
    }

    @Test
    fun missingEdgeWithRoadEvidenceStaysUnknown() = runBlocking {
        val repo = OfflineSignRepository(assetPack, manifestJson, dbFactory)
        repo.use {
            val advisory = repo.resolveSpeedAdvisory(
                currentLocation = GeoPoint(10.82310, 106.62970),
                travelHeadingDeg = 90.0,
                currentEdge = null,
                roadLimits = listOf(
                    RoadLimit.supported(
                        "E_Q1",
                        RoadLimit.Source.OSM,
                        "synthetic-osm-v1",
                        RoadLimit.Direction.FORWARD,
                        50,
                        0.9f
                    )
                )
            )
            assertNull("missing edge must not apply road evidence", advisory.valueKmh())
            assertTrue(advisory.suppressed())
            assertEquals("UNVERIFIED_ROAD_MATCH", advisory.reason())
            assertFalse(SpeedResolver.shouldWarnOverspeed(120.0, advisory))
        }
    }

    @Test
    fun roadEvidenceDoesNotMutateZoneStateOrConfidence() = runBlocking {
        val (pack, manifest) = buildSyntheticPack(
            edges = listOf(TestEdge(edgeId = "E_R3", headingDeg = 90.0)),
            points = listOf(
                TestPoint(
                    pointId = "S_P127_3",
                    type = "P127",
                    lat = 10.823,
                    lon = 106.630,
                    valueKmh = 60,
                    edgeId = "E_R3"
                )
            )
        )
        val repo = OfflineSignRepository(pack, manifest, dbFactory)
        repo.use {
            val tracker = ZoneTracker()
            tracker.observe(ZoneEvidence(SignType.R420, "test-seed", 0L, "E_R3", 0.9f, 10.823, 106.630))
            val confidenceBefore = tracker.confidence()
            assertEquals(ZoneState.INSIDE_DENSE, tracker.state())

            val advisory = resolveWithRoadLimits(
                repo,
                edgeId = "E_R3",
                roadLimits = listOf(
                    RoadLimit.supported(
                        "E_R3",
                        RoadLimit.Source.OSM,
                        "synthetic-osm-v1",
                        RoadLimit.Direction.FORWARD,
                        50,
                        0.9f
                    )
                ),
                location = GeoPoint(10.823, 106.630),
                tracker = tracker
            )
            assertNull("conflicting sources must abstain", advisory.valueKmh())
            assertEquals(
                "R.420 legal zone must be kept",
                ZoneState.INSIDE_DENSE,
                tracker.state()
            )
            assertEquals(
                "road evidence must not change zone confidence",
                confidenceBefore.toDouble(),
                tracker.confidence().toDouble(),
                0.0
            )
            assertFalse(
                "road evidence must not flag the zone for verification",
                tracker.needsVerification()
            )
        }
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(32768)
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

    private fun findProjectRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "feature/signs").isDirectory && File(dir, "app").isDirectory) {
                return dir
            }
            dir = dir.parentFile
        }
        return File(".").canonicalFile
    }
}
